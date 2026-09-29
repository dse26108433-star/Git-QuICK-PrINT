package edu.campus.printapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.net.DocumentView

private fun docLamp(sd: DocumentView): Color = when (sd.status) {
    "COMPLETED" -> CP.Accent
    "FAILED", "CANCELLED", "REJECTED" -> CP.Danger
    else -> CP.Warn
}

/** The pickup code, and how the order is getting on, file by file. */
@Composable
fun StatusScreen(st: SessionState, session: OrderSession) {
    val v = st.order
    val code = v?.pickupCode ?: st.current?.code ?: "-----"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp).align(Alignment.CenterHorizontally).padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CP.Surface)) {
                Column(Modifier.fillMaxWidth().background(CP.Ink).padding(vertical = 26.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("YOUR PICKUP CODE", fontSize = 13.sp, letterSpacing = 3.sp, color = Color(0xFFB5C2D0), fontWeight = FontWeight.SemiBold)
                    Text(code, fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp, color = Color.White,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pickupCode"))
                    Text("Show this at the counter · " + (st.shop?.centerName ?: "Xerox center"), fontSize = 14.sp,
                        color = Color(0xFFB5C2D0), textAlign = TextAlign.Center)
                }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Lamp(lampFor(v), 12.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(v?.stage ?: "Checking…", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("stage"))
                    }
                    val message = when {
                        v == null -> null
                        v.upiOpen -> v.message
                        v.status == "AWAITING_PAYMENT" -> "Checking your payment with the bank. This can take a minute."
                        else -> v.message
                    }
                    message?.let {
                        Text(it, fontSize = 14.sp, color = CP.Muted, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    if (v != null && v.paidAt != null && v.status != "CANCELLED") {
                        Spacer(Modifier.size(16.dp))
                        val done = v.status == "COMPLETED"
                        val printing = v.status == "PRINTING"
                        Track("Paid", done = true, now = false)
                        Track(if (v.status == "QUEUED") "Waiting for a printer" else "Printing", done = done, now = !done)
                        Track("Ready at the counter", done = done, now = false)
                        if (printing && v.documents.size > 1) Hint("${v.documentsDone} of ${v.live.size} files done.")
                    }
                    if (v != null && v.documents.isNotEmpty()) {
                        Spacer(Modifier.size(14.dp))
                        v.documents.forEach { sd ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CP.Surface2)
                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Lamp(docLamp(sd))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${sd.position}. ${sd.fileName}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    val s = sd.settings
                                    val stage = if (v.status == "AWAITING_PAYMENT" && sd.status == "READY") "Prints after payment" else sd.stage
                                    Text((stage ?: "") + (if (s != null) " · " + (if (s.color) "colour" else "B/W") + " · " +
                                        plural((sd.sheets ?: 0) * s.copies, "sheet", "sheets") else ""), fontSize = 13.sp, color = CP.Muted)
                                }
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(plural(v.live.size.coerceAtLeast(1), "file", "files") +
                            (v.totalSheets?.let { " · " + plural(it, "sheet", "sheets") } ?: "") +
                            (v.amountPaise?.let { " · " + rupees(it) } ?: ""), fontSize = 14.sp, color = CP.Muted)
                        v.refundDuePaise?.let { Hint("Refund due for files the counter cancelled: " + rupees(it) + ".") }
                    }
                    Spacer(Modifier.size(16.dp))
                    if (v != null && v.upiOpen) {
                        PrimaryButton(if (v.payment?.note != null) "Send the reference again or pay" else "Show payment details",
                            { session.resumeUpi() }, Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("payAgain"))
                    }
                    PrimaryButton("Print more files", { session.resetToStart() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Track(label: String, done: Boolean, now: Boolean) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Lamp(if (done) CP.Accent else if (now) CP.Warn else CP.Line2)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = if (done || now) CP.Ink else CP.Faint,
            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
    }
}
