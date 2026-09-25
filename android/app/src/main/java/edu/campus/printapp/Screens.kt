package edu.campus.printapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.campus.printapp.net.OrderView
import edu.campus.printapp.net.PaymentStart
import java.util.Locale

// Same look as the website: paper, ink, and copier lamps for status.
private val Paper = Color(0xFFEDEEE9)
private val Ink = Color(0xFF14263B)
private val InkSoft = Color(0xFF5A6B7C)
private val Rule = Color(0xFFD3D6CF)
private val LampReady = Color(0xFF2E7D4F)
private val LampWork = Color(0xFFB3701A)
private val LampStop = Color(0xFFB33A2B)

private val AppColors = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    background = Paper, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    error = LampStop
)

fun rupees(paise: Int?): String = when {
    paise == null -> "–"
    paise % 100 == 0 -> "₹${paise / 100}"
    else -> "₹" + String.format(Locale.US, "%.2f", paise / 100.0)
}

@Composable
fun CampusPrintApp(vm: PrintViewModel, onChooseFile: () -> Unit, onOpenCheckout: (PaymentStart) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val checkout by vm.checkout.collectAsStateWithLifecycle()

    LaunchedEffect(checkout) {
        val start = checkout
        if (start != null) {
            vm.checkoutShown()
            onOpenCheckout(start)
        }
    }
    BackHandler(enabled = s.step != Step.CHOOSE) { vm.back() }

    MaterialTheme(colorScheme = AppColors) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Header(s)
                StepBar(s.step)
                s.error?.let { ErrorBox(it) { vm.dismissError() } }
                Box(Modifier.weight(1f)) {
                    when (s.step) {
                        Step.CHOOSE -> ChooseScreen(s, onChooseFile, { vm.openStatus(it) }, { vm.loadShop() })
                        Step.SETUP -> SetupScreen(s, { vm.setColor(it) }, { vm.changeCopies(it) },
                            { vm.setPagesMode(it) }, { vm.setPagesText(it) },
                            { vm.continueToPayment() }, { vm.backToStart() })
                        Step.PAY -> PayScreen(s, { vm.pay() }, { vm.cancelOrder() })
                        Step.STATUS -> StatusScreen(s) { vm.backToStart() }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Lamp(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun Header(s: UiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Campus Print", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
            Text(s.shop?.centerName ?: "Xerox Center", fontSize = 13.sp, color = InkSoft)
        }
        val shop = s.shop
        val online = shop != null && (shop.bwOnline || shop.colorOnline)
        Lamp(if (shop == null) Rule else if (online) LampReady else LampStop)
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                shop == null -> "..."
                online -> "Printers online"
                else -> "Printers offline"
            },
            fontSize = 13.sp, color = InkSoft
        )
    }
}

@Composable
private fun StepBar(step: Step) {
    val labels = listOf("Choose", "Check", "Pay", "Collect")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == step.ordinal
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) Ink else Rule))
                Text("${i + 1}. $label", fontSize = 12.sp, color = if (on) Ink else InkSoft,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ErrorBox(message: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color(0xFFFBF0EE)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, color = LampStop, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("OK") }
    }
}

@Composable
private fun SheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ---------------------------------------------------------------- 1. choose

