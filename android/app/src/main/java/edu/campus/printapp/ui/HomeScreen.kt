package edu.campus.printapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import edu.campus.printapp.net.OrderView
import edu.campus.printapp.net.ShopView

fun lampFor(v: OrderView?): Color = when (v?.status) {
    null -> CP.Line2
    "COMPLETED" -> CP.Accent
    "FAILED", "CANCELLED", "EXPIRED" -> CP.Danger
    else -> CP.Warn
}

/** "A3 paper: double price · Stapling ₹2 per copy." */
fun priceExtras(s: ShopView, st: SessionState): String {
    val p = s.printing?.pricing ?: return ""
    val out = mutableListOf<String>()
    s.printing.paperSizes.forEach { ps ->
        val pct = p.paperSizePercent[ps.id]
        if (ps.id != "A4" && pct != null && pct != 100) out += ps.id + " paper: " + (if (pct == 200) "double" else "$pct %") + " price"
    }
    val words = mapOf("STAPLE" to "Stapling", "PUNCH" to "Hole punching", "BIND" to "Binding")
    p.finishingPaise.forEach { (k, v) -> if (v > 0 && st.offered.finishing.any { it.startsWith(k) }) out += (words[k] ?: k) + " " + rupees(v) + " per copy" }
    return if (out.isEmpty()) "" else out.joinToString(" · ") + "."
}

/**
 * arrived: the opening is over (or there is none): the page is shown. In the
 * student app its parts then arrive one after the other (Modifier.riseIn).
 */
