package edu.campus.verifier

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Verifier's logic: what leaves the phone, what is kept offline, and how it is sent. */
class CoreTest {

    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.shutdown()

    private class MapStore : Store {
        val m = mutableMapOf<String, String?>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { m[key] = value }
    }

    @Test
    fun onlyMoneyReceivedEverLeavesThePhone() {
        // the UPI business apps, instantly
        listOf("₹20.01 received from Rahul Kumar", "Received ₹20.01 from RAHUL on PhonePe Business",
            "Rahul paid you ₹20.01", "Payment of ₹20.01 received from Rahul",
            // the bank's SMS
            "Dear UPI user A/C X1234 credited by Rs.20.01 on 30Sep26 trf from RAHUL Refno 627312345678 -SBI",
            "Money Received - INR 20.01 in HDFC Bank A/c xx1234 from VPA rahul@okaxis (UPI 627312345678)",
            "Rs 20.01 credited to a/c XX1234 UPI Ref 627312345678"
        ).forEach { assertTrue(it, CreditFilter.looksLikeCredit(it)) }
        listOf("123456 is your OTP to receive Rs 20.01. Do not share it.",
            "Rs.20.01 debited from A/c XX1234 and credited to rahul@ybl (UPI Ref 627312345678)",
            "You paid ₹20.01 to Xerox Center", "Rahul has requested money Rs 20 from you",
            "Your transaction of Rs 20 failed", "Hi, your Swiggy order is on the way", "Received your file, thanks", "", null
        ).forEach { assertFalse(it.toString(), CreditFilter.looksLikeCredit(it)) }
    }

    @Test
    fun aTextFromAPhoneNumberIsNeverMoneyReceived() {
        // banks send under a name the operators registered for them
        assertEquals("SBIUPI", CreditFilter.senderName("AX-SBIUPI"))
        assertEquals("SBIUPI", CreditFilter.senderName("JD-SBIUPI-S"))
        assertEquals("HDFCBK", CreditFilter.senderName("vm-hdfcbk"))
        assertEquals("ICICIB", CreditFilter.senderName("ICICIB"))
        // a person's phone: "Rs 20.01 credited to your a/c" from there is just a text
        listOf("+919876543210", "9876543210", "+91 98765 43210", "098765 43210", "56767", "AB", "", null,
            "AX-9876543210", "SBI UPI <script>", "AX-SBIUPI-S-EXTRA-LONG-NAME").forEach {
            assertNull(it.toString(), CreditFilter.senderName(it))
        }
    }

