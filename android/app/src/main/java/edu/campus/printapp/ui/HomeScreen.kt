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

@Composable
fun HomeScreen(st: SessionState, session: OrderSession, onChooseFiles: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(8.dp))
            // an order that is not finished yet
            if (st.docs.isNotEmpty() || st.draft != null) {
                CardBox(Modifier.padding(bottom = 16.dp), border = CP.Ink) {
                    Text("Your order is not finished", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (st.docs.isEmpty()) "Continue where you stopped." else plural(st.docs.size, "file", "files") +
                        " waiting to be set up and paid.", fontSize = 14.sp, color = CP.Muted)
                    Spacer(Modifier.size(10.dp))
                    PrimaryButton("Continue your order", { session.continueOrder() }, Modifier.fillMaxWidth().testTag("continue"))
                }
            }
            Text("Print from your phone.", fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, color = CP.Ink)
            Text("Skip the queue.", fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, color = CP.Muted)
            Spacer(Modifier.size(10.dp))
            Text("Add all your PDFs and photos at once, choose pages, sides, paper and copies for each, pay with UPI, " +
                "and collect everything with one pickup code.", fontSize = 16.sp, color = CP.Muted, lineHeight = 23.sp)
            Spacer(Modifier.size(14.dp))
            listOf("Many files in one order", "See every page before paying", "Pay only for the pages you pick",
                "No account · files deleted after printing").forEach {
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = CP.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, fontSize = 15.sp, color = CP.Ink2)
                }
            }
            Spacer(Modifier.size(18.dp))
            // choose files
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CP.Surface)
                    .border(1.5.dp, CP.Line2, RoundedCornerShape(22.dp)).clickable(onClick = onChooseFiles)
                    .padding(vertical = 30.dp, horizontal = 16.dp).testTag("chooseFiles"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(CP.Ink), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.size(14.dp))
                Text("Add files to print", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Choose several at once, or share them to Campus Print", fontSize = 14.sp, color = CP.Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.size(14.dp))
                PrimaryButton("Choose files", onChooseFiles)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("PDF", "JPG", "PNG").forEach { Chip(it) }
                    st.shop?.let { Text("up to ${it.maxFileSizeBytes / 1048576} MB each", fontSize = 13.sp, color = CP.Faint) }
                }
            }
            st.shopError?.let {
                Spacer(Modifier.size(12.dp))
                ErrorLine(it)
            }
            // prices
            st.shop?.let { shop ->
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text("Your orders on this phone", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                st.recent.forEach { (order, view) ->
                    CardBox(Modifier.padding(bottom = 8.dp).clickable { session.openStatus(order) }, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Lamp(lampFor(view))
                            Spacer(Modifier.width(10.dp))
                            Text(order.code, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 16.sp)
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