@Composable
fun HomeScreen(st: SessionState, session: OrderSession, onChooseFiles: () -> Unit, arrived: Boolean = true) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(8.dp))
            // an order that is not finished yet
            if (st.docs.isNotEmpty() || st.draft != null) {
                CardBox(Modifier.padding(bottom = 16.dp).riseIn(arrived, 0), border = CP.Ink) {
                    Text("Your order is not finished", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (st.docs.isEmpty()) "Continue where you stopped." else plural(st.docs.size, "file", "files") +
                        (if (st.staffApp) " waiting to be set up and sent to print." else " waiting to be set up and paid."),
                        fontSize = 14.sp, color = CP.Muted)
                    Spacer(Modifier.size(10.dp))
                    PrimaryButton("Continue your order", { session.continueOrder() }, Modifier.fillMaxWidth().testTag("continue"))
                }
            }
            Text(if (st.staffApp) "Staff printing." else "Print from your phone.", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                lineHeight = 36.sp, color = CP.Ink, modifier = Modifier.riseIn(arrived, 0))
            Text(if (st.staffApp) "Free, from your desk." else "Skip the queue.", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                lineHeight = 36.sp, color = CP.Muted, modifier = Modifier.riseIn(arrived, 1))
            Spacer(Modifier.size(10.dp))
            Text(if (st.staffApp) "Add all your PDFs, Word files and photos at once, choose pages, sides, paper and copies for each, and press " +
                    "Print. Nothing to pay: it comes from your free pages for the month. Show your files at the counter and take your prints."
                else "Add all your PDFs, Word files and photos at once, choose pages, sides, paper and copies for each, and pay with UPI. " +
                    "Your files stay on your phone: show them at the counter and take your prints.", fontSize = 16.sp,
                color = CP.Muted, lineHeight = 23.sp, modifier = Modifier.riseIn(arrived, 2))
            Spacer(Modifier.size(14.dp))
            (if (st.staffApp) listOf("Many files in one order", "See every page before printing",
                    (st.staff?.monthlyPages?.toString() ?: "Free") + " free pages every month", "Only your staff ID · files deleted after printing")
                else listOf("Many files in one order", "See every page before paying", "Pay only for the pages you pick",
                    "No account · files deleted after printing")).forEach {
                Row(Modifier.padding(vertical = 3.dp).riseIn(arrived, 3), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = CP.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, fontSize = 15.sp, color = CP.Ink2)
                }
            }
            Spacer(Modifier.size(18.dp))
            // choose files
            Column(
                Modifier.fillMaxWidth().riseIn(arrived, 4, distance = 30.dp).clip(RoundedCornerShape(22.dp)).background(CP.Surface)
                    .border(1.5.dp, CP.Line2, RoundedCornerShape(22.dp)).clickable(onClick = onChooseFiles)
                    .padding(vertical = 30.dp, horizontal = 16.dp).testTag("chooseFiles"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // "+": a soft ring keeps growing out from behind it (the student app), so the eye lands here
                Box(Modifier.size(64.dp).pulseBehind(CP.Ink, enabled = arrived).clip(RoundedCornerShape(18.dp)).background(CP.Ink),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.size(14.dp))
                Text("Add files to print", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Choose several at once, or share them to XeoGo" + (if (st.staffApp) " Staff" else ""), fontSize = 14.sp,
                    color = CP.Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.size(14.dp))
                PrimaryButton("Choose files", onChooseFiles)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("PDF", "Word", "JPG", "PNG").forEach { Chip(it) }
                    st.shop?.let { Text("up to ${it.maxFileSizeBytes / 1048576} MB each", fontSize = 13.sp, color = CP.Faint) }
                }
            }
            // The print service is not there yet. Waking up (it sleeps after a quiet time) is said calmly, with
            // something moving in the student app; anything else is an error.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(st.shop == null) {
                slow = false
                if (st.shop == null) {
                    delay(4_000)
                    slow = true
                }
            }
            if (st.shop == null && (st.shopError == OrderSession.WAKING || (st.shopError == null && slow))) {
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CP.WarnSoft).padding(14.dp).testTag("waking"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Waking up the print service", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CP.Ink)
                            if (LocalLively.current) {
                                Spacer(Modifier.width(6.dp))
                                Dots(color = CP.Warn)
                            }
                        }
                        Text("This takes a minute or two after a quiet time. Files you choose now are added as soon as it is up.",
                            fontSize = 13.sp, color = CP.Ink2, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
                        if (LocalLively.current) FlowBar(null, Modifier.padding(top = 10.dp), color = CP.Warn, track = Color.White)
                    }
                }
            } else st.shopError?.let {
                Spacer(Modifier.size(12.dp))
                ErrorLine(it)
            }
            // the staff app: the free pages of the month, instead of prices
            st.staff?.takeIf { st.staffApp }?.let { s ->
                Spacer(Modifier.size(16.dp))
                CardBox {
                    Text("Left this month", fontSize = 13.sp, color = CP.Muted)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(s.leftPages.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            color = if (s.leftPages <= 0) CP.Danger else CP.Ink, modifier = Modifier.testTag("leftBig"))
                        Text("  of ${s.monthlyPages} free pages", fontSize = 14.sp, color = CP.Muted, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text(s.month + " · used ${s.usedPages} · one printed side is one page", fontSize = 13.sp, color = CP.Muted)
                }
                Hint((if (s.colorAllowed) "Colour and special paper are free too." else "Free staff printing is black & white on the usual paper.") +
                    " The pages start again on the 1st of every month.")
            }
            // prices
            st.shop?.takeIf { !st.staffApp }?.let { shop ->
                Spacer(Modifier.size(16.dp))
                Row(Modifier.riseIn(arrived, 5), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardBox(Modifier.weight(1f)) {
                        Text("Black & white", fontSize = 13.sp, color = CP.Muted)
                        Text(rupees(shop.priceBwPaise), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("per printed side", fontSize = 13.sp, color = CP.Muted)
                    }
                    CardBox(Modifier.weight(1f)) {
                        Text("Colour", fontSize = 13.sp, color = CP.Muted)
                        Text(rupees(shop.priceColorPaise), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("per printed side", fontSize = 13.sp, color = CP.Muted)
                    }
                }
                val extra = priceExtras(shop, st)
                if (extra.isNotEmpty()) Hint(extra)
            }
            // orders made on this phone
            if (st.recent.isNotEmpty()) {
                Spacer(Modifier.size(24.dp))
                Text(if (st.staffApp) "Your prints" else "Your orders on this phone", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (st.staffApp) Text("On every device signed in with your staff ID.", fontSize = 13.sp, color = CP.Muted)
                Spacer(Modifier.size(8.dp))
                st.recent.forEach { (order, view) ->
                    CardBox(Modifier.padding(bottom = 8.dp).riseIn(arrived, 6).clickable { session.openStatus(order) }, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Lamp(lampFor(view))
                            Spacer(Modifier.width(10.dp))
                            Text(clockText(java.time.Instant.ofEpochMilli(order.at).toString(), System.currentTimeMillis()),
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CP.Ink2)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(order.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(view?.stage ?: "…", fontSize = 13.sp, color = CP.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(28.dp))
            Text("Designed & developed by Vedant Pravin Surve", fontSize = 12.sp, color = CP.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** Retry link under an error box. */
@Composable
fun RetryLink(onClick: () -> Unit) = TextButton(onClick = onClick) { Text("Try again", color = CP.Ink) }