    @Test
    fun onlyAppsThatNobodyElseCanWriteIntoAreRead() {
        // the shop's business apps: always
        listOf("com.phonepe.app.business", "com.paytm.business", "com.google.android.apps.nbu.paisa.merchant", "com.bharatpe.app")
            .forEach { assertTrue(it, CreditFilter.appAllowed(it, otherApps = false)) }
        // the ordinary UPI apps have chat: not unless the owner switches "Also other apps" on (and the server lists them)
        listOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.sbi.lotusintouch").forEach {
            assertFalse(it, CreditFilter.appAllowed(it, otherApps = false))
            assertTrue(it, CreditFilter.appAllowed(it, otherApps = true))
        }
        // chat and SMS apps: never
        listOf("com.whatsapp", "com.google.android.apps.messaging", "org.telegram.messenger", "com.truecaller").forEach {
            assertFalse(it, CreditFilter.appAllowed(it, otherApps = true))
        }
    }

    @Test
    fun theTokenOnlyTravelsOverHttps() {
        val token = "t".repeat(32)
        assertNull(Config("https://print.example.org", token, "x").problem)
        assertTrue(Config("https://print.example.org", token, "x").ready)
        // trying it out with the demo server on the same Wi-Fi, or in the emulator
        listOf("http://192.168.1.20:8080", "http://10.0.2.2:8080", "http://localhost:8080", "http://127.0.0.1:8080/",
            "http://172.16.4.9:8080", "http://172.31.255.1").forEach { assertNull(it, Config(it, token, "x").problem) }
        // anywhere else, plain http would show the token to everyone on the way
        listOf("http://print.example.org", "http://203.0.113.9:8080", "http://192.168.1.20.evil.example", "http://172.32.0.1",
            "http://localhost.evil.example", "http://evil.example/192.168.1.20", "http://10.0.0.1@evil.example").forEach {
            val why = Config(it, token, "x").problem
            assertTrue(it + ": " + why, why != null && why.contains("https://"))
            assertFalse(Config(it, token, "x").ready)
        }
        assertTrue(Config("print.example.org", token, "x").problem!!.contains("server address"))
        assertTrue(Config("https://print.example.org", "short", "x").problem!!.contains("token"))
    }

    @Test
    fun theOutboxKeepsEverythingUntilSentAndTheSameMessageOnce() {
        val store = MapStore()
        var now = 1_000_000L
        val box = Outbox(store) { now }
        val a = Alert.of("notification:com.phonepe.app.business", "₹20.01 received", "₹20.01 received from Rahul | \"x\"\nline 2", 1L)
        assertTrue(box.add(a))
        assertFalse(box.add(a.copy(id = "other")))                  // the same notification shown again
        now += 11 * 60_000                                           // but after 10 minutes it is a new payment
        assertTrue(box.add(a.copy(id = "later")))
        // survives the app being closed: a new Outbox on the same storage
        val again = Outbox(store) { now }
        assertEquals(2, again.size())
        assertEquals(a, again.pending()[0])                         // "|", quotes and new lines kept exactly
        again.remove(a.id)
        assertEquals(listOf("later"), Outbox(store).pending().map { it.id })
    }

    @Test
    fun sendingWithTheTokenAndKeepingWhatFailed() {
        val box = Outbox(MapStore())
        box.add(Alert.of("sms", "AX-SBIUPI", "Rs 20.01 credited UPI Ref 627312345678", 42L))
        box.add(Alert.of("notification:com.phonepe.app.business", "₹6.01 received", "₹6.01 received from Priya", 43L))
        val sender = Sender(Config(server.url("/").toString(), "t".repeat(32), "Shop phone"), "1.0.0")

        // server down: nothing is lost
        server.enqueue(MockResponse().setResponseCode(502))
        var r = flush(box, sender)
        assertEquals(0, r.sent)
        assertEquals(2, r.left)
        val first = server.takeRequest()
        assertEquals("/api/v1/payments/upi/alerts", first.path)
        assertEquals("t".repeat(32), first.getHeader("X-Alert-Token"))
        val body = first.body.readUtf8()
        assertTrue(body, body.contains("\"text\":\"Rs 20.01 credited UPI Ref 627312345678\""))
        assertTrue(body, body.contains("\"source\":\"sms\"") && body.contains("\"device\":\"Shop phone\"") &&
            body.contains("\"sentStamp\":\"42\"") && body.contains("\"from\":\"AX-SBIUPI\""))

        // back again: both go, oldest first
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"paidOrder\":\"K7M4X\"}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        val sent = mutableListOf<String>()
        r = flush(box, sender) { a, _ -> sent += a.source }
        assertEquals(2, r.sent)
        assertEquals(0, r.left)
        assertNull(r.error)
        assertEquals(listOf("sms", "notification:com.phonepe.app.business"), sent)

        // a wrong token stops and says so; the message waits
        box.add(Alert.of("sms", "AX-BANK", "Rs 9.01 credited", 44L))
        server.enqueue(MockResponse().setResponseCode(401))
        r = flush(box, sender)
        assertEquals(1, r.left)
        assertTrue(r.error!!.contains("token"))
    }

    @Test
    fun theHeartbeatSaysWhatThePhoneMayRead() {
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"payee\":\"xeroxshop@okaxis\"}"))
        val r = Sender(Config(server.url("/").toString(), "t".repeat(32), "Shop phone"), "1.0.0").heartbeat(true, false)
        assertTrue(r.ok)
        val req = server.takeRequest()
        assertEquals("/api/v1/payments/upi/heartbeat", req.path)
        assertEquals("{\"device\":\"Shop phone\",\"version\":\"1.0.0\",\"sms\":true,\"notifications\":false}", req.body.readUtf8())
        assertFalse(Config("", "short", "x").ready)
    }
}
