package edu.campus.printapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.core.DEFAULT_MARGIN
import edu.campus.printapp.core.Dim
import edu.campus.printapp.core.FINISHING_GROUPS
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.PAGES_PER_SHEET
import edu.campus.printapp.core.PrintSettings
import edu.campus.printapp.core.choices
import edu.campus.printapp.core.conflictWords
import edu.campus.printapp.core.dims
import edu.campus.printapp.core.displaySpec
import edu.campus.printapp.core.finishingPositions
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.core.shortPaper
import edu.campus.printapp.flow.Doc
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import kotlinx.coroutines.delay

private fun isPageError(e: String?) = e != null && Regex("page|number|Pages start|Write the pages|Up to", RegexOption.IGNORE_CASE).containsMatchIn(e)

/** Everything the student can set for one file. Only what the connected printers can do is offered. */
@Composable
fun SettingsPanel(
    d: Doc,
    n: Normalized,
    st: SessionState,
    session: OrderSession,
    onPickPages: () -> Unit
) {
    val s = n.settings
    val pic = d.type != "PDF"
    val off = st.offered
    Column(Modifier.fillMaxWidth()) {
        // what cannot be printed as chosen, and the nearest thing that can (one tap)
        val problem = n.error ?: d.serverError
        if (problem != null && !(n.error != null && isPageError(n.error) && !pic)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("problem")) {
                ErrorLine(problem)
                session.quickFixFor(d)?.let { fix ->
                    Spacer(Modifier.size(10.dp))
                    GhostButton("✓  Change to " + fix.changes.joinToString(" and "), { session.applyQuickFix(d.local) }, small = true)
                }
            }
            Divider()
        }
        if (!pic && (d.pageCount ?: 0) > 1) PagesField(d, n, session, onPickPages)
        CopiesField(d, n, session)
        ColorField(d, s, st, session)
        if (off.duplex || s.duplex != "ONE_SIDED") SidesField(d, n, st, session)
        PaperField(d, s, st, session)
        if (pic) PictureGroup(d, n, st, session) else LayoutGroup(d, n, st, session)
        if (off.finishing.isNotEmpty() || s.staple != null || s.punch != null || s.bind != null) FinishingGroup(d, n, st, session)
        if (off.mediaTypes.isNotEmpty() || off.highQuality || s.mediaType != null || s.quality == "HIGH") PaperTypeGroup(d, s, st, session)
        PriceBlock(d, n, st, session)
    }
}

// ------------------------------------------------------------------ pages

@Composable
private fun PagesField(d: Doc, n: Normalized, session: OrderSession, onPickPages: () -> Unit) {
    val s = n.settings
    val shown = if (d.settings?.pages == "") "" else if (isPageError(n.error)) d.settings?.pages ?: "" else displaySpec(s.pages)
    var text by remember(d.local) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(false) }
    // pages chosen on the page pictures show up here (unless the student is typing)
    LaunchedEffect(shown) { if (!focused) text = shown }
    LaunchedEffect(text, typed) {
        if (!typed) return@LaunchedEffect
        delay(250)
        session.setPagesText(d.local, text)
        typed = false
    }
    val focus = LocalFocusManager.current
    Field("Pages to print") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(200); typed = true },
                singleLine = true,
                placeholder = { Text(if (d.settings?.pages == "") "No pages chosen yet" else "All ${d.pageCount} pages", color = CP.Faint) },
                isError = isPageError(n.error) && text.isNotBlank(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = CP.Line2, focusedBorderColor = CP.Ink),
                modifier = Modifier.weight(1f).testTag("pagesText").onFocusChanged { f ->
                    if (focused && !f.isFocused && !isPageError(n.error)) text = displaySpec(n.settings.pages)
                    focused = f.isFocused
                }
            )
            Spacer(Modifier.width(8.dp))
            GhostButton("Pick on pages", onPickPages, small = true)
        }
        // "Selected: 3, 7, 10–12 · Total pages to print: 5"
        val pageError = isPageError(n.error)
        if (!(pageError && d.settings?.pages != "")) {
            Text(
                buildAnnotatedString {
                    append("Selected: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(if (s.pages != null && s.pages != "") displaySpec(s.pages) else if (d.settings?.pages == "") "none" else "all ${d.pageCount} pages")
                    }
                    append(" · Total pages to print: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.plan.printPages.toString()) }
                },
                fontSize = 14.sp, color = CP.Ink,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(CP.Sunk).padding(horizontal = 12.dp, vertical = 10.dp).testTag("pagesSummary")
            )
        }
        if (pageError) ErrorLine(n.error!!)
        Hint("Type pages like 3, 7, 10-12 (the PDF's own page numbers), or pick them on the page pictures.")
    }
}

