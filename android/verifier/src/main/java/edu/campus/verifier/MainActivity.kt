package edu.campus.verifier

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFF0E1A2B)
private val Muted = Color(0xFF5B6879)
private val Line = Color(0xFFE3E6E0)
private val Accent = Color(0xFF1F8A55)
private val Warn = Color(0xFFB26B12)
private val Danger = Color(0xFFB4382A)

/**
 * The CampusPay Verifier: set up once on the Xerox center's phone, then it
 * works by itself in the background (the screen only shows how it is doing).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Verifier.schedule(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Accent)) { Screen() }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        Thread { Verifier.heartbeat(app); Verifier.send(app) }.start()
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val p = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    var server by remember { mutableStateOf(p.server) }
    var token by remember { mutableStateOf(p.token) }
    var device by remember { mutableStateOf(p.device) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // read again every 2 seconds: the permissions may change in Settings, messages arrive in the background
    @Suppress("UNUSED_EXPRESSION") tick
    val sms = Verifier.smsAllowed(context)
    val notifications = Verifier.notificationsAllowed(context)
    val battery = Verifier.batteryFree(context)
    val cfg = p.config()
    val recentOk = System.currentTimeMillis() - p.lastOkAt < 30 * 60_000
    val reading = (sms && p.smsOn) || (notifications && p.notificationsOn)
    val working = cfg.ready && recentOk && reading && p.lastError == null
    val waiting = Outbox(p).size()

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F6F3)).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(Ink).statusBarsPadding().padding(20.dp)) {
            Text("CAMPUSPAY", color = Color(0xFF9FB0C3), fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
            Text("Verifier", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (working) Accent else if (cfg.ready) Warn else Danger))
                Spacer(Modifier.width(10.dp))
                Text(when {
                    !cfg.ready -> "Not set up yet"
                    !reading -> "Allow it to read payments (step 2)"
                    p.lastError != null -> p.lastError!!
                    !recentOk -> "Not in touch with the server yet"
                    else -> "Working: students' payments confirm by themselves"
                }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Sent ${p.sentCount} payment message(s)" + (if (waiting > 0) " · $waiting waiting for internet" else "") +
                (if (p.lastOkAt > 0) " · server reached " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.lastOkAt)) else ""),
                color = Color(0xFFB5C2D0), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card("1. Connect to your Campus Print server") {
                Field("Server address", server, { server = it })
                Field("Token (UPI_ALERT_TOKEN from the server's settings)", token, { token = it },
                    secret = !showToken, mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(showToken, { showToken = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Show the token", fontSize = 13.sp, color = Muted)
                }
                Field("This phone's name", device, { device = it })
                if (server.startsWith("http://") && !server.contains("localhost") && !server.contains("192.168.") && !server.contains("10.0.2.2")) {
                    Note("This address is not https://: the token could be read on the way. Use the https:// address of your server.", Warn)
                }
                Button(onClick = {
                    p.server = server; p.token = token; p.device = device
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Verifier.heartbeat(context).also { Verifier.send(context) } }
                        result = if (r.ok) "Connected. Payments to ${Regex("\"payee\":\"([^\"]+)\"").find(r.body)?.groupValues?.get(1) ?: "your UPI ID"} now confirm by themselves."
                                 else p.lastError ?: "Could not connect."
                        busy = false
                        tick++
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (busy) "Connecting…" else "Save and connect", fontWeight = FontWeight.Bold)
                }
                result?.let { Note(it, if (it.startsWith("Connected")) Accent else Danger) }
            }

            Card("2. Allow it to read payments") {
                Permission("UPI app notifications (instant)",
                    "“₹20.01 received” from PhonePe Business, Paytm for Business, Google Pay… Arrives in seconds.",
                    notifications, p.notificationsOn, { p.notificationsOn = it; tick++ }) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                Permission("Bank SMS (backup)",
                    "The bank's “credited” SMS. Only those are sent; every other SMS stays on the phone.",
                    sms, p.smsOn, { p.smsOn = it; tick++ }) { askSms.launch(android.Manifest.permission.RECEIVE_SMS) }
                Permission("Never paused by battery saver", "So a payment is never held back.", battery, null, null) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.packageName)))
                }
                if (Build.VERSION.SDK_INT >= 33 && (!sms || !notifications)) {
                    Note("Android says “Restricted setting”? Tap Open App info → ⋮ (top right) → Allow restricted settings, " +
                        "then come back and tap Allow again.", Warn)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName)))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open App info") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Also other apps", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("For example your bank's own app. Only “money received” messages are sent.", fontSize = 12.5.sp, color = Muted)
                    }
                    Switch(p.allApps, { p.allApps = it; tick++ })
                }
                Note("Keep this phone switched on, on the internet and charging: it is the Xerox center's payment line.", Muted)
            }

            Card("Recent") {
                val log = p.log()
                if (log.isEmpty()) Text("Nothing yet. Pay a small amount to your UPI ID to try it.", fontSize = 13.sp, color = Muted)
                log.take(15).forEach { Text(it, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = Ink) }
                OutlinedButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { Verifier.send(context); Verifier.heartbeat(context) }; tick++ }
                }, modifier = Modifier.fillMaxWidth()) { Text("Send now") }
            }
            Text("CampusPay Verifier ${Verifier.version(context)} · Campus Print · by Vedant Pravin Surve",
                fontSize = 12.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
        .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, mono: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default))
}

@Composable
private fun Permission(title: String, detail: String, allowed: Boolean, on: Boolean?, onToggle: ((Boolean) -> Unit)?,
                       ask: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (allowed) Accent else Danger))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
            Text(detail, fontSize = 12.5.sp, color = Muted)
        }
        Spacer(Modifier.width(8.dp))
        if (!allowed) {
            Button(onClick = ask, shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Allow") }
        } else if (on != null && onToggle != null) {
            Switch(on, onToggle)
        } else {
            Text("✓", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, fontSize = 13.sp, color = color)
}
