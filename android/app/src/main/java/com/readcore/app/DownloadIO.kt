package com.readcore.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Check the server's response before appending to a partially downloaded file. */
internal fun resumeTotal(status: Int, start: Long, contentRange: String?, contentLength: Long): Long? {
    if (status == 200) return if (contentLength >= 0) contentLength else null
    if (status != 206 || start <= 0) return null
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange ?: "") ?: return null
    val actualStart = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (actualStart != start || end < actualStart || end >= total || contentLength != end - actualStart + 1 || end != total - 1) return null
    return total
}

/** Preserve an existing prefix on 206; start at zero on a full 200 response. */
internal suspend fun writeDownload(file: File, start: Long, total: Long, input: InputStream, progress: suspend (Long) -> Unit = {}) {
    require(start in 0..total && (start == 0L || file.length() >= start)) { "Invalid partial file" }
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
            check(written <= total) { "Unexpected download length" }
            output.write(buffer, 0, count)
            if (written - lastUpdate >= 1024 * 1024) { progress(written); lastUpdate = written }
        }
        check(written == total) { "Incomplete download" }
    }
}
