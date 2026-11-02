package com.readcore.app

import android.content.Context
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
                    .put("device_id", local.deviceId).put("updated_at", local.updatedAt)
                val request = api.request("/api/v1/sync").put(json.toString().toRequestBody("application/json".toMediaType())).build()
                api.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Progress sync failed: ${response.code}" }
                    val remote = JSONObject(response.body?.string() ?: error("Missing sync response"))
                    val timestamp = remote.getLong("updated_at")
                    if (timestamp > local.updatedAt) {
                        dao.storeRemoteCandidate(local.userId, local.bookId, local.updatedAt, remote.getString("position"), remote.getDouble("percent"), remote.getString("device_id"), timestamp)
                    } else dao.markSynced(local.userId, local.bookId, local.updatedAt)
                }
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Result.retry() }
    }
}

class BookDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
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
        if (!book.remote || !id.matches(Regex("[a-f0-9]{64}")) || book.format !in setOf("epub", "pdf", "mobi", "cbz", "cbr")) return@withContext Result.failure()
        val dir = File(applicationContext.filesDir, "books").apply { mkdirs() }
        val partial = File(dir, "$id.part")
        try {
            val api = WorkerApi(applicationContext)
            var start = if (book.etag != null) partial.length() else 0L
            if (start == 0L && partial.exists()) partial.delete()
            val builder = api.request("/api/v1/books/$id/download")
            if (start > 0) builder.header("Range", "bytes=$start-").header("If-Range", book.etag!!)
            api.client.newCall(builder.build()).execute().use { response ->
                val etag = response.header("ETag") ?: book.etag
                if (response.code == 416 && start > 0 && start == book.size && etag != null) {
                    currentCoroutineContext().ensureActive()
                    val target = File(dir, "$id.${book.format}")
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    dao.setDownload(id, target.absolutePath, "ready", start, etag)
                    cacheCover(api, id, dao)
                    return@withContext Result.success()
                }
                check(response.code == 200 || response.code == 206) { "Download failed: ${response.code}" }
                val body = response.body ?: error("Empty download")
                val total = resumeTotal(response.code, start, response.header("Content-Range"), body.contentLength()) ?: error("Invalid download range")
                check(total in 1..(512L * 1024 * 1024)) { "Book too large or empty" }
                if (response.code == 200) start = 0
                // Store the validator before writing, so an interrupted worker can resume safely.
                dao.setDownload(id, book.localPath, "downloading", start, etag)
                body.byteStream().use { input ->
                    writeDownload(partial, start, total, input) { written -> dao.setDownload(id, book.localPath, "downloading", written, etag) }
                }
                currentCoroutineContext().ensureActive()
                val target = File(dir, "$id.${book.format}")
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                dao.setDownload(id, target.absolutePath, "ready", total, etag)
            }
            cacheCover(api, id, dao)
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) {
            dao.setDownload(id, book.localPath, "queued", partial.length(), dao.book(id)?.etag ?: book.etag)
            Result.retry()
        }
    }
}
