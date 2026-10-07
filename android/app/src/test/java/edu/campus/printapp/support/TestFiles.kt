package edu.campus.printapp.support

import edu.campus.printapp.core.ImageInfo
import edu.campus.printapp.core.UserError
import edu.campus.printapp.flow.FileFacts
import edu.campus.printapp.flow.FileReader
import edu.campus.printapp.flow.IncomingFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Real files for the tests: valid PDFs (the real server opens them with PDFBox) and pictures. */
object TestFiles {

    val dir: File by lazy { Files.createTempDirectory("campusprint-test").toFile() }

    /** A PDF with [pages] pages; landscape = the given pages are wider than tall. */
    fun pdf(name: String, pages: Int, landscape: Set<Int> = emptySet()): File {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(text: String) { offsets += out.size(); out.write(text.toByteArray(Charsets.ISO_8859_1)) }
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val first = 4                                                // 1 catalog, 2 pages, 3 font, then page + content pairs
        val kids = (0 until pages).joinToString(" ") { "${first + it * 2} 0 R" }
        obj("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        obj("2 0 obj << /Type /Pages /Kids [$kids] /Count $pages >> endobj\n")
        obj("3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")
        for (i in 0 until pages) {
            val box = if ((i + 1) in landscape) "0 0 842 595" else "0 0 595 842"
            val content = "BT /F1 48 Tf 100 400 Td (Page ${i + 1}) Tj ET"
            obj("${first + i * 2} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [$box] /Resources << /Font << /F1 3 0 R >> >> /Contents ${first + i * 2 + 1} 0 R >> endobj\n")
            obj("${first + i * 2 + 1} 0 obj << /Length ${content.length} >> stream\n$content\nendstream endobj\n")
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer << /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }

    /** A real PNG (top half blue, bottom half sand), written by hand: Android's unit tests have no javax.imageio. */
    fun png(name: String, w: Int = 800, h: Int = 600): File {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)                                              // no filter
            val (r, g, b) = if (y < h / 2) Triple(0x2A, 0x5A, 0xA0) else Triple(0xEE, 0xC7, 0x7A)
            repeat(w) { raw.write(r); raw.write(g); raw.write(b) }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            data.write(t); data.write(body)
            val crc = CRC32(); crc.update(t); crc.update(body)
            data.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(8); write(2); write(0); write(0); write(0) } }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return File(dir, name).apply { writeBytes(out.toByteArray()) }
    }

    /** Width and height from a PNG's header. */
    fun pngSize(file: File): Pair<Int, Int>? {
        val b = file.readBytes()
        if (b.size < 24 || b[1] != 'P'.code.toByte()) return null
        fun int(at: Int) = ((b[at].toInt() and 255) shl 24) or ((b[at + 1].toInt() and 255) shl 16) or
            ((b[at + 2].toInt() and 255) shl 8) or (b[at + 3].toInt() and 255)
        return int(16) to int(20)
    }

    /** Width and height from a JPEG's frame header (SOF). */
    fun jpegSize(file: File): Pair<Int, Int>? {
        val b = file.readBytes()
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 255
            val len = ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((b[i + 5].toInt() and 255) shl 8) or (b[i + 6].toInt() and 255)
                val w = ((b[i + 7].toInt() and 255) shl 8) or (b[i + 8].toInt() and 255)
                return w to h
            }
            i += 2 + len
        }
        return null
    }

    /** A test file kept with the app's device tests (the same files the website was tested with). */
    fun fixture(name: String): File {
        val f = listOf(File("src/androidTest/assets", name), File("app/src/androidTest/assets", name)).first { it.exists() }
        return File(dir, name).also { f.copyTo(it, overwrite = true) }
    }

    fun text(name: String) = File(dir, name).apply { writeText("This is just text, not a PDF at all.") }

    /** A small Word file (.docx): a ZIP with the parts Word needs. extra: more parts, by name. */
    fun docx(name: String, extra: Map<String, String> = emptyMap()): File = File(dir, name).apply {
        java.util.zip.ZipOutputStream(outputStream()).use { zip ->
            val parts = linkedMapOf(
                "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "_rels/.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>",
                "word/document.xml" to "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>")
            parts.putAll(extra)
            for ((part, content) in parts) {
                zip.putNextEntry(java.util.zip.ZipEntry(part))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** An old Word file (.doc): only how it starts matters here. */
    fun oldDoc(name: String): File = File(dir, name).apply {
        writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(600))
    }

    /** As the app does with a picked file: its own copy first. */
    fun incoming(f: File) = IncomingFile(f.name, f.length()) {
        File(dir, "copy-${System.nanoTime()}-${f.name}").also { f.copyTo(it) }
    }

    /** Reads files like the phone does, without Android: pages of a PDF, size of a picture. */
    val reader = FileReader { file, type ->
        if (type == "PDF") {
            val n = Regex("/Type /Page\\b(?!s)").findAll(file.readText(Charsets.ISO_8859_1)).count()
            if (n == 0) throw UserError("This PDF cannot be opened. It may be damaged.")
            FileFacts("PDF", n)
        } else {
            val (w, h) = (if (type == "JPEG") jpegSize(file) else pngSize(file))
                ?: throw UserError("This picture cannot be opened. It may be damaged.")
            FileFacts(type, 1, ImageInfo(w, h, 96.0, 1))
        }
    }
}