// ------------------------------------------------------------------ copies, colour, sides, paper

@Composable
private fun CopiesField(d: Doc, n: Normalized, session: OrderSession) {
    val s = n.settings
    val max = session.state.value.shop?.maxCopies ?: 50
    Field("Copies") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Seg(listOf(1, 2, 3).map { v -> SegItem("$v", on = s.copies == v) { session.change(d.local) { it.copy(copies = v) } } }, label = "Copies")
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("−", { session.change(d.local) { it.copy(copies = (s.copies - 1).coerceAtLeast(1)) } }, small = true, enabled = s.copies > 1)
            Spacer(Modifier.width(4.dp))
            NumberBox(s.copies, 1, max, "Number of copies", { v -> session.change(d.local) { it.copy(copies = v) } }, width = 60.dp)
            Spacer(Modifier.width(4.dp))
            GhostButton("+", { session.change(d.local) { it.copy(copies = (s.copies + 1).coerceAtMost(max)) } }, small = true, enabled = s.copies < max)
        }
        if (s.copies > 1 && n.plan.sheets > 1 && s.staple == null && s.bind == null) {
            Spacer(Modifier.size(10.dp))
            Seg(listOf(
                SegItem("Collated", "1-2-3, 1-2-3", s.collate) { session.change(d.local) { it.copy(collate = true) } },
                SegItem("Uncollated", "1-1, 2-2, 3-3", !s.collate) { session.change(d.local) { it.copy(collate = false) } }
            ), label = "Copy order")
        }
    }
}

@Composable
private fun ColorField(d: Doc, s: PrintSettings, st: SessionState, session: OrderSession) {
    val shop = st.shop ?: return
    val cs = choices(Dim.COLOR, listOf(false, true), s, st.printers)
    Field("Colour") {
        if (cs.size == 1) {
            val c = cs[0].value == true
            Hint(if (c) "Colour printing only (${rupees(shop.priceColorPaise)} per side)."
                else "Black & white only (${rupees(shop.priceBwPaise)} per side). Colour printing is not available.")
        } else {
            Seg(cs.map { a ->
                val colour = a.value == true
                SegItem(if (colour) "Colour" else "Black & white",
                    conflictWords(a) ?: (rupees(if (colour) shop.priceColorPaise else shop.priceBwPaise) + " / side"),
                    s.color == colour, conflict = !a.available) { session.pick(d.local, a) { it.copy(color = colour) } }
            }, label = "Colour")
        }
    }
}

@Composable
private fun SidesField(d: Doc, n: Normalized, st: SessionState, session: OrderSession) {
    val s = n.settings
    val cs = choices(Dim.DUPLEX, listOf("ONE_SIDED", "LONG_EDGE", "SHORT_EDGE"), s, st.printers)
    val words = mapOf("ONE_SIDED" to ("One-sided" to ""), "LONG_EDGE" to ("Two-sided" to "flip on long edge"),
        "SHORT_EDGE" to ("Two-sided" to "flip on short edge"))
    Field("Sides") {
        // the two two-sided choices keep their edge on show, so they can be told apart even when they need a change
        Seg(cs.map { a ->
            val (label, edge) = words[a.value] ?: ((a.value as String) to "")
            SegItem(label, edge.ifEmpty { null } ?: conflictWords(a), s.duplex == a.value, conflict = !a.available,
                note = if (edge.isNotEmpty()) conflictWords(a) else null) { session.pick(d.local, a) { it.copy(duplex = a.value as String) } }
        }, label = "Sides")
        if (s.duplex != "ONE_SIDED") {
            Hint((if (s.duplex == "LONG_EDGE") "Pages turn like a book. " else "Pages flip up like a notepad. ") +
                "Uses ${plural(n.plan.sheets, "sheet", "sheets")} instead of ${n.plan.sides} per copy.")
        } else if (n.plan.sides > 1 && cs.any { it.value != "ONE_SIDED" && it.available }) {
            Hint("Two-sided would use ${plural((n.plan.sides + 1) / 2, "sheet", "sheets")} instead of ${n.plan.sides}.")
        }
    }
}

