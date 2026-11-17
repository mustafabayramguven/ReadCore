package com.readcore.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

const val MAX_BOOK_BYTES = 512L * 1024 * 1024

/**
 * Check the server's response before appending to a partially downloaded file.
 * A full 200 response without Content-Length (chunked through a proxy or tunnel) falls back to the
 * size the library reported; -1 means the length is unknown and the stream is read to its end.
 */
internal fun resumeTotal(status: Int, start: Long, contentRange: String?, contentLength: Long, expected: Long = -1): Long? {
    if (status == 200) return when {
        contentLength >= 0 -> contentLength
        expected > 0 -> expected
        else -> -1
    }
    if (status != 206 || start <= 0) return null
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange ?: "") ?: return null
    val actualStart = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (actualStart != start || end < actualStart || end >= total || end != total - 1) return null
    if (contentLength >= 0 && contentLength != end - actualStart + 1) return null
    return total
}

/**
 * Preserve an existing prefix on 206; start at zero on a full 200 response.
 * With [total] = -1 the length is unknown: read until the stream ends, up to [MAX_BOOK_BYTES].
 * Returns the final file size.
 */
internal suspend fun writeDownload(file: File, start: Long, total: Long, input: InputStream, progress: suspend (Long) -> Unit = {}): Long {
    val known = total >= 0
    require(start >= 0 && (!known || start <= total) && (start == 0L || file.length() >= start)) { "Invalid partial file" }
    val limit = if (known) total else MAX_BOOK_BYTES
    RandomAccessFile(file, "rw").use { output ->
        output.setLength(start)
        output.seek(start)
        val buffer = ByteArray(64 * 1024)
        var written = start
        var lastUpdate = start
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            check(written <= limit) { if (known) "Unexpected download length" else "Book is too large" }
            output.write(buffer, 0, count)
            if (written - lastUpdate >= 512 * 1024) { progress(written); lastUpdate = written }
        }
        if (known) check(written == total) { "Incomplete download" } else check(written > 0) { "Empty download" }
        progress(written)
        return written
    }
}
