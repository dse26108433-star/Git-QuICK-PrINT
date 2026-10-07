package edu.campus.printapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.displaySpec
import edu.campus.printapp.core.fileSize
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.core.shortPaper
import edu.campus.printapp.flow.Doc
import edu.campus.printapp.flow.DocStatus
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState

/** The list of files in the order, each with its settings in short, its price, and its upload. */
@Composable
fun WorkspaceScreen(vm: PrintViewModel, st: SessionState, onAddFiles: () -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val session = vm.session
    LazyColumn(modifier.fillMaxSize().testTag("fileList"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Your files", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CP.Ink)
                    Text(if (st.docs.isEmpty()) "Add the files you want to print."
                        else plural(st.docs.size, "file", "files") + " · set up each one, then review your order.",
                        fontSize = 14.sp, color = CP.Muted)
                }
                Spacer(Modifier.width(8.dp))
                GhostButton("+ Add files", onAddFiles, small = true)
            }
        }
        // The student app shows the files on their way with a card of its own (UploadStage); the staff app the plain one.
        if (st.docs.isNotEmpty()) item(key = "progress") { if (LocalLively.current) UploadStage(st, session) else QueueProgress(st, session) }
        items(st.docs, key = { it.local }) { d ->
            // a file that is added, removed or moved slides to its place (the student app)
            FileCard(vm, st, d, selected = wide && st.selected == d.local,
                modifier = if (LocalLively.current) Modifier.animateItem() else Modifier) { vm.openEditor(d.local) }
        }
        item(key = "add") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, CP.Line2, RoundedCornerShape(16.dp)).clickable(onClick = onAddFiles).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = CP.Ink)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Add more files", fontWeight = FontWeight.SemiBold, color = CP.Ink)
                    Text("PDF, Word, JPG or PNG · choose several at once", fontSize = 13.sp, color = CP.Muted)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** "Uploading 3 files · 42 %" with a bar and "Cancel all", or "All 4 files ready". */
@Composable
private fun QueueProgress(st: SessionState, session: OrderSession) {
    val docs = st.docs
    val working = docs.filter { it.busy }
    val bad = docs.count { it.status == DocStatus.ERROR || it.status == DocStatus.CANCELLED }
    CardBox(Modifier.padding(bottom = 10.dp).testTag("queueTop"), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        if (working.isNotEmpty()) {
            var total = 0L
            var done = 0.0
            for (d in docs) {
                if (d.status == DocStatus.ERROR || d.status == DocStatus.CANCELLED) continue
                val size = d.size.coerceAtLeast(1)
                total += size
                done += when (d.status) {
                    DocStatus.UPLOADING -> size * d.progress.toDouble()
                    DocStatus.CHECKING, DocStatus.CONVERTING, DocStatus.READY -> size.toDouble()
                    else -> 0.0
                }
            }
            val pct = if (total > 0) (done / total * 100).toInt() else 0
            // everything is sent; only Word files are still being turned into pages
            val onlyWord = working.all { it.status == DocStatus.CONVERTING }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (onlyWord) "Turning " + plural(working.size, "Word file", "Word files") + " into pages…"
                    else "Uploading " + plural(working.size, "file", "files") + " · $pct %", fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(vertical = if (onlyWord) 12.dp else 0.dp))
                if (!onlyWord) TextButton(onClick = { session.cancelAll() }) { Text("Cancel all", color = CP.Muted) }
            }
            ProgressBar(pct / 100f)
        } else {
            val ready = docs.count { it.status == DocStatus.READY }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ready == docs.size) (if (docs.size == 1) "Your file is ready" else "All ${docs.size} files ready")
                    else "$ready of ${docs.size} files ready", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (bad > 0) Text(plural(bad, "needs", "need") + " attention", fontSize = 13.sp, color = CP.Muted)
            }
            Spacer(Modifier.size(8.dp))
            ProgressBar(ready / docs.size.toFloat().coerceAtLeast(1f), CP.Accent)
        }
    }
}

