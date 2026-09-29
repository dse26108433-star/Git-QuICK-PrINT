package edu.campus.printapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import edu.campus.printapp.core.ImageInfo
import edu.campus.printapp.core.Size
import edu.campus.printapp.core.UserError
import edu.campus.printapp.flow.FileFacts
import edu.campus.printapp.flow.FileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Pictures of pages for the list, the page picker and the print preview.
 * Android's PDF renderer can only draw one page at a time per file, so each
 * file has a lock; only a few files stay open, and drawn pages are cached.
 */
class PageImages {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val openLock = Mutex()
    private val open = LinkedHashMap<String, PdfFile>()          // most recently used last
    private val sizes = HashMap<String, Size>()

    /** A page's size as seen (points, after its own rotation and crop): the size the Xerox PC lays out. */
    suspend fun pageSize(file: File, page: Int): Size {
        val key = file.path + "#" + page
        synchronized(sizes) { sizes[key] }?.let { return it }
        val s = pdf(file).use { it.size(page) }
        synchronized(sizes) { sizes[key] = s }
        return s
    }

    /** One page drawn widthPx wide (white paper), or from the cache. */
    suspend fun page(file: File, page: Int, widthPx: Int): Bitmap {
        val w = widthPx.coerceIn(24, 2000)
        val key = "${file.path}#$page@$w"
        cache.get(key)?.let { return it }
        val bmp = pdf(file).use { it.draw(page, w) }
        cache.put(key, bmp)
        return bmp
    }

    /** A picture turned upright (as the camera meant), at most maxSide pixels. */
    suspend fun picture(file: File, maxSide: Int): Bitmap {
        val key = "${file.path}@pic$maxSide"
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { decodeUpright(file, maxSide) }
            ?: throw UserError("This picture cannot be opened. It may be damaged.")
        cache.put(key, bmp)
        return bmp
    }

    /** The picture for a file's card. */
    suspend fun thumb(file: File, type: String, widthPx: Int): Bitmap =
        if (type == "PDF") page(file, 1, widthPx) else picture(file, widthPx * 2)

    fun forget(file: File) {
        cache.snapshot().keys.filter { it.startsWith(file.path) }.forEach { cache.remove(it) }
        synchronized(open) { open.remove(file.path) }?.closeLater()
    }

    // ------------------------------------------------------------------ open PDFs (a few at a time)

    private suspend fun pdf(file: File): PdfFile.Use = openLock.withLock {
        val f = synchronized(open) {
            open.remove(file.path)?.also { open[file.path] = it }
        } ?: PdfFile(file).also { synchronized(open) { open[file.path] = it } }
        synchronized(open) {
            while (open.size > 3) {
                val oldest = open.keys.first()
                open.remove(oldest)?.closeLater()
            }
        }
        f.use()
    }

    private class PdfFile(private val file: File) {
        private val lock = Mutex()
        private var renderer: PdfRenderer? = null
        private var users = 0
        private var closing = false

        fun use(): Use {
            synchronized(this) { users++ }
            return Use()
        }

        inner class Use : AutoCloseable {
            suspend fun size(page: Int): Size = lock.withLock {
                withContext(Dispatchers.IO) {
                    r().openPage(page - 1).use { Size(it.width.toDouble(), it.height.toDouble()) }
                }
            }

            suspend fun draw(page: Int, widthPx: Int): Bitmap = lock.withLock {
                withContext(Dispatchers.IO) {
                    r().openPage(page - 1).use { p ->
                        val h = (widthPx.toDouble() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, 4000)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }

            override fun close() {
                val shut = synchronized(this@PdfFile) { users--; closing && users == 0 }
                if (shut) shutNow()
            }
        }

        private fun r(): PdfRenderer = renderer ?: openRenderer(file).also { renderer = it }

        fun closeLater() {
            val now = synchronized(this) { closing = true; users == 0 }
            if (now) shutNow()
        }

        private fun shutNow() {
            runCatching { renderer?.close() }
            renderer = null
        }
    }

    companion object {
        /** Reads a file on the phone: pages of a PDF, size of a picture. */
        val reader = FileReader { file, type ->
            withContext(Dispatchers.IO) {
                if (type == "PDF") {
                    val r = openRenderer(file)
                    try {
                        if (r.pageCount == 0) throw UserError("This PDF has no pages.")
                        FileFacts("PDF", r.pageCount)
                    } finally {
                        r.close()
                    }
                } else {
                    FileFacts(type, 1, imageInfo(file) ?: throw UserError("This picture cannot be opened. It may be damaged."))
                }
            }
        }

        private fun openRenderer(file: File): PdfRenderer {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw UserError("This PDF is locked with a password. Save a copy without the password and add it again.")
            } catch (e: IOException) {
                fd.close()
                throw UserError("This PDF cannot be opened. It may be damaged.")
            }
        }

        /** Size as stored, the camera's turn and resolution (the server measures again after the upload). */
        fun imageInfo(file: File): ImageInfo? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val exif = runCatching { ExifInterface(file.path) }.getOrNull()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf { it in 1..8 } ?: 1
            return ImageInfo(bounds.outWidth, bounds.outHeight, 96.0, orientation)
        }

        private fun decodeUpright(file: File, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val exif = runCatching { ExifInterface(file.path) }.getOrNull()
            val m = Matrix()
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1) {
                2 -> m.postScale(-1f, 1f)
                3 -> m.postRotate(180f)
                4 -> m.postScale(1f, -1f)
                5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                6 -> m.postRotate(90f)
                7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
                8 -> m.postRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
    }
}