@Composable
private fun PaperField(d: Doc, s: PrintSettings, st: SessionState, session: OrderSession) {
    val sizes = st.shop?.printing?.paperSizes?.map { it.id } ?: listOf("A4")
    val cs = choices(Dim.PAPER_SIZE, sizes, s, st.printers)
    Field("Paper size") {
        if (cs.size == 1) {
            val p = session.paper(cs[0].value as String)
            Hint(p.label + " (" + dims(p) + ")")
        } else {
            Seg(cs.map { a ->
                val p = session.paper(a.value as String)
                SegItem(shortPaper(p.id), conflictWords(a) ?: dims(p), s.paperSize == a.value, conflict = !a.available) {
                    session.pick(d.local, a) { it.copy(paperSize = p.id) }
                }
            }, perRow = if (cs.size > 3) 3 else cs.size, label = "Paper size")
        }
        val pct = st.shop?.printing?.pricing?.paperSizePercent?.get(s.paperSize)
        if (pct != null && pct != 100) Hint(s.paperSize + " costs " + (if (pct == 200) "double" else "$pct %") + " per side.")
    }
}

// ------------------------------------------------------------------ layout (PDF) and picture

private val MARGINS = listOf(Triple(5, "Narrow", "5 mm"), Triple(10, "Normal", "10 mm"), Triple(20, "Wide", "20 mm"))

@Composable
private fun MarginsField(d: Doc, s: PrintSettings, st: SessionState, session: OrderSession) {
    var bl = choices(Dim.BORDERLESS, listOf(true), s, st.printers).firstOrNull()
    if (bl == null && s.marginMm == 0) bl = edu.campus.printapp.core.Choice(true, false, false, null, gone = true)
    val preset = MARGINS.any { it.first == s.marginMm } || s.marginMm == 0
    val items = mutableListOf<SegItem>()
    bl?.let { b ->
        items += SegItem("None", conflictWords(b) ?: "borderless", s.marginMm == 0, conflict = !b.available || b.gone) {
            session.pick(d.local, b) { it.copy(marginMm = 0) }
        }
    }
    MARGINS.forEach { (mm, label, sub) -> items += SegItem(label, sub, s.marginMm == mm) { session.change(d.local) { it.copy(marginMm = mm) } } }
    items += SegItem("Custom", if (preset) "mm" else "${s.marginMm} mm", !preset) { session.change(d.local) { it.copy(marginMm = 15) } }
    Field("Margins") {
        Seg(items, perRow = 3, label = "Margins")
        if (!preset) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberBox(s.marginMm, 1, 40, "Margin in millimetres", { v -> session.change(d.local) { it.copy(marginMm = v) } })
                Spacer(Modifier.width(8.dp))
                Text("mm on every side", fontSize = 13.sp, color = CP.Muted)
            }
        }
        if (s.marginMm == 0) Hint("Printed right to the edge of the paper.")
        else if (s.marginMm < DEFAULT_MARGIN) Hint("Most printers cannot print the outer 3–5 mm of the paper.")
    }
}

@Composable
private fun OrientationField(d: Doc, s: PrintSettings, session: OrderSession) {
    val auto = if (d.type == "PDF") (if (s.pagesPerSheet > 1) "best fit" else "follows each page") else "follows the picture"
    Field("Orientation") {
        Seg(listOf(Triple("AUTO", "Auto", auto), Triple("PORTRAIT", "Portrait", null), Triple("LANDSCAPE", "Landscape", null)).map { (v, label, sub) ->
            SegItem(label, sub, s.orientation == v) { session.change(d.local) { it.copy(orientation = v) } }
        }, label = "Orientation")
    }
}

