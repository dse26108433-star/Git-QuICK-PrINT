package edu.campus.verifier

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/*
 * The XeoGo Pay Verifier's logic, with no Android in it (tested on the computer):
 *
 *   CreditFilter  is this "money received"? Only those ever leave the phone.
 *   Outbox        what still has to be sent; kept on the phone, so nothing is lost offline.
 *   Sender        sends it to the XeoGo server, with the shop's secret token.
 */

/** A "money received" message, waiting to be sent. */
data class Alert(val id: String, val source: String, val sender: String?, val text: String, val stamp: Long) {
    companion object {
        fun of(source: String, sender: String?, text: String, stamp: Long) =
            Alert(UUID.randomUUID().toString(), source, sender, text.trim(), stamp)
    }
}

object CreditFilter {

    /**
     * The UPI apps for shops: their "₹20.01 received" notifications are passed
     * on by default. Only the payment company's servers can write into these
     * (they have no chat), and they push within seconds of the payment: this,
     * not the bank's SMS, is what makes XeoGo Pay instant.
     *
     * The ordinary apps (PhonePe, Google Pay, Paytm...) are not here on
     * purpose: anyone can send the shop a chat message in them that reads
     * "₹20.01 received", so the server never counts those as money.
     */
    val BUSINESS_APPS = setOf(
        "com.phonepe.app.business",                    // PhonePe Business
        "com.google.android.apps.nbu.paisa.merchant",  // Google Pay for Business
        "com.paytm.business",                          // Paytm for Business
        "com.bharatpe.app"                             // BharatPe
    )

    /**
     * SMS and chat apps: never passed on, whatever is switched on. Their
     * notifications repeat an SMS (which is read directly, with its real
     * sender), or are written by other people.
     */
    val MESSAGING_APPS = setOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.android.messaging", "com.truecaller", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android", "com.snapchat.android",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.google.android.dialer"
    )

    /** Is this app's "money received" notification passed on? otherApps: the "Also other apps" switch. */
    fun appAllowed(pkg: String, otherApps: Boolean): Boolean =
        pkg !in MESSAGING_APPS && (pkg in BUSINESS_APPS || otherApps)

    private val OPERATOR_PREFIX = Regex("^[A-Z]{2}-(.+)$")
    private val KIND_SUFFIX = Regex("^(.+)-[A-Z]$")
    private val SENDER_NAME = Regex("[A-Z0-9]{3,11}")

    /**
     * The sender NAME of an SMS ("AX-SBIUPI-S" is "SBIUPI"), or null when it
     * came from a phone number. Banks send under names registered with the
     * telecom operators; anyone can text "Rs 20 credited" from a number, so
     * such an SMS is never money received and never leaves the phone. (The
     * server checks the same, and knows which names are banks.)
     */
    fun senderName(from: String?): String? {
        var s = from?.trim()?.uppercase() ?: return null
        OPERATOR_PREFIX.matchEntire(s)?.let { s = it.groupValues[1] }
        KIND_SUFFIX.matchEntire(s)?.let { s = it.groupValues[1] }
        if (!SENDER_NAME.matches(s)) return null
        return if (s.count { it in 'A'..'Z' } >= 3) s else null
    }

    private val IGNORE = Regex("\\b(otp|one[ -]?time[ -]?password|verification code|requested|request of|collect request" +
        "|is requesting|has requested|payment request|mandate|autopay|failed|declined|reversed|will be credited|to be credited)\\b")
    private val CREDIT = Regex("\\b(credited|credit of|received|deposited|added to|paid you|sent you|cr\\.?)(?![a-z])")
    private val DEBIT = Regex("\\b(debited|debit of|dr\\.?|withdrawn|spent|sent|paid to|paid rs|paid inr|transferred to|deducted|purchase)(?![a-z])")
    private val AMOUNT = Regex("(?:(?<![a-z])(?:rs\\.?|inr|rupees)|₹)\\s*[:.]?\\s*[0-9]")

    /** Money came IN, with an amount. OTPs, payment requests and money going out never match. */
    fun looksLikeCredit(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase().replace(' ', ' ')
        if (IGNORE.containsMatchIn(lower)) return false
        val c = CREDIT.find(lower)?.range?.first ?: return false
        val d = DEBIT.find(lower)?.range?.first
        if (d != null && d < c) return false
        return AMOUNT.containsMatchIn(lower)
    }
}

/** Where the phone keeps small things (SharedPreferences on the phone, a map in tests). */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * The messages still to be sent, oldest first. The same message seen twice
 * within 10 minutes (a notification shown again, an SMS broadcast twice) is
 * kept once.
 */
