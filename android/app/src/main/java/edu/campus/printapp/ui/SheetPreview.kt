package edu.campus.printapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.PageImages
import edu.campus.printapp.core.MM
import edu.campus.printapp.core.Normalized
import edu.campus.printapp.core.Paper
import edu.campus.printapp.core.Sheet
import edu.campus.printapp.core.layoutOptions
import edu.campus.printapp.core.layoutSheet
import edu.campus.printapp.core.pagesFromSpec
import edu.campus.printapp.core.pictureSize
import edu.campus.printapp.flow.Doc
import edu.campus.printapp.flow.PictureMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps the first sheet of a file, drawn as it will print, as a JPEG on the
 * phone. After paying, that picture is what the student shows at the counter
 * (the uploaded file itself is deleted from the phone).
 */
class SheetPictureMaker(private val images: PageImages) : PictureMaker {
    override suspend fun draw(d: Doc, n: Normalized, paper: Paper, to: File): Boolean {
        if (d.file == null || chosenPages(d, n).isEmpty()) return false
        val bitmap = drawSheet(d, n, paper, images, 0, 760, 1000, guides = false)
        return withContext(Dispatchers.IO) {
            to.parentFile?.mkdirs()
            val tmp = File(to.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (to.exists()) to.delete()
            tmp.renameTo(to)
        }
    }
}

/** The chosen pages, as page numbers in print order (none when the list cannot be read). */
fun chosenPages(d: Doc, n: Normalized): List<Int> {
    if (d.type != "PDF") return listOf(1)
    return runCatching { pagesFromSpec(n.settings.pages, d.pageCount ?: 0).sorted() }.getOrDefault(emptyList())
}

/** How many sheet pictures the preview has (sides, for two-sided). */
fun sheetCount(d: Doc, n: Normalized): Int {
    val pages = chosenPages(d, n).size
    val per = n.settings.pagesPerSheet
    return if (d.type == "PDF" && per > 1) ceil(pages / per.toDouble()).toInt() else pages
}

private sealed interface Drawn {
    data class Ok(val bitmap: Bitmap) : Drawn
    data class Empty(val text: String) : Drawn
    data object Busy : Drawn
}

/**
 * One sheet of paper exactly as the Xerox PC will print it: the same layout
 * rules (Layout.java), the page drawn as it looks, margins shown as a dashed
 * line, grey when black & white is chosen.
 */
@Composable
fun SheetPreview(
    d: Doc,
    n: Normalized?,
    paper: Paper,
    images: PageImages,
    sheet: Int,
    onSheet: (Int) -> Unit,
    height: Dp
) {
    val total = if (n == null) 0 else sheetCount(d, n)
    val k = if (total == 0) 0 else sheet.coerceIn(0, total - 1)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(CP.Viewer)
                .semantics { contentDescription = "Print preview" },
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val boxW = with(density) { (maxWidth - 36.dp).toPx() }.roundToInt().coerceAtLeast(120)
            val boxH = with(density) { (maxHeight - 36.dp).toPx() }.roundToInt().coerceAtLeast(120)
            val drawn by produceState<Drawn>(Drawn.Busy, d.local, d.file, d.image, d.localImage, n?.settings, n?.error, k, boxW, boxH, paper) {
                value = when {
                    d.error != null -> Drawn.Empty("This file cannot be printed.")
                    n == null || d.file == null -> Drawn.Empty("Reading the file…")
                    total == 0 -> Drawn.Empty("Choose at least one page to see the preview.")
                    else -> try {
                        Drawn.Ok(drawSheet(d, n, paper, images, k, boxW, boxH))
                    } catch (e: Exception) {
                        Drawn.Empty("The preview could not be drawn. The file is still fine to print.")
                    }
                }
            }
            val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
            when (val x = drawn) {
                is Drawn.Ok -> Image(
                    x.bitmap.asImageBitmap(), contentDescription = "Sheet ${k + 1} of $total",
                    colorFilter = if (n?.settings?.color == true) null else grey,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(18.dp)
                )
                is Drawn.Empty -> Text(x.text, color = CP.Line2, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                Drawn.Busy -> CircularProgressIndicator(color = CP.Line2, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
        }
        if (n != null && total > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { onSheet(k - 1) }, enabled = k > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous sheet")
                }
                val two = n.settings.twoSided
                val words = (if (two) "Side ${k + 1} of $total · sheet ${k / 2 + 1}, " + (if (k % 2 == 0) "front" else "back")
                    else "Sheet ${k + 1} of $total") + " · " + paper.id.replace("PHOTO_", "").replace("X", "×")
                Text(words, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CP.Ink,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                IconButton(onClick = { onSheet(k + 1) }, enabled = k < total - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next sheet")
                }
            }
        }
    }
}

/** Draws sheet k into a bitmap that fits boxW × boxH pixels. guides: the dashed margin line of the preview. */
internal suspend fun drawSheet(d: Doc, n: Normalized, paper: Paper, images: PageImages, k: Int, boxW: Int, boxH: Int,
                               guides: Boolean = true): Bitmap {
    val s = n.settings
    val file = d.file!!
    val isPic = d.type != "PDF"
    val o = layoutOptions(s, paper, isPic)
    val pages = chosenPages(d, n)
    val sheet: Sheet = if (isPic) {
        val size = pictureSize(d.imageInfo ?: error("no picture size"), s.rotation)
        layoutSheet(0, 1, { size }, o)
    } else {
        val per = max(1, s.pagesPerSheet)
        val need = if (per == 1) listOf(k) else (listOf(0) + (k * per until min(pages.size, (k + 1) * per))).distinct()
        val sizes = need.associateWith { images.pageSize(file, pages[it]) }
        layoutSheet(k, pages.size, { sizes.getValue(it) }, o)
    }
    val scale = min(boxW / sheet.w, boxH / sheet.h)                 // pixels per point
    val w = (sheet.w * scale).roundToInt().coerceAtLeast(1)
    val h = (sheet.h * scale).roundToInt().coerceAtLeast(1)
    // the pictures of the pages first (slow), then everything is drawn at once
    val cellImages = sheet.cells.map { cell ->
        if (isPic) images.picture(file, 1600) else images.page(file, pages[cell.page], (cell.w * scale).roundToInt().coerceAtLeast(24))
    }
    return withContext(Dispatchers.Default) {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun top(y: Double, hh: Double) = ((sheet.h - y - hh) * scale).toFloat()     // PDF y-up -> bitmap y-down
        c.save()
        sheet.clip?.let { b -> c.clipRect(RectF((b.x * scale).toFloat(), top(b.y, b.h), ((b.x + b.w) * scale).toFloat(), top(b.y, b.h) + (b.h * scale).toFloat())) }
        sheet.cells.forEachIndexed { i, cell ->
            val x = (cell.x * scale).toFloat()
            val y = top(cell.y, cell.h)
            val cw = (cell.w * scale).toFloat()
            val ch = (cell.h * scale).toFloat()
            val img = cellImages[i]
            c.save()
            if (sheet.cells.size > 1) c.clipRect(x, y, x + cw, y + ch)
            if (!isPic) {
                c.drawBitmap(img, null, RectF(x, y, x + cw, y + ch), paint)
                if (sheet.cells.size > 1) {
                    val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x260E1A2B }
                    c.drawRect(x + .5f, y + .5f, x + cw - .5f, y + ch - .5f, frame)
                }
            } else {
                // the picture is upright already (EXIF); the student's turn is applied here, never stretched
                val turn = ((s.rotation % 360) + 360) % 360
                c.translate(x + cw / 2, y + ch / 2)
                c.rotate(turn.toFloat())
                val q = turn % 180 != 0
                val dw = if (q) ch else cw
                val dh = if (q) cw else ch
                c.drawBitmap(img, null, RectF(-dw / 2, -dh / 2, dw / 2, dh / 2), paint)
            }
            c.restore()
        }
        c.restore()
        if (s.marginMm > 0 && guides) {                                         // margin guides
            val m = (s.marginMm * MM * scale).toFloat()
            val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x8C3B6FF0.toInt()
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
            }
            c.drawRect(m, m, w - m, h - m, guide)
        }
        out
    }
}