@Composable
private fun ScalingField(d: Doc, s: PrintSettings, pic: Boolean, session: OrderSession) {
    val opts = if (pic) listOf(Triple("FIT", "Fit", "whole picture"), Triple("FILL", "Fill", "trim edges"),
        Triple("ACTUAL", "Actual", "100 %"), Triple("CUSTOM", "Custom", "%"))
    else listOf(Triple("FIT", "Fit to page", null), Triple("ACTUAL", "Actual size", "100 %"), Triple("CUSTOM", "Custom", "%"))
    Field(if (pic) "Size on the paper" else "Scale") {
        Seg(opts.map { (v, label, sub) ->
            SegItem(label, if (v == "CUSTOM" && s.scaling == "CUSTOM") "${s.scalePercent} %" else sub, s.scaling == v) {
                session.change(d.local) {
                    val t = it.copy(scaling = v)
                    if (v == "CUSTOM" && !(t.scalePercent > 0 && t.scalePercent != 100)) t.copy(scalePercent = 90) else t
                }
            }
        }, perRow = if (pic) 2 else 3, label = "Scale")
        if (s.scaling == "CUSTOM") {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberBox(s.scalePercent, 10, 400, "Scale in percent", { v -> session.change(d.local) { it.copy(scalePercent = v) } })
                Spacer(Modifier.width(8.dp))
                Text("% of actual size (10–400)", fontSize = 13.sp, color = CP.Muted)
            }
        }
        if (pic) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = CP.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Proportions are always kept: never stretched.", fontSize = 13.sp, color = CP.Accent, fontWeight = FontWeight.Medium)
            }
            if (s.scaling == "ACTUAL") d.imageInfo?.let { Hint("Actual size uses the picture's own resolution (${it.dpi.toInt()} dpi).") }
        } else if (s.scaling == "ACTUAL") {
            Hint("Pages print at their real size; anything outside the paper is cut off.")
        }
    }
}

@Composable
private fun LayoutGroup(d: Doc, n: Normalized, st: SessionState, session: OrderSession) {
    val s = n.settings
    val parts = mutableListOf<String>()
    if (s.pagesPerSheet > 1) parts += "${s.pagesPerSheet} per sheet"
    parts += when (s.orientation) { "AUTO" -> "Auto"; "PORTRAIT" -> "Portrait"; else -> "Landscape" }
    if (s.pagesPerSheet == 1) parts += when (s.scaling) { "FIT" -> "Fit"; "ACTUAL" -> "Actual size"; else -> "${s.scalePercent} %" }
    parts += if (s.marginMm == 0) "borderless" else "${s.marginMm} mm"
    Group("Layout", parts.joinToString(" · "), startOpen = false) {
        if (n.plan.printPages > 1) {
            Field("Pages per sheet") {
                Seg(PAGES_PER_SHEET.map { v -> SegItem("$v", on = s.pagesPerSheet == v) { session.change(d.local) { it.copy(pagesPerSheet = v) } } },
                    label = "Pages per sheet")
                if (s.pagesPerSheet > 1) {
                    Hint(plural(n.plan.printPages, "page fits", "pages fit") + " on " + plural(n.plan.sides, "side", "sides") +
                        ". Pages are shrunk to fit their space.")
                }
            }
        }
        OrientationField(d, s, session)
        if (s.pagesPerSheet == 1) ScalingField(d, s, false, session)
        MarginsField(d, s, st, session)
    }
}

@Composable
private fun PictureGroup(d: Doc, n: Normalized, st: SessionState, session: OrderSession) {
    val s = n.settings
    val turn = s.rotation
    val parts = mutableListOf(when (s.scaling) { "FIT" -> "Fit"; "FILL" -> "Fill"; "ACTUAL" -> "Actual size"; else -> "${s.scalePercent} %" })
    if (turn != 0) parts += "turned $turn°"
    parts += if (s.marginMm == 0) "borderless" else "${s.marginMm} mm"
    Group("Picture", parts.joinToString(" · "), startOpen = true) {
        ScalingField(d, s, true, session)
        Field("Turn", if (turn != 0) "turned $turn°" else "upright") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("↺  Turn left", { session.change(d.local) { it.copy(rotation = ((turn - 90) % 360 + 360) % 360) } }, Modifier.weight(1f))
                GhostButton("Turn right  ↻", { session.change(d.local) { it.copy(rotation = (turn + 90) % 360) } }, Modifier.weight(1f))
            }
        }
        OrientationField(d, s, session)
        if (s.scaling == "ACTUAL" || s.scaling == "CUSTOM") {
            Field("Position") {
                Seg(listOf(
                    SegItem("Centred", on = s.center) { session.change(d.local) { it.copy(center = true) } },
                    SegItem("Top-left", on = !s.center) { session.change(d.local) { it.copy(center = false) } }
                ), label = "Position")
            }
        }
        MarginsField(d, s, st, session)
    }
}

// ------------------------------------------------------------------ finishing, paper type, quality

