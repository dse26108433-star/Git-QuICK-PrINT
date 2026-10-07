package edu.campus.printapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import edu.campus.printapp.net.DocumentView

/** Every setting of one file, in words, exactly as the server will print it. */
fun settingRows(session: OrderSession, sd: DocumentView): List<Pair<String, String>> {
    val s = sd.settings ?: return emptyList()
    val pic = sd.fileType != "PDF"
    val rows = mutableListOf<Pair<String, String>>()
    rows += "Pages" to (if (pic) "Picture" else if (s.pages != null) "Selected: ${sd.pagesText} · " + plural(sd.printPages ?: 0, "page", "pages")
        else sd.pagesText ?: "All pages")
    rows += "Copies" to ("${s.copies}" + if (s.copies > 1) (if (s.collate) " · collated" else " · uncollated") else "")
    rows += "Colour" to (if (s.color) "Colour" else "Black & white")
    rows += "Sides" to (if (s.duplex == "ONE_SIDED") "One-sided" else "Two-sided, flip on " + (if (s.duplex == "LONG_EDGE") "long" else "short") + " edge")
    rows += "Paper" to (session.paper(s.paperSize).label + (s.mediaType?.let { " · " + session.mediaLabel(it) } ?: ""))
    val layout = mutableListOf<String>()
    if (!pic && s.pagesPerSheet > 1) layout += "${s.pagesPerSheet} pages per sheet"
    layout += if (s.orientation == "AUTO") "auto orientation" else s.orientation.lowercase()
    val scale = when (s.scaling) { "FIT" -> "fit to page"; "FILL" -> "fill page"; "ACTUAL" -> "actual size"; else -> "${s.scalePercent} %" }
    if (pic || s.pagesPerSheet == 1) layout += scale
    if (pic && s.rotation != 0) layout += "turned ${s.rotation}°"
    if (pic && !s.center && (s.scaling == "ACTUAL" || s.scaling == "CUSTOM")) layout += "top-left"
    layout += if (s.marginMm == 0) "borderless" else "${s.marginMm} mm margins"
    rows += "Layout" to layout.joinToString(", ")
    val fin = listOfNotNull(s.staple?.let { session.finishingLabel("STAPLE_$it") }, s.punch?.let { session.finishingLabel("PUNCH_$it") },
        s.bind?.let { session.finishingLabel("BIND_$it") })
    if (fin.isNotEmpty()) rows += "Finishing" to fin.joinToString("; ")
    if (s.quality == "HIGH") rows += "Quality" to "High"
    val sheets = sd.sheets ?: 0
    rows += "Paper used" to plural(sheets, "sheet", "sheets") + if (s.copies > 1) " × ${s.copies} = " + plural(sheets * s.copies, "sheet", "sheets") else ""
    return rows
}

