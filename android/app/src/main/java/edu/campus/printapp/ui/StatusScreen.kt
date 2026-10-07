package edu.campus.printapp.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.flow.clockText
import edu.campus.printapp.flow.docSummary
import edu.campus.printapp.flow.whenLine
import edu.campus.printapp.net.DocumentView
import edu.campus.printapp.net.OrderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun docLamp(sd: DocumentView): Color = when (sd.status) {
    "COMPLETED" -> CP.Accent
    "FAILED", "CANCELLED", "REJECTED" -> CP.Danger
    else -> CP.Warn
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)

private fun stageOf(v: OrderView, sd: DocumentView): String =
    if (v.status == "AWAITING_PAYMENT" && sd.status == "READY") "Prints after payment" else sd.stage ?: ""

/**
 * Your prints: how far the order is, with the times, and the files themselves.
 * At the counter the student taps "I'm at the counter" and shows this screen;
 * the staff see the same files on theirs and hand over the pages. There is no
 * pickup code to show or type.
 */
@Composable
fun StatusScreen(st: SessionState, session: OrderSession) {
    val v = st.order
    val docs = v?.live ?: emptyList()
    var index by remember(v?.orderId) { mutableIntStateOf(0) }
    val i = index.coerceIn(0, (docs.size - 1).coerceAtLeast(0))
    val collected = v?.isCollected == true
    val nowMs = System.currentTimeMillis() + st.skewMs

    // At the counter the screen stays on: the staff are looking at it.
    val view = LocalView.current
    DisposableEffect(st.atCounter) {
        view.keepScreenOn = st.atCounter
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 620.dp).align(Alignment.CenterHorizontally).padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CP.Surface)
                .then(if (st.atCounter) Modifier.border(3.dp, CP.Accent, RoundedCornerShape(24.dp)) else Modifier)) {

                // how far it is, and when
                Column(Modifier.fillMaxWidth().background(if (collected) CP.Accent else CP.Ink).padding(vertical = 20.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(((st.shop?.centerName ?: "Xerox center") + " · your prints").uppercase(), fontSize = 12.sp, letterSpacing = 2.sp,
                        color = if (collected) Color(0xFFE3F3EA) else Color(0xFF9FB0C3), fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Lamp(if (collected) Color.White else lampFor(v), 12.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(v?.stage ?: "Opening your order…", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("stage"))
                    }
                    val times = v?.let { whenLine(it, nowMs) }.orEmpty()
                    if (times.isNotEmpty()) {
                        Text(times, fontSize = 14.sp, color = if (collected) Color(0xFFE3F3EA) else Color(0xFFC9D3DE),
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).testTag("times"))
                    }
                }

                if (st.atCounter && v != null) CounterStrip(st, v)

                // the files themselves: one large sheet at a time
                if (docs.isNotEmpty()) {
                    val sd = docs[i]
                    Row(Modifier.fillMaxWidth().background(CP.Viewer).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { index = i - 1 }, enabled = i > 0) {
                            if (docs.size > 1) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous file",
                                tint = if (i > 0) Color.White else Color(0x55FFFFFF))
                        }
                        Box(Modifier.weight(1f).heightIn(min = 220.dp, max = if (st.atCounter) 520.dp else 440.dp), contentAlignment = Alignment.Center) {
                            FilePicture(st.pictures[sd.id], sd, large = true)
                        }
                        IconButton(onClick = { index = i + 1 }, enabled = i < docs.size - 1) {
                            if (docs.size > 1) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next file",
                                tint = if (i < docs.size - 1) Color.White else Color(0x55FFFFFF))
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((if (docs.size > 1) "${i + 1} of ${docs.size} · " else "") + sd.fileName, fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.testTag("fileName"))
                        Text(docSummary(sd), fontSize = 13.5.sp, color = CP.Muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(6.dp))
                        val lamp = docLamp(sd)
                        Text(stageOf(v!!, sd), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            color = if (lamp == CP.Accent) Color(0xFF14613B) else if (lamp == CP.Danger) Color(0xFF7C2A1F) else Color(0xFF6D4411),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(
                                if (lamp == CP.Accent) CP.AccentSoft else if (lamp == CP.Danger) CP.DangerSoft else CP.WarnSoft)
                                .padding(horizontal = 11.dp, vertical = 3.dp))
                    }
                    if (docs.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            docs.forEachIndexed { k, d ->
                                Box(Modifier.size(width = 46.dp, height = 58.dp).clip(RoundedCornerShape(6.dp))
                                    .border(2.dp, if (k == i) CP.Ink else CP.Line, RoundedCornerShape(6.dp)).background(CP.Sunk)
                                    .clickable { index = k }, contentAlignment = Alignment.Center) {
                                    FilePicture(st.pictures[d.id], d, large = false)
                                    Text("${k + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).clip(RoundedCornerShape(4.dp))
                                            .background(CP.Ink).padding(horizontal = 4.dp))
                                }
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    val message = when {
                        v == null -> null
                        collected -> "Handed over at the counter. Thank you!"
                        st.atCounter -> "Staff give you the pages that look like these. This screen turns to Collected when they do."
                        v.status == "COMPLETED" -> "At the Xerox counter, tap the green button and show this screen. Staff see the same files and hand you the pages."
                        v.upiOpen -> v.message
                        v.status == "AWAITING_PAYMENT" -> "Checking your payment with the bank. This can take a minute."
                        v.paidAt != null && v.message == null && (v.status == "QUEUED" || v.status == "PRINTING") ->
                            "Your files are going to the printer. At the counter, tap the green button and show this screen."
                        else -> v.message
                    }
                    message?.let {
                        Text(it, fontSize = 14.5.sp, color = CP.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    if (st.offline) {
                        Spacer(Modifier.size(10.dp))
                        Hint("No internet right now: this shows your order as this phone last saw it. At the counter, tell the staff a file's name.")
                    }

                    if (v != null && v.canCollect && !st.atCounter) {
                        Spacer(Modifier.size(16.dp))
                        Button(
                            onClick = { session.atCounter() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("atCounter"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CP.Accent)
                        ) { Text("I’m at the counter", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                    if (st.atCounter) {
                        TextButton(onClick = { session.leaveCounter() }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("leaveCounter")) {
                            Text("I’m not at the counter any more", color = CP.Muted)
                        }
                    }

                    if (v != null && v.paidAt != null && v.status != "CANCELLED") {
                        Spacer(Modifier.size(14.dp))
                        val done = v.status == "COMPLETED"
                        Track(if (v.isFree) "Sent" else "Paid", done = true, now = false)
                        Track(if (v.status == "QUEUED") "Waiting for a printer" else "Printing", done = done, now = !done)
                        Track("Ready at the counter", done = done, now = false)
                        Track(if (done && !collected) "Collect at the counter" else "Collected", done = collected, now = done && !collected)
                    }
                    if (v != null && v.documents.size > 1) {
                        Spacer(Modifier.size(14.dp))
                        v.documents.forEach { sd ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(CP.Surface2)
                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Lamp(docLamp(sd))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${sd.position}. ${sd.fileName}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(stageOf(v, sd) + (sd.printedAt?.let { " · " + clockText(it, nowMs) } ?: ""), fontSize = 13.sp, color = CP.Muted)
                                }
                            }
                        }
                    }
                    if (v != null) {
                        Spacer(Modifier.size(12.dp))
                        Divider()
                        Text("Order " + v.pickupCode + " · " + plural(v.live.size.coerceAtLeast(1), "file", "files") +
                            (v.totalSheets?.let { " · " + plural(it, "sheet", "sheets") } ?: "") +
                            (v.freePages?.let { " · free (" + plural(it, "page", "pages") + ")" } ?: v.amountPaise?.let { " · " + rupees(it) } ?: ""),
                            fontSize = 13.5.sp, color = CP.Muted,
                            modifier = Modifier.padding(top = 12.dp).testTag("orderLine"))
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

/**
 * "Show this screen to the staff", with a running clock: the staff can tell
 * the live screen from a screenshot, and their own screen shows this order now.
 */
@Composable
private fun CounterStrip(st: SessionState, v: OrderView) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() + st.skewMs) }
    LaunchedEffect(st.skewMs) {
        while (true) {
            now = System.currentTimeMillis() + st.skewMs
            delay(1000)
        }
    }
    Row(Modifier.fillMaxWidth().background(if (st.counterOffline) CP.Warn else CP.Accent).padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("counterStrip"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0x38FFFFFF)), contentAlignment = Alignment.Center) {
            Icon(if (st.counterOffline) Icons.Filled.Warning else Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Show this screen to the staff", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(when {
                st.counterOffline -> "This phone is offline: tell the staff a file's name, and they find your order."
                v.status == "COMPLETED" -> "They can see your order on their screen now."
                v.status == "FAILED" -> "They can see your order. One file had a problem: they will sort it out."
                else -> "They can see you are here. Still printing: ${v.documentsDone} of ${v.live.size} done."
            }, color = Color.White, fontSize = 13.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(CLOCK.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())).lowercase(Locale.ENGLISH), color = Color.White,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** The picture of a file's first sheet (grey for black & white), or a plain sheet with its kind while there is none. */
@Composable
private fun FilePicture(path: String?, sd: DocumentView, large: Boolean) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val img = bitmap
    if (img != null) {
        Image(img, contentDescription = if (large) "First sheet of ${sd.fileName}" else null,
            colorFilter = if (sd.settings?.color == false) grey else null,
            contentScale = if (large) ContentScale.Fit else ContentScale.Crop,
            modifier = if (large) Modifier.padding(horizontal = 4.dp).shadow(10.dp).background(Color.White) else Modifier.fillMaxSize())
    } else if (large) {
        Column(Modifier.width(150.dp).aspectRatio(210f / 297f).shadow(10.dp).background(Color.White).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (sd.fileType == "JPEG") "JPG" else sd.fileType, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (sd.hasPreview) "Getting the picture…" else "The picture shows once it is printed", fontSize = 12.sp, color = CP.Muted,
                textAlign = TextAlign.Center)
        }
    } else {
        Text(if (sd.fileType == "PDF") "PDF" else "IMG", fontSize = 10.sp, color = CP.Faint, fontWeight = FontWeight.SemiBold)
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