@Composable
private fun ChooseScreen(
    s: UiState,
    onChooseFile: () -> Unit,
    onOpenOrder: (SavedOrder) -> Unit,
    onRetry: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Print from your phone.", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 32.sp)
        Text("Skip the queue.", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = InkSoft, lineHeight = 32.sp)
        Spacer(Modifier.height(6.dp))
        Text("Choose a file and the pages, pay with UPI, and collect with a pickup code.", color = InkSoft)
        Spacer(Modifier.height(16.dp))

        if (s.busy) {
            SheetCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Opening your file...")
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color.White)
                    .border(2.dp, Color(0xFFB9BEB5), RoundedCornerShape(4.dp))
                    .clickable(onClick = onChooseFile).padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Choose a file to print", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("PDF, PNG or JPG", color = InkSoft)
                }
            }
        }

        s.shopError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = LampStop)
            TextButton(onClick = onRetry) { Text("Try again") }
        }

        s.shop?.let { shop ->
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetCard(Modifier.weight(1f)) {
                    Text("Black & white", fontSize = 13.sp, color = InkSoft)
                    Text(rupees(shop.priceBwPaise), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("per page", fontSize = 13.sp, color = InkSoft)
                }
                SheetCard(Modifier.weight(1f)) {
                    Text("Colour", fontSize = 13.sp, color = InkSoft)
                    Text(rupees(shop.priceColorPaise), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("per page", fontSize = 13.sp, color = InkSoft)
                }
            }
        }

        if (s.recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Your orders on this phone", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            s.recent.forEach { (order, view) ->
                SheetCard(Modifier.padding(bottom = 8.dp).clickable { onOpenOrder(order) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Lamp(lampColor(view))
                        Spacer(Modifier.width(10.dp))
                        Text(order.code, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(order.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(view?.stage ?: "...", fontSize = 13.sp, color = InkSoft)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Designed & developed by Vedant Pravin Surve", fontSize = 12.sp, color = InkSoft,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun lampColor(v: OrderView?): Color = when (v?.status) {
    null -> Rule
    "COMPLETED" -> LampReady
    "FAILED", "CANCELLED", "EXPIRED" -> LampStop
    else -> LampWork
}

// ---------------------------------------------------------------- 2. check + options

@Composable
private fun SetupScreen(
    s: UiState,
    onColor: (Boolean) -> Unit,
    onCopies: (Int) -> Unit,
    onPagesMode: (Boolean) -> Unit,
    onPagesText: (String) -> Unit,
    onContinue: () -> Unit,
    onChangeFile: () -> Unit
) {
    val shop = s.shop ?: return
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The file itself, as it will print (grey when black & white is chosen).
        LazyRow(
            Modifier.fillMaxWidth().height(340.dp).background(Color(0xFFDADDD5)),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(s.previews) { index, bitmap ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = image,
                        contentDescription = "Page ${s.previewPages.getOrNull(index) ?: (index + 1)}",
                        colorFilter = if (s.color) null else grey,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).background(Color.White)
                    )
                    if (s.fileType == "PDF") {
                        Text("Page ${s.previewPages.getOrNull(index) ?: (index + 1)}", fontSize = 12.sp, color = InkSoft)
                    }
                }
            }
        }
        // Until a valid choice is typed, the preview shows the start of the file.
        val shown = s.pageRanges.sumOf { it.last - it.first + 1 }
        val previewNote = when {
            s.fileType != "PDF" -> null
            shown == s.filePages && s.previews.size < shown ->
                "Swipe to see pages. Showing the first ${s.previews.size} of $shown" +
                    (if (!s.chooseSome && s.pageError == null) "; all pages will be printed." else ".")
            shown != s.filePages && s.previews.size < shown ->
                "Swipe to see pages. Showing the first ${s.previews.size} of the $shown pages you chose."
            shown != s.filePages -> "Showing the " + (if (shown == 1) "page" else "$shown pages") + " you chose."
            else -> null
        }
        previewNote?.let {
            Text(it, fontSize = 13.sp, color = InkSoft, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        Column(Modifier.padding(16.dp)) {
            SheetCard {
                Text(s.fileName, fontWeight = FontWeight.SemiBold)
                Text(
                    if (s.fileType == "PDF") "${s.filePages} " + (if (s.filePages == 1) "page" else "pages")
                    else "Picture, printed on one A4 page",
                    fontSize = 13.sp, color = InkSoft
                )
                Spacer(Modifier.height(14.dp))

                if (s.fileType == "PDF" && s.filePages > 1) {
                    Text("Pages")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceTile("All pages", "${s.filePages} pages", selected = !s.chooseSome,
                            enabled = true, modifier = Modifier.weight(1f)) { onPagesMode(false) }
                        ChoiceTile("Choose pages", "e.g. 5, 10-20", selected = s.chooseSome,
                            enabled = true, modifier = Modifier.weight(1f)) { onPagesMode(true) }
                    }
                    if (s.chooseSome) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.pagesText,
                            onValueChange = onPagesText,
                            singleLine = true,
                            placeholder = { Text("e.g. 102  or  333-390  or  1-5, 8") },
                            isError = s.pageError != null && s.pagesText.isNotBlank(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Use the PDF's own page numbers (page 1 = the first page of the file). They can differ " +
                                "from the numbers printed on the pages, so check the preview.",
                            fontSize = 13.sp, color = InkSoft, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    val pagesMessage = s.pageError
                        ?: if (s.chooseSome) "${s.pages} " + (if (s.pages == 1) "page" else "pages") + " selected" +
                            (if (s.pageSpec.isNotEmpty()) ": " + s.pageSpec.replace(",", ", ") else " (every page)")
                        else null
                    pagesMessage?.let {
                        Text(
                            it, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                            color = if (s.pageError != null) LampStop else Ink,
                            fontWeight = if (s.pageError != null) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceTile("Black & white", rupees(shop.priceBwPaise) + " / page", selected = !s.color,
                        enabled = shop.bwAvailable, modifier = Modifier.weight(1f)) { onColor(false) }
                    ChoiceTile("Colour", rupees(shop.priceColorPaise) + " / page", selected = s.color,
                        enabled = shop.colorAvailable, modifier = Modifier.weight(1f)) { onColor(true) }
                }
                if (!shop.colorAvailable) {
                    Text("Colour printing is not available right now.", fontSize = 13.sp, color = InkSoft)
                }

                Spacer(Modifier.height(14.dp))
                Text("Copies")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onCopies(-1) }, enabled = s.copies > 1) { Text("−", fontSize = 22.sp) }
                    Text("${s.copies}", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    OutlinedButton(onClick = { onCopies(1) }, enabled = s.copies < shop.maxCopies) { Text("+", fontSize = 22.sp) }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Rule)
                if (s.canContinue) {
                    Text(
                        "${s.pages} " + (if (s.pages == 1) "page" else "pages") + " × ${s.copies} " +
                            (if (s.copies == 1) "copy" else "copies") + " × ${rupees(s.pricePerPage)}",
                        color = InkSoft
                    )
                    Text(rupees(s.estimatePaise), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Choose the pages to print", color = InkSoft)
                    Text("\u2013", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }

                val online = if (s.color) shop.colorOnline else shop.bwOnline
                if (!online) {
                    Text(
                        "The printers are offline at the moment. You can still order: it prints as soon as they are back.",
                        fontSize = 13.sp, color = LampWork, modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onContinue,
                    enabled = s.canContinue,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp)
                ) { Text(if (s.canContinue) "Continue to payment · ${rupees(s.estimatePaise)}" else "Continue to payment") }
                TextButton(onClick = onChangeFile, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Choose a different file", color = InkSoft)
                }
            }
        }
    }
}

/** "58 pages (333-390 of 1000)", or "12 pages" for a whole file. */
private fun pagesText(v: OrderView): String {
    val n = v.printPages ?: v.pageCount ?: return "? pages"
    val words = "$n " + (if (n == 1) "page" else "pages")
    return if (v.pages.isNullOrEmpty()) words else "$words (${v.pages.replace(",", ", ")} of ${v.pageCount})"
}

@Composable
private fun ChoiceTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .border(if (selected) 2.5.dp else 1.5.dp, if (selected) Ink else Rule, RoundedCornerShape(4.dp))
            .background(Color.White)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val alpha = if (enabled) 1f else 0.4f
        Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = Ink.copy(alpha = alpha))
        Text(subtitle, fontSize = 13.sp, color = InkSoft.copy(alpha = alpha))
    }
}

// ---------------------------------------------------------------- 3. send + pay

@Composable
private fun PayScreen(s: UiState, onPay: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SheetCard {
            val v = s.payView
            if (s.uploading || v == null) {
                Text("Sending your file...", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { s.uploadProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.uploadProgress >= 1f) "Checking the file..." else "${(s.uploadProgress * 100).toInt()}% sent. Please keep the app open.",
                    fontSize = 13.sp, color = InkSoft
                )
            } else {
                Text("Check and pay", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SummaryLine("File", v.fileName)
                SummaryLine("Pages", pagesText(v))
                SummaryLine("Copies", "${v.copies}")
                SummaryLine("Print", if (v.color) "Colour" else "Black & white")
                SummaryLine("Total", rupees(v.amountPaise), big = true)

                val demo = s.shop?.paymentMode == "demo"
                if (demo) {
                    Text("Test mode: no real money is taken.", color = LampWork, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onPay,
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp)
                ) { Text((if (demo) "Pay (test) " else "Pay ") + rupees(v.amountPaise)) }
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Cancel this order", color = InkSoft)
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, big: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = InkSoft, modifier = Modifier.weight(1f))
        Text(
            value, textAlign = TextAlign.End, modifier = Modifier.weight(2f),
            fontSize = if (big) 22.sp else 16.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------- 4. collect

@Composable
private fun StatusScreen(s: UiState, onNew: () -> Unit) {
    val v = s.status
    val code = v?.pickupCode ?: s.current?.code ?: "-----"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SheetCard {
            Text("YOUR PICKUP CODE", fontSize = 13.sp, letterSpacing = 2.sp, color = InkSoft,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(code, fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Lamp(lampColor(v))
                Spacer(Modifier.width(8.dp))
                Text(v?.stage ?: "Checking...", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            val message = when {
                v == null -> null
                v.status == "AWAITING_PAYMENT" -> "Checking your payment with the bank. This can take a minute."
                else -> v.message
            }
            message?.let {
                Text(it, color = InkSoft, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            if (v != null && v.paidAt != null && v.status != "FAILED" && v.status != "CANCELLED") {
                Spacer(Modifier.height(16.dp))
                val printing = v.status in setOf("CLAIMED", "DOWNLOADING", "SUBMITTED", "COMPLETED")
                val done = v.status == "COMPLETED"
                TrackLine("Paid", done = true, now = false)
                TrackLine("Waiting for a printer", done = printing, now = !printing)
                TrackLine("Printing" + (v.printerName?.let { " on $it" } ?: ""), done = done, now = printing && !done)
                TrackLine("Ready at the counter", done = done, now = false)
            }

            if (v != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "${v.fileName} · ${pagesText(v)} × ${v.copies} · " +
                        (if (v.color) "colour" else "B/W") + " · " + rupees(v.amountPaise),
                    fontSize = 13.sp, color = InkSoft
                )
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(4.dp)) { Text("Print another file") }
        }
    }
}

@Composable
private fun TrackLine(label: String, done: Boolean, now: Boolean) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Lamp(if (done) LampReady else if (now) LampWork else Rule)
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (done || now) Ink else InkSoft,
            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
    }
}
