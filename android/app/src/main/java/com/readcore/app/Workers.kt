package com.readcore.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private class WorkerApi(context: Context) {
    private val prefs = context.getSharedPreferences("readcore", Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(context, "readcore-secrets", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.MINUTES).build()
    fun request(path: String): Request.Builder {
        val url = prefs.getString("url", "") ?: ""
        val token = secrets.getString("token", "") ?: ""
        require(url.startsWith("https://") && token.isNotBlank()) { "Server credentials missing" }
        return Request.Builder().url(url.trimEnd('/') + path).header("Authorization", "Bearer $token")
    }
}

class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = OfflineStore.db(applicationContext).offline()
        try {
            val api = WorkerApi(applicationContext)
            for (local in dao.pending()) {
                currentCoroutineContext().ensureActive()
                val json = JSONObject().put("book_id", local.bookId).put("user_id", local.userId)
                    .put("position", local.position).put("percent", local.percent)
                    .put("progression", local.progression ?: JSONObject.NULL)
                    .put("device_id", local.deviceId).put("updated_at", local.updatedAt)
                val request = api.request("/api/v1/sync").put(json.toString().toRequestBody("application/json".toMediaType())).build()
                api.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Progress sync failed: ${response.code}" }
                    val remote = JSONObject(response.body?.string() ?: error("Missing sync response"))
                    val timestamp = remote.getLong("updated_at")
                    if (timestamp > local.updatedAt) {
                        dao.storeRemoteCandidate(local.userId, local.bookId, local.updatedAt, remote.getString("position"), remote.getDouble("percent"), remote.getString("device_id"), timestamp, remote.optDoubleOrNull("progression"))
                    } else dao.markSynced(local.userId, local.bookId, local.updatedAt)
                }
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Result.retry() }
    }
}


internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

/** A failure that retrying cannot fix (bad token, book removed from the server, ...). */
private class PermanentDownloadError(message: String) : Exception(message)

class BookDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val notificationId get() = (inputData.getString("bookId") ?: "").hashCode()

    private fun notification(title: String, done: Long, total: Long): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
        if (total > 0) builder.setProgress(1000, ((done * 1000) / total).toInt().coerceIn(0, 1000), false)
            .setContentText("${done / (1024 * 1024)} / ${(total + 1024 * 1024 - 1) / (1024 * 1024)} MB")
        else builder.setProgress(0, 0, true).setContentText("${done / (1024 * 1024)} MB")
        return builder.build()
    }
    private fun foregroundInfo(title: String, done: Long, total: Long): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification(title, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification(title, done, total))
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Downloading book", 0, -1)

    /** Long downloads run as a foreground service so Android does not stop them after 10 minutes. */
    private suspend fun showProgress(title: String, done: Long, total: Long) {
        try { setForeground(foregroundInfo(title, done, total)) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { /* Background start not allowed: continue as a normal worker. */ }
    }

    private suspend fun cacheCover(api: WorkerApi, id: String, dao: OfflineDao) {
        try {
            api.client.newCall(api.request("/api/v1/books/$id/cover").build()).execute().use { response ->
                if (!response.isSuccessful) return
                val dir = File(applicationContext.filesDir, "covers").apply { mkdirs() }
                val temp = File.createTempFile("cover", ".tmp", dir)
                try {
                    val body = response.body ?: return
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(8192); var count = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= 8L * 1024 * 1024); output.write(buffer, 0, n) }
                    } }
                    val target = File(dir, "$id.jpg")
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    dao.setCover(id, target.absolutePath)
                } finally { temp.delete() }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { /* Books can be read without cover art. */ }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("bookId") ?: return@withContext Result.failure()
        val dao = OfflineStore.db(applicationContext).offline()
        val book = dao.book(id) ?: return@withContext Result.failure()
        if (!book.remote || !id.matches(Regex("[a-f0-9]{64}")) || book.format !in setOf("epub", "pdf", "mobi", "cbz", "cbr")) {
            dao.setDownload(id, book.localPath, "failed", 0, null, "This book cannot be downloaded")
            return@withContext Result.failure()
        }
        val dir = File(applicationContext.filesDir, "books").apply { mkdirs() }
        val partial = File(dir, "$id.part")
        val target = File(dir, "$id.${book.format}")
        suspend fun finish(bytes: Long, etag: String?) {
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dao.setDownload(id, target.absolutePath, "ready", bytes, etag)
        }
        try {
            showProgress(book.title, book.downloadedBytes, book.size)
            val api = WorkerApi(applicationContext)
            // Resume only when the partial file is tied to a validator from the same server file.
            var start = if (book.etag != null && partial.isFile) partial.length() else 0L
            if (start == 0L) partial.delete()
            // Identity encoding: a proxy that gzips would otherwise hide the length and break ranges.
            val builder = try { api.request("/api/v1/books/$id/download") } catch (_: IllegalArgumentException) { throw PermanentDownloadError("Set the server URL and token in Settings") }
            builder.header("Accept-Encoding", "identity")
            if (start > 0) builder.header("Range", "bytes=$start-").header("If-Range", book.etag!!)
            api.client.newCall(builder.build()).execute().use { response ->
                val etag = response.header("ETag") ?: book.etag
                when (response.code) {
                    401, 403 -> throw PermanentDownloadError("The server rejected the token. Check Settings.")
                    404 -> throw PermanentDownloadError("The book is no longer on the server. Refresh the library.")
                    416 -> {
                        // The partial file may already be complete; otherwise restart from zero.
                        if (start > 0 && start == book.size) { finish(start, etag); return@use }
                        partial.delete(); dao.setDownload(id, null, "queued", 0, null)
                        throw IOException("Restarting download")
                    }
                }
                if (response.code != 200 && response.code != 206) throw IOException("Server returned ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                if (response.code == 200) start = 0
                val total = resumeTotal(response.code, start, response.header("Content-Range"), body.contentLength(), book.size)
                    ?: run { partial.delete(); dao.setDownload(id, null, "queued", 0, null); throw IOException("Invalid download range") }
                if (total == 0L || total > MAX_BOOK_BYTES) throw PermanentDownloadError("The book is empty or larger than 512 MB")
                // Store the validator before writing, so an interrupted worker can resume safely.
                dao.setDownload(id, null, "downloading", start, etag)
                var shown = 0L
                val size = body.byteStream().use { input ->
                    writeDownload(partial, start, total, input) { written ->
                        dao.setDownloaded(id, written)
                        if (written - shown >= 2L * 1024 * 1024) { shown = written; showProgress(book.title, written, total) }
                    }
                }
                finish(size, etag)
            }
            cacheCover(api, id, dao)
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (e: PermanentDownloadError) {
            dao.setDownload(id, null, "failed", partial.length(), dao.book(id)?.etag, e.message)
            Result.failure()
        } catch (e: Exception) {
            val tries = runAttemptCount + 1
            if (tries >= MAX_ATTEMPTS) {
                dao.setDownload(id, null, "failed", partial.length(), dao.book(id)?.etag, "Download failed: ${e.message ?: "network error"}")
                Result.failure()
            } else {
                dao.setDownload(id, null, "queued", partial.length(), dao.book(id)?.etag, "Retrying (${e.message ?: "network error"})")
                Result.retry()
            }
        }
    }
    companion object {
        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 6
    }
}
