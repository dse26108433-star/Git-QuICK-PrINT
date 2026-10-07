package edu.campus.verifier

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Instant: the UPI business app (PhonePe Business, Paytm for Business, Google
 * Pay for Business, BharatPe) shows "₹20.01 received from ..." a second or two
 * after the payment, pushed by its own servers. That notification is passed on
 * at once. Chat and SMS apps are never read.
 */
class NotificationWatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val p = Prefs(this)
        if (!p.notificationsOn) return
        val pkg = sbn.packageName
        if (pkg == packageName || !CreditFilter.appAllowed(pkg, p.allApps)) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val body = if (big != null && big.length > (text?.length ?: 0)) big else text
        val all = listOfNotNull(title, body).joinToString(" ").trim()
        if (!CreditFilter.looksLikeCredit(all)) return
        val alert = Alert.of("notification:$pkg", title, all, sbn.postTime)
        val app = applicationContext
        Thread { Verifier.queue(app, alert) }.start()
    }

    override fun onListenerConnected() {
        Prefs(this).addLog("Reading payment notifications: on")
    }
}

/**
 * Backup: the bank's "credited" SMS (it can come later than the app's
 * notification). Only an SMS sent under a sender name is read: one from a
 * phone number is somebody's text, whatever it says, and stays on the phone.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val p = Prefs(context)
        if (!p.smsOn) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // One SMS can arrive in several parts: put each sender's parts together.
        val alerts = messages.groupBy { it.originatingAddress ?: "" }.mapNotNull { (from, parts) ->
            if (CreditFilter.senderName(from) == null) return@mapNotNull null
            val text = parts.joinToString("") { it.messageBody ?: "" }
            if (CreditFilter.looksLikeCredit(text)) Alert.of("sms", from, text, parts.first().timestampMillis) else null
        }
        if (alerts.isEmpty()) return                  // not about money received: never leaves the phone
        val done = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                alerts.forEach { Verifier.queue(app, it) }
            } finally {
                done.finish()
            }
        }.start()
    }
}

/** After a restart (or an update of this app): keep checking in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Verifier.schedule(context)
        Verifier.scheduleRetry(context)
    }
}

/** Sends what is waiting (and, every 15 minutes, says "I am alive"). */
class SendJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            val left = Verifier.send(app).left
            if (params.jobId == Verifier.HEARTBEAT_JOB) Verifier.heartbeat(app)
            jobFinished(params, params.jobId == Verifier.RETRY_JOB && left > 0)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
