package edu.campus.printapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.PrintViewModel
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.fileSize
import edu.campus.printapp.core.pagesFromSpec
import edu.campus.printapp.core.plural
import edu.campus.printapp.core.rupees
import edu.campus.printapp.flow.Doc
import edu.campus.printapp.flow.DocStatus
import edu.campus.printapp.flow.SessionState
import java.io.File

/**
 * One file: its print preview (or its pages to choose from) and every
 * setting. Full screen on a phone; the right-hand side on a tablet.
 */
@Composable
fun EditorScreen(vm: PrintViewModel, st: SessionState, d: Doc, fullScreen: Boolean) {
    val session = vm.session
    val ui by vm.ui.collectAsStateWithLifecycle()
    val n = session.norm(d)
    val index = st.docs.indexOfFirst { it.local == d.local }
    val pagesTab = ui.pagesTab && d.type == "PDF" && (d.pageCount ?: 0) > 1
    val list = rememberLazyListState()
    // another file: start at its top (its preview); a problem to fix: bring it into view
    LaunchedEffect(d.local) { list.scrollToItem(0) }
    LaunchedEffect(d.local, d.serverError) { if (d.serverError != null) list.animateScrollToItem(if (pagesTab) 0 else 1) }

    Column(Modifier.fillMaxSize().background(CP.Surface)) {
        // ---- top: which file
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (fullScreen) IconButton(onClick = { vm.closeEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to your files") }
            else Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = CP.Ink)
                val meta = mutableListOf(when (d.type) {
                    "JPEG" -> "JPG picture"; "PNG" -> "PNG picture"; "PDF" -> "PDF"; "DOCX" -> "Word file"; else -> "File"
                })
                if (d.type == "PDF" && d.pageCount != null) meta += plural(d.pageCount, "page", "pages")
                if (d.size > 0) meta += fileSize(d.size)
                Text(meta.joinToString(" · "), fontSize = 13.sp, color = CP.Muted, maxLines = 1)
            }
            if (st.docs.size > 1) {
                IconButton(onClick = { st.docs.getOrNull(index - 1)?.let { vm.openEditor(it.local) } }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous file")
                }
                Text("${index + 1} / ${st.docs.size}", fontSize = 14.sp, color = CP.Muted)
                IconButton(onClick = { st.docs.getOrNull(index + 1)?.let { vm.openEditor(it.local) } }, enabled = index < st.docs.size - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next file")
                }
            }
        }
        Divider()
        // ---- the preview or the pages, then the settings (one scrolling list: fast for 2000 pages)
        val screenH = LocalConfiguration.current.screenHeightDp
        val previewH = (screenH * if (fullScreen) 0.46f else 0.56f).coerceIn(260f, 720f).dp
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cols = (maxWidth / 112.dp).toInt().coerceIn(3, 8)
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("editorList")) {
                item(key = "tabs") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        if (d.type == "PDF" && (d.pageCount ?: 0) > 1) {
                            Seg(listOf(
                                SegItem("Print preview", on = !pagesTab) { vm.showPagesTab(false) },
                                SegItem("Choose pages", on = pagesTab) { vm.showPagesTab(true) }
                            ), label = "Preview or pages")
                            Spacer(Modifier.size(12.dp))
                        }
                        if (!pagesTab) {
                            SheetPreview(d, n, session.paper(n?.settings?.paperSize ?: "A4"), vm.images,
                                ui.sheet[d.local] ?: 0, { vm.setSheet(d.local, it) }, previewH)
                        }
                    }
                }
                if (pagesTab && n != null && d.file != null) pagesGrid(vm, d, n, d.file, cols)
                item(key = "settings") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        when {
                            d.status == DocStatus.ERROR -> FileProblem(vm, d)
                            n == null -> Hint("Reading the file… the settings appear in a moment.", Modifier.padding(vertical = 16.dp))
                            else -> SettingsPanel(d, n, st, session) { vm.showPagesTab(true) }
                        }
                        Spacer(Modifier.size(24.dp))
                    }
                }
            }
        }
        // ---- bottom: this file's price
        if (fullScreen) {
            Divider()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val p = if (n != null && n.error == null) session.price(d, n) else null
                val problem = n != null && (n.error != null || d.serverError != null)
                Column(Modifier.weight(1f)) {
                    Text(if (p == null || n == null) "–" else if (st.staffApp) plural(session.pages(n), "page", "pages") else rupees(p.amount),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CP.Ink, modifier = Modifier.testTag("editorPrice"))
                    Text(if (problem) "needs a change" else if (n != null) plural(n.plan.sheets * n.settings.copies, "sheet", "sheets") else "",
                        fontSize = 13.sp, color = if (problem) CP.Warn else CP.Muted)
                }
                PrimaryButton("Done", { vm.closeEditor() }, Modifier.width(140.dp))
            }
        }
    }
}

