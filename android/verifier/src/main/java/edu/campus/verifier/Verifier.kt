package edu.campus.verifier

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The phone's settings and what happened lately (SharedPreferences). */
class Prefs(context: Context) : Store {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("verifier", Context.MODE_PRIVATE)

    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String?) { sp.edit().putString(key, value).apply() }

    var server: String
        get() = sp.getString("server", null) ?: BuildConfig.API_BASE
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()
    var device: String
        get() = sp.getString("device", null) ?: (Build.MANUFACTURER + " " + Build.MODEL).trim()
        set(v) = sp.edit().putString("device", v.trim().take(60)).apply()
    var smsOn: Boolean
        get() = sp.getBoolean("smsOn", true)
        set(v) = sp.edit().putBoolean("smsOn", v).apply()
    var notificationsOn: Boolean
        get() = sp.getBoolean("notificationsOn", true)
        set(v) = sp.edit().putBoolean("notificationsOn", v).apply()
    /** Also pass on "money received" notifications of other apps (a bank's app, for example). */
    var allApps: Boolean
        get() = sp.getBoolean("allApps", false)
        set(v) = sp.edit().putBoolean("allApps", v).apply()

    var lastOkAt: Long
        get() = sp.getLong("lastOkAt", 0)
        set(v) = sp.edit().putLong("lastOkAt", v).apply()
    var lastError: String?
        get() = sp.getString("lastError", null)
        set(v) = sp.edit().putString("lastError", v).apply()
    var sentCount: Int
        get() = sp.getInt("sentCount", 0)
        set(v) = sp.edit().putInt("sentCount", v).apply()

    fun config() = Config(server, token, device)

    /** The last things that happened, newest first ("10:42  Sent ₹20.01 received from…"). */
    fun log(): List<String> = (sp.getString("log", "") ?: "").lines().filter { it.isNotBlank() }

    fun addLog(line: String) {
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val all = listOf("$time  $line") + log()
        sp.edit().putString("log", all.take(40).joinToString("\n")).apply()
    }
}

/** Queues, sends and schedules. Everything here may run on any thread except the main one. */
object Verifier {

    private val lock = Any()
    const val HEARTBEAT_JOB = 1
    const val RETRY_JOB = 2

    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.0"
    }.getOrDefault("1.1.0")

    /** A "money received" message arrived: keep it, and send it at once. */
    fun queue(context: Context, alert: Alert) {
        val p = Prefs(context)
        if (!Outbox(p).add(alert)) return
        p.addLog("Received: " + alert.text.take(90))
        send(context)
    }

    /** Sends everything waiting. If something is left (no internet...), Android tries again when it can. */
    fun send(context: Context): Flush {
        val p = Prefs(context)
        val cfg = p.config()
        cfg.problem?.let { why ->
            p.lastError = why
            return Flush(0, Outbox(p).size(), why)
        }
        synchronized(lock) {
            val outbox = Outbox(p)
            val result = flush(outbox, Sender(cfg, version(context))) { a, answer ->
                p.sentCount = p.sentCount + 1
                p.lastOkAt = System.currentTimeMillis()
                val order = Regex("\"paidOrder\":\"([A-Z0-9]{5})\"").find(answer.body)?.groupValues?.get(1)
                p.addLog(if (order != null) "Confirmed order $order (" + a.text.take(60) + ")" else "Sent to the server")
            }
            p.lastError = result.error
            if (result.left > 0) scheduleRetry(context)
            return result
        }
    }

    /** "I am alive": while the server hears this, students' payments confirm by themselves. */
    fun heartbeat(context: Context): Answer {
        val p = Prefs(context)
        val cfg = p.config()
        cfg.problem?.let { why ->
            p.lastError = why
            return Answer(0, why)
        }
        val r = Sender(cfg, version(context)).heartbeat(smsAllowed(context) && p.smsOn,
            notificationsAllowed(context) && p.notificationsOn)
        if (r.ok) {
            p.lastOkAt = System.currentTimeMillis()
            p.lastError = null
        } else {
            p.lastError = when (r.code) {
                0 -> "No internet connection."
                401 -> "The server refused the token. Copy UPI_ALERT_TOKEN again."
                503 -> "The server has XeoGo Pay bank messages switched off (PAYMENT_MODE=upi, UPI_ALERT_TOKEN)."
                404 -> "No XeoGo server at this address."
                else -> "The server answered ${r.code}."
            }
        }
        return r
    }

    // ---------------------------------------------------------------- Android: jobs and permissions

    /** Check in every 15 minutes, even after a restart. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(HEARTBEAT_JOB) == null) {
            js.schedule(JobInfo.Builder(HEARTBEAT_JOB, ComponentName(context, SendJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        }
    }

    fun scheduleRetry(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        js.schedule(JobInfo.Builder(RETRY_JOB, ComponentName(context, SendJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(5_000)
            .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build())
    }

    fun smsAllowed(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun notificationsAllowed(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }

    fun batteryFree(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}
