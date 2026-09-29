package edu.campus.printapp.flow

import edu.campus.printapp.core.ImageInfo
import java.io.File

/** What the phone learns from a file before sending it. */
data class FileFacts(val type: String, val pageCount: Int, val image: ImageInfo? = null)

/** Reads a file on the phone (PDF pages, picture size). Throws UserError with words for the student. */
fun interface FileReader {
    suspend fun read(file: File, type: String): FileFacts
}

/** A file the student picked or shared. fetch() makes the app's own copy (throws UserError when it cannot). */
class IncomingFile(val name: String, val size: Long, val fetch: suspend () -> File)

object FileTypes {
    /** PDF, PNG or JPEG from the first bytes (the name can lie), or null. */
    fun detect(file: File): String? {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()) return "PNG"
        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) return "JPEG"
        var i = 0
        while (i + 4 < n) {
            if (head[i] == '%'.code.toByte() && head[i + 1] == 'P'.code.toByte() && head[i + 2] == 'D'.code.toByte() &&
                head[i + 3] == 'F'.code.toByte() && head[i + 4] == '-'.code.toByte()) return "PDF"
            i++
        }
        return null
    }
}
