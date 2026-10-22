package com.doomscroll.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * One row per event in shared/contracts/events.schema.json, minus userId/platform (added at upload).
 * The unique [dedupeKey] is the record of what was already emitted, so sent rows are kept, not deleted.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["dedupeKey"], unique = true)])
data class OutboxEvent(
    @PrimaryKey val eventId: String,
    val dedupeKey: String,
    val label: String,
    val type: String,
    val bucketMinutes: Int,
    val localDate: String,
    val timezone: String,
    val occurredAt: String,
    val status: String = STATUS_PENDING,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
    }
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(events: List<OutboxEvent>)

    // ponytail: loads every key ever emitted; prune rows older than a few days if this grows.
    @Query("SELECT dedupeKey FROM outbox")
    suspend fun keys(): List<String>

    @Query("SELECT * FROM outbox ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<OutboxEvent>>

    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY occurredAt LIMIT 500")
    suspend fun pending(): List<OutboxEvent>

    @Query("UPDATE outbox SET status = 'sent' WHERE eventId IN (:ids)")
    suspend fun markSent(ids: List<String>)

    /** ISO-8601 time of the oldest event still waiting to upload, or null. */
    @Query("SELECT MIN(occurredAt) FROM outbox WHERE status = 'pending'")
    fun oldestPending(): Flow<String?>
}

@Database(entities = [OutboxEvent::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun outbox(): OutboxDao
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun db(@ApplicationContext context: Context): AppDb =
        Room.databaseBuilder(context, AppDb::class.java, "doomscroll.db").build()

    @Provides
    fun outbox(db: AppDb): OutboxDao = db.outbox()
}