@Composable
private fun FileProblem(vm: PrintViewModel, d: Doc) {
    Column(Modifier.padding(vertical = 16.dp)) {
        FieldHead("This file cannot be printed")
        ErrorLine(d.error ?: "This file cannot be printed.")
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.canRetry) GhostButton("Try again", { vm.session.retry(d.local) }, small = true)
            GhostButton("Remove it", { vm.removeDoc(d.local) }, small = true)
        }
    }
}

// ------------------------------------------------------------------ choosing pages on page pictures

private fun androidx.compose.foundation.lazy.LazyListScope.pagesGrid(vm: PrintViewModel, d: Doc, n: Normalized, file: File, cols: Int) {
    val total = d.pageCount ?: 0
    val chosen = runCatching { pagesFromSpec(d.settings?.pages, total) }.getOrDefault(emptySet())
    item(key = "pagesBar") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                when {
                    chosen.size == total -> "All $total pages · tap to leave pages out"
                    chosen.isEmpty() -> "No pages chosen · tap the pages you want"
                    else -> "${chosen.size} of $total pages · tap to add or leave out"
                }, fontSize = 14.sp, color = CP.Ink2, modifier = Modifier.padding(bottom = 8.dp).testTag("pagesBar")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("All", "None", "Odd", "Even", "Invert").forEach { q ->
                    GhostButton(q, {
                        val set = (1..total).filter { p ->
                            when (q) { "All" -> true; "Odd" -> p % 2 == 1; "Even" -> p % 2 == 0; "Invert" -> p !in chosen; else -> false }
                        }.toSet()
                        vm.session.setPages(d.local, set)
                    }, Modifier.weight(1f), small = true)
                }
            }
            Spacer(Modifier.size(10.dp))
        }
    }
    items((1..total).chunked(cols), key = { "row" + it.first() }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { p ->
                PageTile(vm, file, p, p in chosen, Modifier.weight(1f)) {
                    vm.session.setPages(d.local, if (p in chosen) chosen - p else chosen + p)
                }
            }
            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PageTile(vm: PrintViewModel, file: File, page: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val img by produceState<android.graphics.Bitmap?>(null, file, page) {
        value = runCatching { vm.images.page(file, page, 180) }.getOrNull()
    }
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (on) CP.Ink else Color.Transparent, RoundedCornerShape(10.dp))
            .background(if (on) CP.Sunk else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Page $page"; selected = on }
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.707f).clip(RoundedCornerShape(4.dp)).background(Color.White)
            .border(1.dp, CP.Line, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            img?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(if (on) 1f else 0.38f))
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                .background(if (on) CP.Ink else Color.White).border(2.dp, if (on) CP.Ink else CP.Line2, CircleShape),
                contentAlignment = Alignment.Center) {
                if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Text("Page $page", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (on) CP.Ink else CP.Muted,
            modifier = Modifier.padding(top = 4.dp))
    }
}
