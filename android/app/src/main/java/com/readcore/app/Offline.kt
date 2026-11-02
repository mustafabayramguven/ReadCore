package com.readcore.app

import android.content.Context
import android.os.Build
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity
data class CachedBook(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val size: Long,
    val remote: Boolean,
    val localPath: String?,
    val coverPath: String?,
    val downloadState: String,
    val downloadedBytes: Long,
    val etag: String?,
    val series: String? = null,
    val language: String? = null
)

@Entity(primaryKeys = ["userId", "bookId"])
data class LocalProgress(
    val userId: String,
    val bookId: String,
    val position: String,
    val percent: Double,
    val deviceId: String,
    val updatedAt: Long,
    val pending: Boolean,
    val remotePosition: String? = null,
    val remotePercent: Double? = null,
    val remoteDevice: String? = null,
    val remoteUpdatedAt: Long? = null
)

data class LibraryRow(@Embedded val book: CachedBook, val lastReadAt: Long, val percent: Double)

@Dao
abstract class OfflineDao {
    @Query("SELECT b.*, MAX(COALESCE(p.updatedAt, 0), COALESCE(p.remoteUpdatedAt, 0)) AS lastReadAt, CASE WHEN COALESCE(p.remoteUpdatedAt, 0) > COALESCE(p.updatedAt, 0) THEN COALESCE(p.remotePercent, 0) ELSE COALESCE(p.percent, 0) END AS percent FROM CachedBook b LEFT JOIN LocalProgress p ON p.bookId=b.id AND p.userId=:userId ORDER BY MAX(COALESCE(p.updatedAt, 0), COALESCE(p.remoteUpdatedAt, 0)) DESC, b.title COLLATE NOCASE")
    abstract fun observeBooks(userId: String): Flow<List<LibraryRow>>
    @Query("DELETE FROM CachedBook WHERE id=:id AND remote=0") abstract suspend fun deleteImported(id: String)
    @Query("SELECT * FROM CachedBook WHERE id = :id") abstract suspend fun book(id: String): CachedBook?
    @Query("SELECT * FROM CachedBook WHERE remote = 1") abstract suspend fun remoteBooks(): List<CachedBook>
    @Query("DELETE FROM CachedBook WHERE remote=1 AND localPath IS NULL AND id NOT IN (:ids)") abstract suspend fun removeMissing(ids: List<String>)
    @Query("DELETE FROM CachedBook WHERE remote=1 AND localPath IS NULL") abstract suspend fun removeAllUndownloaded()
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertBook(book: CachedBook): Long
    @Query("UPDATE CachedBook SET title=:title, author=:author, format=:format, size=:size, remote=:remote, series=:series, language=:language WHERE id=:id")
    abstract suspend fun updateMetadata(id: String, title: String, author: String, format: String, size: Long, remote: Boolean, series: String?, language: String?)
    @Transaction open suspend fun mergeBook(book: CachedBook) {
        if (insertBook(book) == -1L) updateMetadata(book.id, book.title, book.author, book.format, book.size, book.remote, book.series, book.language)
    }
    @Query("UPDATE CachedBook SET localPath=:path, downloadState=:state, downloadedBytes=:bytes, etag=:etag WHERE id=:id")
    abstract suspend fun setDownload(id: String, path: String?, state: String, bytes: Long, etag: String?)
    @Query("UPDATE CachedBook SET coverPath=:path WHERE id=:id") abstract suspend fun setCover(id: String, path: String?)
    @Query("SELECT * FROM LocalProgress WHERE userId=:userId AND bookId=:bookId") abstract suspend fun progress(userId: String, bookId: String): LocalProgress?
    @Query("SELECT * FROM LocalProgress WHERE pending=1") abstract suspend fun pending(): List<LocalProgress>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putProgress(progress: LocalProgress)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertProgress(progress: LocalProgress): Long
    @Transaction open suspend fun acceptRemote(remote: LocalProgress): Boolean {
        val current = progress(remote.userId, remote.bookId)
        if (current != null && current.updatedAt >= remote.updatedAt) return false
        putProgress(remote)
        return true
    }
    @Transaction open suspend fun saveTurn(userId: String, bookId: String, position: String, percent: Double, deviceId: String, remote: Boolean): LocalProgress {
        val old = progress(userId, bookId)
        val timestamp = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0L) + 1)
        val next = LocalProgress(userId, bookId, position, percent, deviceId, timestamp, pending = remote)
        putProgress(next)
        return next
    }
    @Query("UPDATE LocalProgress SET pending=0 WHERE userId=:userId AND bookId=:bookId AND updatedAt=:updatedAt")
    abstract suspend fun markSynced(userId: String, bookId: String, updatedAt: Long)
    @Query("UPDATE LocalProgress SET pending=0, remotePosition=:position, remotePercent=:percent, remoteDevice=:device, remoteUpdatedAt=:updatedAt WHERE userId=:userId AND bookId=:bookId AND updatedAt=:localUpdatedAt")
    abstract suspend fun storeRemoteCandidate(userId: String, bookId: String, localUpdatedAt: Long, position: String, percent: Double, device: String, updatedAt: Long)
    @Query("UPDATE LocalProgress SET remotePosition=NULL, remotePercent=NULL, remoteDevice=NULL, remoteUpdatedAt=NULL WHERE userId=:userId AND bookId=:bookId")
    abstract suspend fun clearCandidate(userId: String, bookId: String)
}

object OfflineStore {
    @Volatile private var instance: ReadDatabase? = null
    val migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS CachedBook (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, format TEXT NOT NULL, size INTEGER NOT NULL, remote INTEGER NOT NULL, localPath TEXT, coverPath TEXT, downloadState TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, etag TEXT, series TEXT, language TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS LocalProgress (userId TEXT NOT NULL, bookId TEXT NOT NULL, position TEXT NOT NULL, percent REAL NOT NULL, deviceId TEXT NOT NULL, updatedAt INTEGER NOT NULL, pending INTEGER NOT NULL, remotePosition TEXT, remotePercent REAL, remoteDevice TEXT, remoteUpdatedAt INTEGER, PRIMARY KEY(userId, bookId))")
        }
    }
    fun db(context: Context): ReadDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, ReadDatabase::class.java, "reading.db").addMigrations(migration).build().also { instance = it }
    }
    fun user(context: Context): String = context.getSharedPreferences("readcore", Context.MODE_PRIVATE).getString("userId", "default") ?: "default"
    fun device(context: Context): String {
        val prefs = context.getSharedPreferences("readcore", Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
        return "${Build.MODEL.take(48)}-${id.take(8)}"
    }
    fun scheduleSync(context: Context, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("readcore-sync", ExistingWorkPolicy.REPLACE, request)
    }
    fun download(context: Context, bookId: String) {
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(workDataOf("bookId" to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("readcore-download-$bookId", ExistingWorkPolicy.KEEP, request)
    }
}