/** Short words for a file's settings. */
private fun chips(session: OrderSession, d: Doc, n: Normalized): List<String> {
    val s = n.settings
    val out = mutableListOf<String>()
    if (d.type == "PDF") {
        out += if (s.pages != null && s.pages != "") "pp. " + displaySpec(s.pages) + " (${n.plan.printPages})"
        else if (s.pages == "") "No pages" else if (d.pageCount == 1) "1 page" else "All ${d.pageCount} pages"
    }
    if (s.copies > 1) out += "×${s.copies}"
    out += if (s.color) "Colour" else "B/W"
    if (s.duplex != "ONE_SIDED") out += "2-sided"
    out += shortPaper(s.paperSize)
    if (s.pagesPerSheet > 1) out += "${s.pagesPerSheet}/sheet"
    if (s.marginMm == 0) out += "Borderless"
    if (d.type != "PDF" && s.scaling == "FILL") out += "Fill"
    if (d.type != "PDF" && s.rotation != 0) out += "Turned ${s.rotation}°"
    if (s.staple != null) out += "Stapled"
    if (s.punch != null) out += "Punched"
    if (s.bind != null) out += "Bound"
    s.mediaType?.let { out += session.mediaLabel(it) }
    if (s.quality == "HIGH") out += "High quality"
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileCard(vm: PrintViewModel, st: SessionState, d: Doc, selected: Boolean, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val session = vm.session
    val lively = LocalLively.current
    val n = if (d.status == DocStatus.READY || d.busy) session.norm(d) else null
    val problem = if (d.status == DocStatus.ERROR) d.error else n?.error ?: d.serverError
    val border = when { problem != null -> CP.Danger.copy(alpha = .45f); selected -> CP.Ink; else -> CP.Line }
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(if (problem != null) CP.DangerSoft.copy(alpha = .35f) else CP.Surface)
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "File ${st.docs.indexOf(d) + 1}: ${d.name}" }
            .padding(12.dp)
            .testTag("file:" + d.name)
    ) {
        Thumb(vm, d, 64)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = CP.Ink, modifier = Modifier.weight(1f, fill = false))
                // the moment the Xerox center accepted the file: a green tick pops in (the student app)
                if (lively && problem == null) {
                    Spacer(Modifier.width(6.dp))
                    CheckPop(d.status == DocStatus.READY, size = 18.dp)
                }
            }
            val meta = mutableListOf<String>()
            if (d.type == "PDF" && d.pageCount != null) meta += plural(d.pageCount, "page", "pages")
            if (d.word) meta += "Word file" else if (d.type != null && d.type != "PDF") meta += "Picture"
            if (d.size > 0) meta += fileSize(d.size)
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 13.sp, color = CP.Muted)
            Spacer(Modifier.size(6.dp))
            if (n != null && d.status != DocStatus.ERROR) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips(session, d, n).forEach { Chip(it, warn = problem != null) }
                }
            }
            when (d.status) {
                DocStatus.READING -> {
                    StateLine("Reading the file…")
                    if (lively) FlowBar(null, Modifier.padding(top = 5.dp), colors = Glow.flow)
                }
                DocStatus.QUEUED -> { StateLine("Waiting to upload…"); Actions { SmallAction("Cancel") { session.cancelUpload(d.local) } } }
                DocStatus.UPLOADING -> {
                    StateLine("Uploading ${(d.progress * 100).toInt()} %")
                    if (lively) FlowBar(d.progress, Modifier.padding(top = 5.dp), colors = Glow.flow)
                    else ProgressBar(d.progress, modifier = Modifier.padding(top = 4.dp))
                    Actions { SmallAction("Cancel") { session.cancelUpload(d.local) } }
                }
                DocStatus.CHECKING -> {
                    if (lively) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateLine("Checking the file")
                            Spacer(Modifier.width(4.dp))
                            Dots(Modifier.padding(top = 4.dp))
                        }
                        FlowBar(null, Modifier.padding(top = 5.dp), color = CP.Accent)
                    } else {
                        StateLine("Checking the file…")
                    }
                }
                DocStatus.CONVERTING -> {
                    // a Word file: the Xerox center's computer is turning it into pages
                    val line = "Turning it into pages at the Xerox center" +
                        (if (d.ahead > 0) " · " + plural(d.ahead, "file", "files") + " ahead" else "")
                    if (lively) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateLine(line)
                            Spacer(Modifier.width(4.dp))
                            Dots(Modifier.padding(top = 4.dp))
                        }
                        FlowBar(null, Modifier.padding(top = 5.dp), colors = Glow.flow)
                    } else {
                        StateLine("$line…")
                    }
                }
                DocStatus.CANCELLED -> {
                    StateLine("Upload cancelled")
                    Actions { SmallAction("Try again") { session.retry(d.local) }; SmallAction("Remove") { vm.removeDoc(d.local) } }
                }
                DocStatus.ERROR -> {
                    Problem(d.error ?: "This file cannot be printed.")
                    Actions {
                        if (d.canRetry) SmallAction("Try again") { session.retry(d.local) }
                        SmallAction("Remove") { vm.removeDoc(d.local) }
                    }
                }
                DocStatus.READY -> {}
            }
            if (problem != null && d.status != DocStatus.ERROR) Problem(problem)
        }
        Column(horizontalAlignment = Alignment.End) {
            val p = if (n != null && n.error == null) session.price(d, n) else null
            Text(if (p == null || n == null) "" else if (st.staffApp) plural(session.pages(n), "page", "pages") else rupees(p.amount),
                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CP.Ink)
            IconButton(onClick = { vm.removeDoc(d.local) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, "Remove ${d.name}", tint = CP.Faint)
            }
            // the order of the list is the order the files print in
            val i = st.docs.indexOf(d)
            if (st.docs.size > 1) {
                Row {
                    IconButton(onClick = { session.move(d.local, -1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Print ${d.name} earlier", tint = if (i > 0) CP.Muted else CP.Line2)
                    }
                    IconButton(onClick = { session.move(d.local, 1) }, enabled = i < st.docs.size - 1, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Print ${d.name} later", tint = if (i < st.docs.size - 1) CP.Muted else CP.Line2)
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLine(text: String) = Text(text, fontSize = 13.sp, color = CP.Ink2, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Problem(text: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Icon(Icons.Filled.Warning, null, tint = CP.Danger, modifier = Modifier.size(15.dp).padding(top = 2.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, color = CP.Danger, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) = GhostButton(text, onClick, small = true)

/** The first page (or the picture), with a PDF / JPG / PNG badge. */
@Composable
fun Thumb(vm: PrintViewModel, d: Doc, sizeDp: Int) {
    val img by produceState<android.graphics.Bitmap?>(null, d.file, d.type) {
        val f = d.file
        val t = d.type
        // (a Word file has no picture until the Xerox center's computer has turned it into pages)
        value = if (f != null && t != null && t != "DOCX") runCatching { vm.images.thumb(f, t, sizeDp * 3) }.getOrNull() else null
    }
    // until the first page is drawn: a light passes over the empty box (the student app)
    Box(Modifier.size(sizeDp.dp, (sizeDp * 1.25f).dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
        .shimmer(img == null && d.file != null && d.type != null && !d.word)
        .border(1.dp, CP.Line, RoundedCornerShape(8.dp))) {
        img?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        d.type?.let {
            Text(if (it == "JPEG") "JPG" else if (it == "DOCX") "WORD" else it, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).clip(RoundedCornerShape(4.dp))
                    .background(CP.Ink2).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** Total and "Review order", with the reason when it cannot be pressed yet (tap it to open that file). */
@Composable
fun CheckoutBar(vm: PrintViewModel, st: SessionState) {
    val session = vm.session
    val c = session.checkout(st)
    Column(Modifier.fillMaxWidth().background(CP.Surface)) {
        Divider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (st.docs.isEmpty()) "–" else if (st.staffApp) plural(c.pages, "page", "pages") else rupees(c.total),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (c.over) CP.Danger else CP.Ink,
                    modifier = Modifier.testTag("total"))
                if (c.why != null && st.docs.isNotEmpty()) {
                    Text(c.why, fontSize = 12.5.sp, color = CP.Warn, fontWeight = FontWeight.Medium,
                        textDecoration = if (c.needs != null && !c.uploading) TextDecoration.Underline else null,
                        modifier = Modifier.clickable(enabled = c.needs != null && !c.uploading) { c.needs?.let { vm.openEditor(it) } }
                            .testTag("why"))
                } else {
                    Text(if (st.docs.isEmpty()) "" else plural(c.files, "file", "files") + " · " + plural(c.sheets, "sheet", "sheets") +
                        (st.staff?.takeIf { st.staffApp }?.let { " · ${it.leftPages} free left" } ?: "") +
                        (if (c.uploading) " · uploading…" else ""), fontSize = 13.sp, color = CP.Muted)
                }
            }
            PrimaryButton(if (st.reviewing) "Checking…" else "Review order", { session.review() },
                enabled = c.canReview && !st.reviewing, modifier = Modifier.testTag("reviewBtn"))
        }
    }
}
