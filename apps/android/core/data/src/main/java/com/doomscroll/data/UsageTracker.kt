package com.doomscroll.data

import com.doomscroll.usage.Session
import com.doomscroll.usage.SessionBuilder
import com.doomscroll.usage.ThresholdEvaluator
import com.doomscroll.usage.TrackedApps
import com.doomscroll.usage.UsageEventReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class UsageSnapshot(val sessions: List<Session>, val todayTotalsMs: Map<String, Long>, val ranAtMs: Long)

/** Reads usage since the cursor, evaluates thresholds, and writes new events to the outbox. */
@Singleton
class UsageTracker @Inject constructor(
    private val reader: UsageEventReader,
    private val trackedApps: TrackedApps,
    private val outbox: OutboxDao,
    private val settings: Settings,
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<UsageSnapshot?>(null)
    val snapshot: StateFlow<UsageSnapshot?> = _snapshot

    suspend fun run(nowMs: Long = System.currentTimeMillis()): Unit = mutex.withLock {
        if (!reader.hasAccess()) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cursor = settings.cursor()

        // Always re-read from the start of yesterday so sessions spanning midnight are whole and today's
        // totals are complete. A stale cursor (worker didn't run for days) reaches further back, up to the
        // ~7 days Android keeps.
        val startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val readFrom = maxOf(
            if (cursor == 0L) startOfYesterday else minOf(cursor, startOfYesterday),
            nowMs - TimeUnit.DAYS.toMillis(7),
        )
        // First run: don't backfill usage from before install.
        val fromDate = if (cursor == 0L) today else Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()

        val sessions = SessionBuilder(trackedApps::labelFor).build(reader.read(readFrom, nowMs), nowMs)
        val evaluator = ThresholdEvaluator(zone)
        val events = evaluator.evaluate(sessions, settings.sessionLimits(), fromDate, outbox.keys().toSet())
        outbox.insert(
            events.map {
                OutboxEvent(
                    eventId = UUID.randomUUID().toString(),
                    dedupeKey = it.dedupeKey,
                    label = it.label,
                    type = it.kind.wire,
                    bucketMinutes = it.bucketMinutes,
                    localDate = it.localDate.toString(),
                    timezone = zone.id,
                    occurredAt = Instant.ofEpochMilli(it.occurredAtMs).toString(),
                )
            },
        )
        settings.setCursor(nowMs)

        val todayTotals = evaluator.dailyTotals(sessions).filterKeys { it.first == today }.mapKeys { it.key.second }
        _snapshot.value = UsageSnapshot(sessions, todayTotals, nowMs)
    }
}
