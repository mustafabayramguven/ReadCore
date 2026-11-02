package com.readcore.app

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

@Entity(primaryKeys = ["bookId", "day"])
data class ReadingDay(val bookId: String, val day: String, val seconds: Long = 0, val pages: Long = 0, val words: Long = 0)
data class DayTotal(val day: String, val seconds: Long, val pages: Long, val words: Long)
data class BookTotal(val bookId: String, val seconds: Long)
@Dao
abstract class ReadingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(row: ReadingDay)
    @Query("UPDATE ReadingDay SET seconds = seconds + :seconds, pages = pages + :pages, words = words + :words WHERE bookId = :bookId AND day = :day")
    abstract suspend fun add(bookId: String, day: String, seconds: Long, pages: Long, words: Long)
    @Transaction open suspend fun record(bookId: String, day: String, seconds: Long, pages: Long, words: Long) {
        insert(ReadingDay(bookId, day)); add(bookId, day, seconds, pages, words)
    }
    @Query("SELECT day, SUM(seconds) AS seconds, SUM(pages) AS pages, SUM(words) AS words FROM ReadingDay GROUP BY day ORDER BY day")
    abstract fun days(): Flow<List<DayTotal>>
    @Query("SELECT bookId, SUM(seconds) AS seconds FROM ReadingDay GROUP BY bookId")
    abstract fun books(): Flow<List<BookTotal>>
}
@Database(entities = [ReadingDay::class, CachedBook::class, LocalProgress::class], version = 2, exportSchema = false)
abstract class ReadDatabase : RoomDatabase() { abstract fun reading(): ReadingDao; abstract fun offline(): OfflineDao }

data class LibraryBook(val id: String, val title: String, val format: String, val localPath: String? = null, val remote: Boolean = false, val author: String = "", val size: Long = 0, val coverPath: String? = null, val downloadState: String = "none", val downloadedBytes: Long = 0, val lastReadAt: Long = 0, val percent: Double = 0.0)
data class RemoteOffer(val progress: LocalProgress, val label: String)

class ReadModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("readcore", Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(app, "readcore-secrets", MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val client = OkHttpClient()
    private val db = OfflineStore.db(app)
    val days = db.reading().days()
    val bookTotals = db.reading().books()
    var library by mutableStateOf(readLocal()); private set
    var url by mutableStateOf(prefs.getString("url", "") ?: ""); private set
    var token by mutableStateOf(secrets.getString("token", "") ?: ""); private set
    var fontSize by mutableIntStateOf(prefs.getInt("fontSize", 19)); private set
    var lineHeight by mutableIntStateOf(prefs.getInt("lineHeight", 150)); private set
    var paragraph by mutableIntStateOf(prefs.getInt("paragraph", 16)); private set
    var background by mutableStateOf(prefs.getString("background", "#000000") ?: "#000000"); private set
    var textColor by mutableStateOf(prefs.getString("textColor", "#EAEAEA") ?: "#EAEAEA"); private set
    var fontPath by mutableStateOf(prefs.getString("fontPath", "") ?: ""); private set
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false); private set
    var current by mutableStateOf<LibraryBook?>(null); private set
    var opened by mutableStateOf<OpenBook?>(null); private set
    var chapter by mutableIntStateOf(0); private set
    var userId by mutableStateOf(OfflineStore.user(app)); private set
    var remoteOffer by mutableStateOf<RemoteOffer?>(null); private set
    private var libraryObserver: Job? = null

    init {
        observeLibrary()
        viewModelScope.launch {
            for (book in readLocal()) {
                val path = book.localPath ?: continue
                val size = File(path).length()
                db.offline().insertBook(CachedBook(book.id, book.title, book.author, book.format, size, book.remote, path, null, "ready", size, null))
                if (prefs.contains("chapter_${book.id}")) {
                    db.offline().insertProgress(LocalProgress(userId, book.id, "legacy:${prefs.getInt("chapter_${book.id}", 0)}", 0.0, "", 0, false))
                }
            }
            OfflineStore.scheduleSync(app)
        }
    }
    private fun observeLibrary() {
        libraryObserver?.cancel()
        val activeUser = userId
        libraryObserver = viewModelScope.launch {
            db.offline().observeBooks(activeUser).collect { rows ->
                library = rows.map { row -> row.book.let { b -> LibraryBook(b.id, b.title, b.format, b.localPath?.takeIf { File(it).isFile }, b.remote, b.author, b.size, b.coverPath, b.downloadState, b.downloadedBytes, row.lastReadAt, row.percent) } }
            }
        }
    }
    fun saveUser(value: String) {
        val next = value.trim()
        if (next.isEmpty() || next.length > 128) { message = "Enter a user ID of up to 128 characters"; return }
        userId = next; prefs.edit().putString("userId", next).apply(); observeLibrary()
    }

    fun saveServer(newUrl: String, newToken: String) {
        url = newUrl.trim().trimEnd('/'); token = newToken.trim()
        prefs.edit().putString("url", url).apply(); secrets.edit().putString("token", token).apply()
    }
    fun typography(size: Int = fontSize, line: Int = lineHeight, margin: Int = paragraph, bg: String = background, fg: String = textColor) {
        fontSize = size; lineHeight = line; paragraph = margin; background = bg; textColor = fg
        prefs.edit().putInt("fontSize", size).putInt("lineHeight", line).putInt("paragraph", margin).putString("background", bg).putString("textColor", fg).apply()
    }
    private fun readLocal(): List<LibraryBook> = try {
        val arr = JSONArray(prefs.getString("books", "[]")); (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); LibraryBook(o.getString("id"), o.getString("title"), o.getString("format"), o.getString("path"), o.optBoolean("remote"), o.optString("author"))
        }.filter { it.localPath != null && File(it.localPath).isFile }
    } catch (_: Exception) { emptyList() }
    private suspend fun saveLocal(book: LibraryBook) {
        val list = (readLocal().filterNot { it.id == book.id } + book)
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("format", it.format).put("path", it.localPath).put("remote", it.remote).put("author", it.author)) }
        prefs.edit().putString("books", arr.toString()).apply()
        val path = book.localPath
        db.offline().mergeBook(CachedBook(book.id, book.title, book.author, book.format, path?.let { File(it).length() } ?: 0, book.remote, path, book.coverPath, if (path != null) "ready" else "none", path?.let { File(it).length() } ?: 0, null))
        if (path != null) db.offline().setDownload(book.id, path, "ready", File(path).length(), null)
    }
    private fun request(path: String): Request.Builder {
        require(url.startsWith("https://")) { "Use an HTTPS server URL" }
        require(token.isNotBlank()) { "Enter your server token in Settings" }
        return Request.Builder().url(url + path).header("Authorization", "Bearer $token")
    }
    fun refresh() { viewModelScope.launch {
        loading = true
        try {
            val activeUser = userId
            val remote = withContext(Dispatchers.IO) {
                val endpoint = "/api/v1/books?sort=recent&user_id=${Uri.encode(activeUser)}"
                client.newCall(request(endpoint).build()).execute().use { response ->
                    check(response.isSuccessful) { "Server returned ${response.code}" }
                    val json = JSONArray(response.body?.string() ?: "[]")
                    (0 until json.length()).map { index ->
                        val o = json.getJSONObject(index)
                        val book = CachedBook(o.getString("id"), o.getString("title"), o.optString("author", "").takeUnless { it == "null" } ?: "", o.getString("format"), o.optLong("size"), true, null, null, "none", 0, null,
                            series = o.optString("series").takeUnless { it.isBlank() || it == "null" }, language = o.optString("language").takeUnless { it.isBlank() || it == "null" })
                        val item = o.optJSONObject("progress")
                        val progress = item?.let { LocalProgress(activeUser, book.id, it.getString("position"), it.getDouble("percent"), it.getString("device_id"), it.getLong("updated_at"), false) }
                        book to progress
                    }
                }
            }
            for ((book, candidate) in remote) {
                db.offline().mergeBook(book)
                if (candidate != null) {
                    val local = db.offline().progress(activeUser, book.id)
                    if (candidate.updatedAt > maxOf(local?.updatedAt ?: 0, local?.remoteUpdatedAt ?: 0)) {
                        if (local == null) db.offline().insertProgress(LocalProgress(activeUser, book.id, "", 0.0, "", 0, false, candidate.position, candidate.percent, candidate.deviceId, candidate.updatedAt))
                        else db.offline().storeRemoteCandidate(activeUser, book.id, local.updatedAt, candidate.position, candidate.percent, candidate.deviceId, candidate.updatedAt)
                    }
                }
            }
            if (remote.isEmpty()) db.offline().removeAllUndownloaded() else db.offline().removeMissing(remote.map { it.first.id })
            OfflineStore.scheduleSync(getApplication())
        } catch (e: Exception) { message = e.message ?: "Unable to load library" } finally { loading = false }
    } }
    suspend fun loadCover(book: LibraryBook): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val cached = db.offline().book(book.id)?.coverPath?.let(::File)?.takeIf { it.isFile }
            val file = cached ?: if (book.remote) {
                val dir = File(getApplication<Application>().filesDir, "covers").apply { mkdirs() }
                val temp = File.createTempFile("cover", ".tmp", dir)
                try {
                    client.newCall(request("/api/v1/books/${book.id}/cover").build()).execute().use { response ->
                        if (!response.isSuccessful) { temp.delete(); return@withContext null }
                        copyLimited(response.body?.byteStream() ?: error("Empty cover"), temp, 8L * 1024 * 1024)
                    }
                    val destination = File(dir, "${book.id}.jpg")
                    check(temp.renameTo(destination)) { "Could not save cover" }
                    db.offline().setCover(book.id, destination.absolutePath)
                    destination
                } catch (e: Exception) { temp.delete(); throw e }
            } else return@withContext null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..20000) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 900) sample *= 2
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
    }
    private fun copyLimited(input: java.io.InputStream, target: File, limit: Long) {
        try {
            input.use { source -> target.outputStream().use { out ->
                val buffer = ByteArray(32768); var count = 0L
                while (true) { val n = source.read(buffer); if (n < 0) break; count += n; require(count <= limit) { "File is too large" }; out.write(buffer, 0, n) }
            } }
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun importBook(uri: Uri) { viewModelScope.launch {
        loading = true
        try {
            val book = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Book.epub"
                val format = name.substringAfterLast('.', "").lowercase()
                require(format in setOf("epub", "mobi", "pdf", "cbz")) { "Choose an EPUB, MOBI, PDF or CBZ file" }
                val dir = File(getApplication<Application>().filesDir, "books").apply { mkdirs() }
                val temp = File.createTempFile("import", ".tmp", dir)
                copyLimited(resolver.openInputStream(uri) ?: error("Cannot open file"), temp, 512L * 1024 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                temp.inputStream().use { input -> val b = ByteArray(32768); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                val dest = File(dir, "$id.$format"); if (!temp.renameTo(dest)) { temp.delete(); error("Could not save book") }
                LibraryBook(id, name.substringBeforeLast('.'), format, dest.absolutePath)
            }
            saveLocal(book)
        } catch (e: Exception) { message = e.message ?: "Import failed" } finally { loading = false }
    } }
    fun importFont(uri: Uri) { viewModelScope.launch {
        try {
            val dest = withContext(Dispatchers.IO) {
                val dir = getApplication<Application>().filesDir
                val temp = File.createTempFile("font", ".tmp", dir)
                try {
                    copyLimited(getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Cannot open font"), temp, 20L * 1024 * 1024)
                    Typeface.createFromFile(temp) // Reject invalid font files before replacing the current font.
                    val dest = File(dir, "reader-font"); check(temp.renameTo(dest)) { "Could not save font" }; dest
                } catch (e: Exception) { temp.delete(); throw e }
            }
            fontPath = dest.absolutePath; prefs.edit().putString("fontPath", fontPath).apply()
        } catch (e: Exception) { message = e.message ?: "Font import failed" }
    } }
    fun resetFont() { fontPath = ""; prefs.edit().remove("fontPath").apply() }
    fun download(book: LibraryBook) {
        if (!book.remote) return
        viewModelScope.launch {
            val stored = db.offline().book(book.id) ?: return@launch
            if (stored.localPath != null && File(stored.localPath).isFile) return@launch
            db.offline().setDownload(book.id, stored.localPath, "queued", stored.downloadedBytes, stored.etag)
            OfflineStore.download(getApplication(), book.id)
        }
    }
    fun downloadAll() { library.filter { it.remote && it.localPath == null }.forEach(::download) }
    fun removeDownload(book: LibraryBook) { viewModelScope.launch {
        androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("readcore-download-${book.id}")
        withContext(Dispatchers.IO) {
            book.localPath?.let { File(it).delete() }
            File(getApplication<Application>().filesDir, "books/${book.id}.part").delete()
        }
        db.offline().setDownload(book.id, null, "none", 0, null)
        if (!book.remote) db.offline().deleteImported(book.id)
    } }
    fun flushSync() { OfflineStore.scheduleSync(getApplication()) }
    private fun position(content: OpenBook, format: String, page: Int): String = when {
        format == "epub" && content is OpenBook.Text -> "epubcfi(/6/${(content.spineIndices[page] + 1) * 2}!/4)"
        else -> "$format:$page"
    }
    private fun pageOf(content: OpenBook, format: String, position: String): Int {
        val cfi = Regex("^epubcfi\\(/6/(\\d+)(?:\\[[^]]*])?!.*\\)$").matchEntire(position)
        if (format == "epub" && content is OpenBook.Text && cfi != null) {
            val spine = (cfi.groupValues[1].toIntOrNull() ?: 2) / 2 - 1
            return content.spineIndices.indexOf(spine).coerceAtLeast(0)
        }
        return position.substringAfterLast(':').toIntOrNull() ?: 0
    }
    fun open(book: LibraryBook, onOpened: () -> Unit) { viewModelScope.launch {
        if (book.localPath == null) { download(book); message = "Downloading ${book.title}; open it when the download finishes"; return@launch }
        loading = true
        try {
            val parsed = withContext(Dispatchers.IO) { ReaderCore.open(File(book.localPath), book.format) }
            require(parsed.count > 0) { "Book has no pages" }
            val local = db.offline().progress(userId, book.id)
            current = book; opened = parsed
            chapter = (local?.let { pageOf(parsed, book.format, it.position) } ?: prefs.getInt("chapter_${book.id}", 0)).coerceIn(0, parsed.count - 1)
            if (book.remote && prefs.getBoolean("pending_${book.id}", false) && (local == null || local.position.startsWith("legacy:"))) {
                val percent = if (parsed.count <= 1) 0.0 else chapter * 100.0 / (parsed.count - 1)
                db.offline().saveTurn(userId, book.id, position(parsed, book.format, chapter), percent, OfflineStore.device(getApplication()), true)
                prefs.edit().putBoolean("pending_${book.id}", false).apply()
                OfflineStore.scheduleSync(getApplication(), 2)
            }
            remoteOffer = null
            if (local?.remoteUpdatedAt != null && local.remotePosition != null && local.remotePercent != null && local.remoteDevice != null && local.remoteUpdatedAt > local.updatedAt) {
                val candidate = LocalProgress(userId, book.id, local.remotePosition, local.remotePercent, local.remoteDevice, local.remoteUpdatedAt, false)
                remoteOffer = RemoteOffer(candidate, "${pageOf(parsed, book.format, candidate.position) + 1}")
            }
            onOpened()
            if (book.remote) viewModelScope.launch { checkRemote(book, parsed) }
        } catch (e: Exception) { message = e.message ?: "Cannot open book" } finally { loading = false }
    } }
    private suspend fun checkRemote(book: LibraryBook, content: OpenBook) {
        val activeUser = userId
        try {
            val candidate = withContext(Dispatchers.IO) {
                client.newCall(request("/api/v1/sync/${Uri.encode(activeUser)}/${book.id}").build()).execute().use { response ->
                    if (response.code == 404) return@use null
                    check(response.isSuccessful) { "Sync unavailable" }
                    val json = JSONObject(response.body?.string() ?: return@use null)
                    LocalProgress(activeUser, book.id, json.getString("position"), json.getDouble("percent"), json.getString("device_id"), json.getLong("updated_at"), false)
                }
            } ?: return
            val local = db.offline().progress(activeUser, book.id)
            if (candidate.updatedAt > (local?.updatedAt ?: 0L) && current?.id == book.id && userId == activeUser) {
                remoteOffer = RemoteOffer(candidate, "${pageOf(content, book.format, candidate.position) + 1}")
            }
        } catch (_: Exception) { /* Offline reading remains available. */ }
    }
    fun resolveRemote(accept: Boolean) { viewModelScope.launch {
        val offer = remoteOffer ?: return@launch
        val book = current ?: return@launch
        val content = opened ?: return@launch
        if (accept && offer.progress.bookId == book.id && offer.progress.userId == userId && db.offline().acceptRemote(offer.progress)) {
            chapter = pageOf(content, book.format, offer.progress.position).coerceIn(0, content.count - 1)
            prefs.edit().putInt("chapter_${book.id}", chapter).apply()
        } else db.offline().clearCandidate(offer.progress.userId, offer.progress.bookId)
        remoteOffer = null
    } }
    fun changeChapter(value: Int) {
        val book = current ?: return; val content = opened ?: return
        val next = value.coerceIn(0, content.count - 1)
        if (next == chapter) return
        val previous = chapter
        viewModelScope.launch {
            val percent = if (content.count <= 1) 0.0 else next * 100.0 / (content.count - 1)
            db.offline().saveTurn(userId, book.id, position(content, book.format, next), percent, OfflineStore.device(getApplication()), book.remote)
            chapter = next
            prefs.edit().putInt("chapter_${book.id}", next).apply()
            if (next > previous) {
                val words = if (content is OpenBook.Text) content.chapters[previous].split(Regex("\\s+")).count { it.isNotBlank() }.toLong() else 0L
                record(book.id, 0, 1, words)
            }
            if (book.remote) OfflineStore.scheduleSync(getApplication(), 2)
        }
    }
    suspend fun syncProgress() { flushSync() }
    fun record(bookId: String, seconds: Long, pages: Long = 0, words: Long = 0) {
        if (seconds <= 0 && pages <= 0 && words <= 0) return
        viewModelScope.launch(Dispatchers.IO) { db.reading().record(bookId, LocalDate.now().toString(), seconds, pages, words) }
    }
    override fun onCleared() { super.onCleared() }
}
