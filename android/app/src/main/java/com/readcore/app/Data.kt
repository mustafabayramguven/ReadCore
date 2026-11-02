package com.readcore.app

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
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
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
@Database(entities = [ReadingDay::class], version = 1, exportSchema = false)
abstract class ReadDatabase : RoomDatabase() { abstract fun reading(): ReadingDao }

data class LibraryBook(val id: String, val title: String, val format: String, val localPath: String? = null, val remote: Boolean = false, val author: String = "")

class ReadModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("readcore", Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(app, "readcore-secrets", MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val client = OkHttpClient()
    private val progressMutex = Mutex()
    private val db = Room.databaseBuilder(app, ReadDatabase::class.java, "reading.db").build()
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
    private fun saveLocal(book: LibraryBook) {
        val list = (readLocal().filterNot { it.id == book.id } + book)
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("format", it.format).put("path", it.localPath).put("remote", it.remote).put("author", it.author)) }
        prefs.edit().putString("books", arr.toString()).apply()
        library = library.filterNot { it.id == book.id } + book
    }
    private fun request(path: String): Request.Builder {
        require(url.startsWith("https://")) { "Use an HTTPS server URL" }
        require(token.isNotBlank()) { "Enter your server token in Settings" }
        return Request.Builder().url(url + path).header("Authorization", "Bearer $token")
    }
    fun refresh() { viewModelScope.launch {
        loading = true
        try {
            val remote = withContext(Dispatchers.IO) {
                client.newCall(request("/opds").build()).execute().use { response ->
                    check(response.isSuccessful) { "Server returned ${response.code}" }
                    parseCatalog(response.body?.byteStream() ?: error("Empty catalog"))
                }
            }
            val local = readLocal(); library = remote.map { r -> r.copy(localPath = local.find { it.id == r.id }?.localPath) } + local.filter { b -> remote.none { it.id == b.id } }
        } catch (e: Exception) { message = e.message ?: "Unable to load library" } finally { loading = false }
    } }
    // ReadCore's acquisition feed uses stable IDs in its relative book links.
    // Arbitrary external acquisition URLs are deliberately not followed.
    private fun parseCatalog(input: java.io.InputStream): List<LibraryBook> {
        val xml = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(input, "UTF-8") }
        val books = mutableListOf<LibraryBook>()
        var inEntry = false
        var title = ""
        var author = ""
        var id: String? = null
        var format: String? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when {
                xml.eventType == XmlPullParser.START_TAG && xml.name == "entry" -> { inEntry = true; title = ""; author = ""; id = null; format = null }
                inEntry && xml.eventType == XmlPullParser.START_TAG && xml.name == "title" -> title = xml.nextText()
                inEntry && xml.eventType == XmlPullParser.START_TAG && xml.name == "name" -> author = xml.nextText()
                inEntry && xml.eventType == XmlPullParser.START_TAG && xml.name == "link" && xml.getAttributeValue(null, "rel") == "http://opds-spec.org/acquisition" -> {
                    val href = xml.getAttributeValue(null, "href") ?: ""
                    id = Regex("^/api/books/([a-f0-9]{64})/file$").matchEntire(href)?.groupValues?.get(1)
                    format = when (xml.getAttributeValue(null, "type")) {
                        "application/epub+zip" -> "epub"
                        "application/pdf" -> "pdf"
                        "application/x-mobipocket-ebook" -> "mobi"
                        "application/vnd.comicbook+zip" -> "cbz"
                        "application/vnd.comicbook-rar" -> "cbr"
                        else -> null
                    }
                }
                xml.eventType == XmlPullParser.END_TAG && xml.name == "entry" -> {
                    if (id != null && format != null) books.add(LibraryBook(id, title.ifBlank { "Untitled" }, format, remote = true, author = author))
                    inEntry = false
                }
            }
        }
        return books
    }
    suspend fun loadCover(book: LibraryBook): android.graphics.Bitmap? {
        if (!book.remote || book.format != "epub") return null
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request("/api/books/${book.id}/cover").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val input = response.body?.byteStream() ?: return@use null
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 8 * 1024 * 1024) return@use null; out.write(buffer, 0, n) }
                    val bytes = out.toByteArray()
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..20000) return@use null
                    var sample = 1
                    while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 900) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (_: Exception) { null }
        }
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
    fun open(book: LibraryBook, onOpened: () -> Unit) { viewModelScope.launch {
        loading = true
        try {
            var selected = book
            if (selected.localPath == null) {
                val downloaded = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().filesDir, "books").apply { mkdirs() }
                    val temp = File.createTempFile("download", ".tmp", dir)
                    try {
                        client.newCall(request("/api/books/${book.id}/file").build()).execute().use { response ->
                            check(response.isSuccessful) { "Server returned ${response.code}" }
                            copyLimited(response.body?.byteStream() ?: error("Empty download"), temp, 512L * 1024 * 1024)
                        }
                        val dest = File(dir, "${book.id}.${book.format}"); check(temp.renameTo(dest)) { "Could not save download" }; dest
                    } catch (e: Exception) { temp.delete(); throw e }
                }
                selected = book.copy(localPath = downloaded.absolutePath); saveLocal(selected)
            }
            val parsed = withContext(Dispatchers.IO) { ReaderCore.open(File(selected.localPath!!), selected.format) }
            require(parsed.count > 0) { "Book has no pages" }
            current = selected; opened = parsed
            chapter = prefs.getInt("chapter_${selected.id}", 0).coerceIn(0, parsed.count - 1)
            if (selected.remote) syncProgress()
            onOpened()
        } catch (e: Exception) { message = e.message ?: "Cannot open book" } finally { loading = false }
    } }
    fun changeChapter(value: Int) {
        val book = current ?: return; val content = opened ?: return
        val next = value.coerceIn(0, content.count - 1)
        if (next == chapter) return
        if (next > chapter) {
            val words = if (content is OpenBook.Text) content.chapters[chapter].split(Regex("\\s+")).count { it.isNotBlank() }.toLong() else 0L
            record(book.id, 0, 1, words)
        }
        chapter = next
        prefs.edit().putInt("chapter_${book.id}", next).putBoolean("pending_${book.id}", book.remote).apply()
        if (book.remote) viewModelScope.launch { syncProgress() }
    }
    suspend fun syncProgress() = progressMutex.withLock {
        val book = current ?: return@withLock
        if (!book.remote || opened == null) return@withLock
        try {
            withContext(Dispatchers.IO) {
                if (prefs.getBoolean("pending_${book.id}", false)) {
                    val position = prefs.getInt("chapter_${book.id}", 0)
                    val body = JSONObject().put("chapter", position).put("offset", 0.0).toString().toRequestBody("application/json".toMediaType())
                    client.newCall(request("/api/progress/${book.id}").put(body).build()).execute().use { check(it.isSuccessful) { "Sync failed" } }
                    // A newer local change must not be cleared by an older request.
                    if (prefs.getInt("chapter_${book.id}", 0) == position) prefs.edit().putBoolean("pending_${book.id}", false).apply()
                }
                client.newCall(request("/api/progress/${book.id}").build()).execute().use { response ->
                    if (response.isSuccessful && !prefs.getBoolean("pending_${book.id}", false)) {
                        val value = JSONObject(response.body?.string() ?: "{}").optInt("chapter", 0)
                        withContext(Dispatchers.Main) {
                            if (current?.id == book.id && !prefs.getBoolean("pending_${book.id}", false)) {
                                chapter = value.coerceIn(0, (opened?.count ?: 1) - 1)
                                prefs.edit().putInt("chapter_${book.id}", chapter).apply()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { /* Keep local progress queued for the next sync. */ }
    }
    fun record(bookId: String, seconds: Long, pages: Long = 0, words: Long = 0) {
        if (seconds <= 0 && pages <= 0 && words <= 0) return
        viewModelScope.launch(Dispatchers.IO) { db.reading().record(bookId, LocalDate.now().toString(), seconds, pages, words) }
    }
    override fun onCleared() { db.close(); super.onCleared() }
}