@Composable
private fun FinishingGroup(d: Doc, n: Normalized, st: SessionState, session: OrderSession) {
    val s = n.settings
    val parts = listOfNotNull(s.staple?.let { "stapled" }, s.punch?.let { "punched" }, s.bind?.let { "bound" })
    Group("Finishing", if (parts.isEmpty()) "none" else parts.joinToString(", "), startOpen = parts.isNotEmpty()) {
        for (grp in FINISHING_GROUPS) {
            val (title, dim, cur) = when (grp) {
                "STAPLE" -> Triple("Staple", Dim.STAPLE, s.staple)
                "PUNCH" -> Triple("Hole punch", Dim.PUNCH, s.punch)
                else -> Triple("Binding", Dim.BIND, s.bind)
            }
            val positions = finishingPositions(st.offered.finishing, grp).toMutableList()
            if (cur != null && cur !in positions) positions += cur
            if (positions.isEmpty()) continue
            val cs = choices(dim, listOf<Any?>(null) + positions, s, st.printers)
            Field(title) {
                Seg(cs.map { a ->
                    val v = a.value as String?
                    val label = if (v == null) "None" else session.finishingLabel(grp + "_" + v).substringAfter(':').trim()
                        .replaceFirstChar { it.uppercase() }
                    SegItem(label, conflictWords(a), cur == v, conflict = !a.available) { session.pick(d.local, a) { dim.write(it, v) } }
                }, perRow = 3, label = title)
                if ((grp == "STAPLE" || grp == "BIND") && n.plan.sheets < 2) Hint("Needs at least 2 sheets of paper.")
                val fp = st.shop?.printing?.pricing?.finishingPaise?.get(grp) ?: 0
                if (fp > 0 && cur != null) Hint(rupees(fp) + " per copy.")
            }
        }
    }
}

@Composable
private fun PaperTypeGroup(d: Doc, s: PrintSettings, st: SessionState, session: OrderSession) {
    val parts = mutableListOf(s.mediaType?.let { session.mediaLabel(it) } ?: "usual paper")
    if (s.quality == "HIGH") parts += "high quality"
    Group("Paper type & quality", parts.joinToString(" · "), startOpen = s.mediaType != null || s.quality == "HIGH") {
        if (st.offered.mediaTypes.isNotEmpty() || s.mediaType != null) {
            val cs = choices(Dim.MEDIA_TYPE, listOf<Any?>(null) + st.offered.mediaTypes, s, st.printers)
            Field("Paper type") {
                Seg(cs.map { a ->
                    val v = a.value as String?
                    SegItem(if (v == null) "Usual paper" else session.mediaLabel(v), conflictWords(a), s.mediaType == v, conflict = !a.available) {
                        session.pick(d.local, a) { it.copy(mediaType = v) }
                    }
                }, perRow = 2, label = "Paper type")
                val pct = s.mediaType?.let { st.shop?.printing?.pricing?.mediaTypePercent?.get(it) }
                if (pct != null && pct != 100) Hint("This paper costs $pct % of the normal price per side.")
            }
        }
        if (st.offered.highQuality || s.quality == "HIGH") {
            val cs = choices(Dim.QUALITY, listOf("STANDARD", "HIGH"), s, st.printers)
            if (cs.size > 1) {
                Field("Print quality") {
                    Seg(cs.map { a ->
                        SegItem(if (a.value == "HIGH") "High" else "Standard", conflictWords(a), s.quality == a.value, conflict = !a.available) {
                            session.pick(d.local, a) { it.copy(quality = a.value as String) }
                        }
                    }, label = "Print quality")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ price of this file

@Composable
private fun PriceBlock(d: Doc, n: Normalized, st: SessionState, session: OrderSession) {
    val p = if (n.error == null) session.price(d, n) else null
    val s = n.settings
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CP.Sunk).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("This file", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CP.Ink2)
                Text(
                    if (p != null) (if (d.type == "PDF") plural(n.plan.printPages, "page", "pages") + " → " else "") +
                        plural(n.plan.sheets, "sheet", "sheets") + (if (s.copies > 1) " × ${s.copies} copies" else "") +
                        " · " + rupees(p.perSide) + " per side" + (if (p.finishing > 0) " + " + rupees(p.finishing) + " finishing" else "")
                    else "Fix the choice above to see the price.",
                    fontSize = 12.5.sp, color = CP.Muted
                )
            }
            Text(if (p != null) rupees(p.amount) else "–", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CP.Ink)
        }
        if (st.docs.any { it.local != d.local && it.settings != null }) {
            Spacer(Modifier.size(10.dp))
            GhostButton("Use these settings for all files", { session.applyToAll(d.local) }, Modifier.fillMaxWidth(), small = true)
        }
    }
}
