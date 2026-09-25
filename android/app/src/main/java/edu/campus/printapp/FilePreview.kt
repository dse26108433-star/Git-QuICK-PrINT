package edu.campus.printapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/** A problem the student can fix; the message is shown as it is. */
class UserError(message: String) : Exception(message)

/** Opens the chosen file on the phone so the student can see it before paying. */
object FilePreview {

    /** PDF, PNG or JPEG from the first bytes (the name can lie), or null. */
    fun detectType(file: File): String? {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()) return "PNG"
        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return "JPEG"
        var i = 0
        while (i + 4 < n) {
            if (head[i] == '%'.code.toByte() && head[i + 1] == 'P'.code.toByte() &&
                head[i + 2] == 'D'.code.toByte() && head[i + 3] == 'F'.code.toByte() &&
                head[i + 4] == '-'.code.toByte()) return "PDF"
            i++
        }
        return null
    }

    /** Page count, plus pictures of the first few pages. */
    fun renderPdf(file: File, maxPages: Int, widthPx: Int): Pair<Int, List<Bitmap>> =
        open(file).use { r ->
            val count = r.pageCount
            if (count == 0) throw UserError("This PDF has no pages.")
            count to (1..minOf(count, maxPages)).map { draw(r, it, widthPx) }
        }

    /** Pictures of the given pages (1 = first page), e.g. the pages the student chose. */
    fun renderPdfPages(file: File, pages: List<Int>, widthPx: Int): List<Bitmap> =
        open(file).use { r -> pages.filter { it in 1..r.pageCount }.map { draw(r, it, widthPx) } }

    private fun draw(r: PdfRenderer, pageNumber: Int, widthPx: Int): Bitmap =
        r.openPage(pageNumber - 1).use { page ->
            val height = (widthPx.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }

    private fun open(file: File): PdfRenderer {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close()
            throw UserError("This PDF is locked with a password. Save a copy without the password, then choose it again.")
        } catch (e: IOException) {
            fd.close()
            throw UserError("This PDF cannot be opened. It may be damaged.")
        }
    }

    /** A picture small enough for the screen, turned upright like the gallery shows it. */
    fun decodeImage(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