/** "Review and pay": the server's answer, file by file. What is shown here is what prints. */
@Composable
fun ReviewScreen(vm: PrintViewModel, st: SessionState) {
    val session = vm.session
    val v = st.order ?: return
    var askCancel by remember { mutableStateOf(false) }
    val docs = v.live
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally).padding(16.dp)) {
            Text(if (st.staffApp) "Review and print" else "Review and pay", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Checked by the Xerox center. This is exactly what will be printed.", fontSize = 14.sp, color = CP.Muted)
            if (v.editable && !st.paymentStarted) {
                Spacer(Modifier.size(12.dp))
                GhostButton("‹  Change something", { session.edit() }, Modifier.testTag("editBtn"))
            }
            Spacer(Modifier.size(14.dp))
            docs.forEach { sd ->
                val local = st.docs.find { it.id == sd.id }
                CardBox(Modifier.padding(bottom = 10.dp).testTag("rvDoc")) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (local != null) { Thumb(vm, local, 44); Spacer(Modifier.width(12.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text("${sd.position}. ${sd.fileName}", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.size(6.dp))
                            settingRows(session, sd).forEach { (k, value) ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text(k, fontSize = 13.sp, color = CP.Muted, modifier = Modifier.width(84.dp))
                                    Text(value, fontSize = 13.sp, color = CP.Ink, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Text(if (st.staffApp) plural((sd.sides ?: 0) * (sd.settings?.copies ?: 1), "page", "pages") else rupees(sd.amountPaise),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // the total
            val pages = docs.sumOf { (it.printPages ?: 0) * (it.settings?.copies ?: 1) }
            val sheets = docs.sumOf { (it.sheets ?: 0) * (it.settings?.copies ?: 1) }
            val sum = docs.sumOf { it.amountPaise ?: 0 }
            val total = v.amountPaise ?: sum
            val tag = if (v.payment?.provider == "upi") v.payment.tagPaise ?: 0 else 0
            val upiMode = st.shop?.paymentMode == "upi"
            if (st.staffApp) {
                StaffTotal(st, session, docs.size, pages, sheets) { askCancel = true }
            } else CardBox(Modifier.padding(top = 4.dp)) {
                Text("Order total", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                TotalLine("Files", docs.size.toString())
                TotalLine("Pages printed", pages.toString())
                TotalLine("Sheets of paper", sheets.toString())
                if (total - tag > sum) TotalLine("Minimum online payment", rupees(total - tag - sum))
                if (tag > 0) TotalLine("UPI payment tag", "+" + rupees(tag))
                Spacer(Modifier.size(8.dp))
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(rupees(total), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("rvTotal"))
                }
                if (tag > 0) Hint("The few paise tell the bank's message which payment is yours.")
                else if (total > sum) Hint("Online payments start at ₹1.")
                val shop = st.shop
                if (shop?.paymentMode == "demo") {
                    Text("Test mode: no real money is taken.", fontSize = 13.sp, color = CP.Warn,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                            .background(CP.WarnSoft).padding(10.dp))
                }
                if (shop != null && !shop.bwOnline && !shop.colorOnline) {
                    Hint("The printers are offline at the moment. You can still order: it prints as soon as they are back.")
                }
                Spacer(Modifier.size(8.dp))
                PrimaryButton(if (st.paying) "Opening payment…" else (if (shop?.paymentMode == "demo") "Pay (test) " else "Pay ") +
                        rupees(total) + if (upiMode) " with UPI" else "",
                    { session.pay() }, Modifier.fillMaxWidth().testTag("payBtn"), enabled = !st.paying)
                st.payError?.let { ErrorLine(it) }
                Text(if (upiMode) "Pay the Xerox center directly · Google Pay, PhonePe, Paytm, BHIM or any UPI app"
                    else "Secure payment by Razorpay · UPI, cards, wallets", fontSize = 12.5.sp, color = CP.Muted,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { askCancel = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Cancel this order", color = CP.Muted)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
    if (askCancel) {
        AlertDialog(
            onDismissRequest = { askCancel = false },
            title = { Text("Cancel this order?") },
            text = { Text(if (st.staffApp) "Your files will be deleted. Nothing was printed, and no pages were counted."
                else "Your files will be deleted. Nothing has been paid.") },
            confirmButton = { TextButton(onClick = { askCancel = false; session.cancelOrder() }) { Text("Cancel order", color = CP.Danger) } },
            dismissButton = { TextButton(onClick = { askCancel = false }) { Text("Keep it") } }
        )
    }
}

/**
 * The staff app: nothing to pay. The order takes pages from the month's free
 * pages; "Print" is offered when those cover it (the server checks again when
 * it is pressed, in one step with sending it to the printers).
 */
@Composable
private fun StaffTotal(st: SessionState, session: OrderSession, files: Int, printed: Int, sheets: Int, onCancel: () -> Unit) {
    val v = st.order ?: return
    val takes = v.freePages ?: 0
    val s = st.staff
    val left = s?.leftPages
    val over = left != null && takes > left
    CardBox(Modifier.padding(top = 4.dp)) {
        Text("This order", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        TotalLine("Files", files.toString())
        TotalLine("Pages printed", printed.toString())
        TotalLine("Sheets of paper", sheets.toString())
        Spacer(Modifier.size(8.dp))
        Divider()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("From your free pages", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(plural(takes, "page", "pages"), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("rvTotal"))
        }
        if (s != null && left != null) {
            if (over) ErrorLine("You have " + (if (left <= 0) "no free pages" else "only " + plural(left, "free page", "free pages")) +
                " left for ${s.month}. Go back and print fewer pages or copies.", Modifier.testTag("overLimit"))
            else Hint("After this you have ${left - takes} of your ${s.monthlyPages} free pages left for ${s.month}.")
        }
        val shop = st.shop
        if (shop != null && !shop.bwOnline && !shop.colorOnline) {
            Hint("The printers are offline at the moment. You can still send it: it prints as soon as they are back.")
        }
        Spacer(Modifier.size(8.dp))
        PrimaryButton(if (st.paying) "Sending…" else if (over) "Not enough free pages" else "Print now · free",
            { session.pay() }, Modifier.fillMaxWidth().testTag("payBtn"), enabled = !st.paying && !over)
        st.payError?.let { ErrorLine(it) }
        Text("Free staff printing · nothing to pay", fontSize = 12.5.sp, color = CP.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancel this order", color = CP.Muted)
        }
    }
}

@Composable
private fun TotalLine(a: String, b: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a, fontSize = 14.sp, color = CP.Muted)
        Text(b, fontSize = 14.sp, color = CP.Ink)
    }
}
