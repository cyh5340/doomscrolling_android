package com.doomscroll.network

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.doomscroll.data.OutboxDao
import com.doomscroll.data.OutboxEvent
import com.doomscroll.data.UsageTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Row in public.events; mirrors shared/contracts/events.schema.json. */
@Serializable
private data class EventRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("user_id") val userId: String,
    val platform: String,
    val label: String,
    val type: String,
    @SerialName("bucket_minutes") val bucketMinutes: Int,
    @SerialName("local_date") val localDate: String,
    val timezone: String,
    @SerialName("occurred_at") val occurredAt: String,
)

class EventUploader @Inject constructor(
    private val supabase: SupabaseClient,
    private val outbox: OutboxDao,
    private val account: Account,
) {
    /** Sends pending outbox rows. The event id is the primary key, so a resend is a no-op on the server. */
    suspend fun upload() {
        val uid = account.awaitUserId() ?: return
        while (true) {
            val batch = outbox.pending().ifEmpty { return }
            supabase.from("events").upsert(batch.map { it.toRow(uid) }) {
                onConflict = "event_id"
                ignoreDuplicates = true
            }
            outbox.markSent(batch.map { it.eventId })
        }
    }

    private fun OutboxEvent.toRow(uid: String) =
        EventRow(eventId, uid, "android", label, type, bucketMinutes, localDate, timezone, occurredAt)
}

/** Every 15 min: refresh group rules, read usage into the outbox, upload it. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tracker: UsageTracker,
    private val uploader: EventUploader,
    private val groups: Groups,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Tracking must work offline: stale rules are fine (cached), only a failed upload retries.
        runCatching { groups.mine() }
        tracker.run()
        return try {
            uploader.upload()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