class Outbox(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {

    @Synchronized
    fun add(a: Alert): Boolean {
        val now = clock()
        val fingerprint = (a.source + "|" + a.text).hashCode().toString()
        val seen = decodeSeen(store.get(SEEN)).filterValues { now - it < 10 * 60_000 }.toMutableMap()
        if (fingerprint in seen) return false
        seen[fingerprint] = now
        store.put(SEEN, seen.entries.joinToString(";") { it.key + "=" + it.value })
        store.put(QUEUE, encode(pending() + a))
        return true
    }

    @Synchronized
    fun pending(): List<Alert> = decode(store.get(QUEUE))

    @Synchronized
    fun remove(id: String) {
        store.put(QUEUE, encode(pending().filter { it.id != id }))
    }

    fun size(): Int = pending().size

    private fun encode(list: List<Alert>): String = list.takeLast(200).joinToString("\n") { a ->
        listOf(a.id, a.source, a.sender ?: "", a.text, a.stamp.toString()).joinToString("|") { b64(it) }
    }

    private fun decode(s: String?): List<Alert> = s.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split("|").map { unb64(it) }
        if (f.size != 5) null else Alert(f[0], f[1], f[2].ifEmpty { null }, f[3], f[4].toLongOrNull() ?: 0L)
    }

    private fun decodeSeen(s: String?): Map<String, Long> = s.orEmpty().split(";").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: 0L)
    }.toMap()

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
    private fun unb64(s: String) = String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)

    companion object {
        private const val QUEUE = "outbox.queue"
        private const val SEEN = "outbox.seen"
    }
}

/** The XeoGo server's address, the shop's secret token (UPI_ALERT_TOKEN), and this phone's name. */
data class Config(val server: String, val token: String, val device: String) {

    /**
     * Why nothing can be sent yet, or null. The token is what lets a message
     * pay an order, so it only ever travels over https; plain http is for a
     * test server on this phone or the same Wi-Fi.
     */
    val problem: String? get() = when {
        !server.startsWith("http://") && !server.startsWith("https://") -> "Type the server address (it starts with https://)."
        server.startsWith("http://") && !localAddress(server) ->
            "Use the https:// address of your server: over http:// the token could be read on the way."
        token.length < 24 -> "Type the token (UPI_ALERT_TOKEN from the server's settings)."
        else -> null
    }

    val ready: Boolean get() = problem == null

    companion object {
        private val PRIVATE = Regex("localhost|127(\\.\\d{1,3}){3}|10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2}")

        /** A server on this phone or the same Wi-Fi (trying it out with the demo server). */
        fun localAddress(server: String): Boolean {
            val host = server.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase()
            return PRIVATE.matches(host)
        }
    }
}

/** The server's answer. code 0: no answer at all (no internet). */
data class Answer(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

class Sender(private val cfg: Config, private val version: String) {

    fun send(a: Alert): Answer = post("/api/v1/payments/upi/alerts", json(
        "text" to a.text, "from" to a.sender, "source" to a.source, "sentStamp" to a.stamp.toString(),
        "device" to cfg.device))

    fun heartbeat(sms: Boolean, notifications: Boolean): Answer = post("/api/v1/payments/upi/heartbeat", json(
        "device" to cfg.device, "version" to version, "sms" to sms, "notifications" to notifications))

    private fun post(path: String, body: String): Answer = try {
        val c = URL(cfg.server.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Alert-Token", cfg.token)
        c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
        c.disconnect()
        Answer(code, text)
    } catch (e: Exception) {
        Answer(0, e.message ?: e.javaClass.simpleName)
    }

    companion object {
        fun json(vararg fields: Pair<String, Any?>): String = fields.joinToString(",", "{", "}") { (k, v) ->
            "\"" + k + "\":" + when (v) {
                null -> "null"
                is Boolean -> v.toString()
                else -> "\"" + escape(v.toString()) + "\""
            }
        }

        private fun escape(s: String) = buildString {
            for (ch in s) when (ch) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}

/** What happened when the outbox was sent. */
data class Flush(val sent: Int, val left: Int, val error: String?)

/**
 * Sends everything waiting, oldest first. A message the server could not use
 * (400) is dropped; with no internet, or the server down, it stays for later.
 * A wrong token stops everything: nothing is sent until it is fixed.
 */
fun flush(outbox: Outbox, sender: Sender, onSent: (Alert, Answer) -> Unit = { _, _ -> }): Flush {
    var sent = 0
    var error: String? = null
    for (a in outbox.pending()) {
        val r = sender.send(a)
        when {
            r.ok -> { outbox.remove(a.id); sent++; onSent(a, r) }
            r.code == 400 || r.code == 413 -> outbox.remove(a.id)
            r.code == 401 -> { error = "The server refused the token. Copy UPI_ALERT_TOKEN again."; break }
            r.code == 503 -> { error = "The server has XeoGo Pay bank messages switched off (PAYMENT_MODE=upi, UPI_ALERT_TOKEN)."; break }
            r.code == 0 -> { error = "No internet connection. It is sent as soon as there is one."; break }
            else -> { error = "The server answered ${r.code}. It is tried again soon."; break }
        }
    }
    return Flush(sent, outbox.size(), error)
}
