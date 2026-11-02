package com.readcore.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Html
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/** This text-first reader does not execute book HTML or extract archive paths to disk. */
sealed class OpenBook {
    data class Text(val chapters: List<String>, val spineIndices: List<Int> = chapters.indices.toList()) : OpenBook()
    data class Pdf(val file: File, val pageCount: Int) : OpenBook()
    data class Comic(val file: File, val entries: List<String>) : OpenBook()
    val count: Int get() = when (this) { is Text -> chapters.size; is Pdf -> pageCount; is Comic -> entries.size }
}

object ReaderCore {
    private const val MAX_ENTRY = 16 * 1024 * 1024
    private const val MAX_TEXT = 48 * 1024 * 1024
    private fun readLimited(input: java.io.InputStream, limit: Int = MAX_ENTRY): ByteArray {
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                require(output.size().toLong() + n <= limit) { "Book content is too large" }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
    }
    private fun entry(zip: ZipFile, name: String): ByteArray {
        val item = zip.getEntry(name) ?: error("Missing EPUB entry: $name")
        require(!item.isDirectory && (item.size < 0 || item.size <= MAX_ENTRY)) { "EPUB entry is too large" }
        return readLimited(zip.getInputStream(item))
    }
    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(bytes.inputStream(), null)
    }
    private fun normalizedPath(base: String, href: String): String {
        val baseUri = URI("https://readcore.invalid/" + base)
        val resolved = baseUri.resolve(href).normalize()
        require(resolved.scheme == "https" && resolved.host == "readcore.invalid") { "External EPUB references are not supported" }
        val rawPath = resolved.rawPath.removePrefix("/")
        val path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        require(path.split('/').none { it == ".." || it == "." }) { "Invalid EPUB path" }
        return path
    }
    fun epub(file: File): OpenBook.Text = ZipFile(file).use { zip ->
        val container = parser(entry(zip, "META-INF/container.xml"))
        var root: String? = null
        while (container.next() != XmlPullParser.END_DOCUMENT) {
            if (container.eventType == XmlPullParser.START_TAG && container.name == "rootfile") {
                root = container.getAttributeValue(null, "full-path"); break
            }
        }
        val opfPath = root ?: error("EPUB has no package document")
        val opf = parser(entry(zip, opfPath))
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        while (opf.next() != XmlPullParser.END_DOCUMENT) {
            if (opf.eventType != XmlPullParser.START_TAG) continue
            when (opf.name) {
                "item" -> {
                    val id = opf.getAttributeValue(null, "id")
                    val href = opf.getAttributeValue(null, "href")
                    if (id != null && href != null) manifest[id] = normalizedPath(opfPath, href)
                }
                "itemref" -> opf.getAttributeValue(null, "idref")?.let(spine::add)
            }
        }
        var total = 0
        val chapters = spine.mapIndexedNotNull { index, id ->
            val path = manifest[id] ?: return@mapIndexedNotNull null
            val bytes = entry(zip, path)
            total += bytes.size
            require(total <= MAX_TEXT) { "EPUB text is too large" }
            val text = Html.fromHtml(bytes.toString(Charsets.UTF_8), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (text.isBlank()) null else index to text
        }
        require(chapters.isNotEmpty()) { "EPUB contains no readable text" }
        OpenBook.Text(chapters.map { it.second }, chapters.map { it.first })
    }

    /** Supports unencrypted MOBI 6 / PalmDOC text records, including PalmDOC compression. */
    fun mobi(file: File): OpenBook.Text {
        require(file.length() <= MAX_TEXT.toLong()) { "MOBI file is too large" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16(offset: Int): Int { require(offset >= 0 && offset + 2 <= bytes.size) { "Invalid MOBI" }; return buffer.getShort(offset).toInt() and 0xffff }
        fun u32(offset: Int): Int { require(offset >= 0 && offset + 4 <= bytes.size) { "Invalid MOBI" }; return buffer.getInt(offset) }
        require(bytes.size >= 86) { "Invalid MOBI" }
        val records = u16(76)
        require(records >= 2 && 78L + records * 8L <= bytes.size) { "Invalid MOBI records" }
        val offsets = (0 until records).map { u32(78 + it * 8) } + bytes.size
        require(offsets.zipWithNext().all { (a, b) -> a >= 0 && a <= b && b <= bytes.size }) { "Invalid MOBI offsets" }
        val header = offsets[0]
        val compression = u16(header)
        val textLength = u32(header + 4)
        val textRecords = u16(header + 8)
        require(u16(header + 12) == 0) { "DRM-protected MOBI is not supported" }
        require(compression == 1 || compression == 2) { "This MOBI compression is not supported" }
        require(textLength in 0..MAX_TEXT && textRecords < records) { "Invalid MOBI text size" }
        val encoding = if (header + 32 <= offsets[1] && String(bytes, header + 16, 4, Charsets.US_ASCII) == "MOBI") u32(header + 28) else 1252
        val out = ByteArrayOutputStream()
        for (record in 1..textRecords) {
            val data = bytes.copyOfRange(offsets[record], offsets[record + 1])
            if (compression == 1) out.write(data) else {
                var i = 0
                val decoded = ArrayList<Byte>()
                while (i < data.size && decoded.size < 8192) {
                    val c = data[i++].toInt() and 255
                    when {
                        c in 1..8 -> repeat(c) { require(i < data.size) { "Invalid MOBI record" }; decoded.add(data[i++]) }
                        c <= 0x7f -> decoded.add(c.toByte())
                        c <= 0xbf -> {
                            require(i < data.size) { "Invalid MOBI record" }
                            val pair = (c shl 8) or (data[i++].toInt() and 255)
                            val distance = (pair shr 3) and 0x7ff
                            val length = (pair and 7) + 3
                            require(distance > 0 && distance <= decoded.size) { "Invalid MOBI compression" }
                            repeat(length) { decoded.add(decoded[decoded.size - distance]) }
                        }
                        else -> { decoded.add(32); decoded.add((c xor 0x80).toByte()) }
                    }
                }
                require(decoded.size <= 8192) { "MOBI record is too large" }
                out.write(decoded.toByteArray())
            }
            require(out.size() <= MAX_TEXT) { "MOBI text is too large" }
        }
        val raw = out.toByteArray().take(textLength).toByteArray()
        val charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val text = Html.fromHtml(raw.toString(charset), Html.FROM_HTML_MODE_LEGACY).toString().trim()
        require(text.isNotEmpty()) { "MOBI contains no readable text" }
        return OpenBook.Text(text.chunked(16000))
    }
    fun open(file: File, format: String): OpenBook = when (format.lowercase()) {
        "epub" -> epub(file)
        "mobi" -> mobi(file)
        "pdf" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { OpenBook.Pdf(file, it.pageCount) } }
        "cbz" -> ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }.map { it.name }.sorted().toList()
            require(entries.isNotEmpty() && entries.size <= 5000) { "CBZ has no images or has too many pages" }
            OpenBook.Comic(file, entries)
        }
        else -> error("Unsupported format: $format")
    }
    fun page(book: OpenBook, index: Int): Bitmap = when (book) {
        is OpenBook.Pdf -> ParcelFileDescriptor.open(book.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf -> pdf.openPage(index).use { page ->
            val scale = minOf(2f, 1600f / page.width, 2400f / page.height)
            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } } }
        is OpenBook.Comic -> ZipFile(book.file).use { zip ->
            val data = entry(zip, book.entries[index])
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "Invalid image dimensions" }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2400) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Invalid image")
        }
        is OpenBook.Text -> error("Text does not have bitmap pages")
    }
}
