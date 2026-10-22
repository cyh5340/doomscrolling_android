package com.doomscroll.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class EventKind(val wire: String) { BUCKET_REACHED("bucket_reached"), SESSION_THRESHOLD("session_threshold") }

/** An event to report. [dedupeKey] is stable across runs, so the same crossing is never emitted twice. */
data class PendingEvent(
    val dedupeKey: String,
    val kind: EventKind,
    val label: String,
    val bucketMinutes: Int,
    val localDate: LocalDate,
    val occurredAtMs: Long,
)

class ThresholdEvaluator(private val zone: ZoneId, private val bucketMinutes: Int = 15) {
    private val bucketMs = bucketMinutes * 60_000L

    fun dailyTotals(sessions: List<Session>): Map<Pair<LocalDate, String>, Long> =
        sessions.flatMap { it.splitByDay(zone) }
            .groupingBy { (date, s) -> date to s.label }
            .fold(0L) { acc, (_, s) -> acc + s.durationMs }

    /**
     * Emits every bucket and session-limit crossing on or after [fromDate] whose key isn't in [alreadyEmitted].
     * Sessions are split at midnight for buckets but kept whole for session limits. Each group can have its own
     * session limit, so each distinct limit is checked separately; buckets are shared by all groups.
     */
    fun evaluate(
        sessions: List<Session>,
        sessionLimits: Collection<Int>,
        fromDate: LocalDate,
        alreadyEmitted: Set<String>,
    ): List<PendingEvent> {
        val out = mutableListOf<PendingEvent>()

        val totals = mutableMapOf<Pair<LocalDate, String>, Long>()
        for ((date, seg) in sessions.flatMap { it.splitByDay(zone) }.sortedBy { it.second.startMs }) {
            val key = date to seg.label
            val before = totals[key] ?: 0L
            val after = before + seg.durationMs
            totals[key] = after
            if (date < fromDate) continue
            var bucket = before / bucketMs + 1
            while (bucket * bucketMs <= after) {
                val minutes = (bucket * bucketMinutes).toInt()
                out += PendingEvent(
                    "bucket:$date:${seg.label}:$minutes", EventKind.BUCKET_REACHED, seg.label, minutes, date,
                    seg.startMs + (bucket * bucketMs - before),
                )
                bucket++
            }
        }

        for (limit in sessionLimits.toSortedSet()) {
            val limitMs = limit * 60_000L
            for (s in sessions) {
                if (s.durationMs < limitMs) continue
                val crossedAt = s.startMs + limitMs
                val date = Instant.ofEpochMilli(crossedAt).atZone(zone).toLocalDate()
                if (date < fromDate) continue
                out += PendingEvent(
                    "session:${s.label}:${s.startMs}:$limit", EventKind.SESSION_THRESHOLD, s.label, limit, date,
                    crossedAt,
                )
            }
        }

        return out.filter { it.dedupeKey !in alreadyEmitted }
    }
}
