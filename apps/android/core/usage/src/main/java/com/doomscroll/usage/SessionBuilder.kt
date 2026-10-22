package com.doomscroll.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class UsageEventType { RESUMED, PAUSED, STOPPED, SCREEN_OFF, SHUTDOWN }

data class UsageEvent(val timeMs: Long, val type: UsageEventType, val packageName: String? = null)

data class Session(val label: String, val startMs: Long, val endMs: Long, val isOngoing: Boolean = false) {
    val durationMs: Long get() = endMs - startMs
}

/** Turns raw usage events into per-label sessions. Pure Kotlin, no Android types. */
class SessionBuilder(
    private val labelFor: (String) -> String?,
    private val mergeGapMs: Long = 30_000,
) {
    fun build(events: List<UsageEvent>, nowMs: Long): List<Session> {
        val raw = mutableListOf<Session>()
        // Only one app is foreground at a time: the last resumed one.
        var openPkg: String? = null
        var openLabel = ""
        var openStart = 0L

        fun close(atMs: Long) {
            if (openPkg != null && atMs > openStart) raw += Session(openLabel, openStart, atMs)
            openPkg = null
        }

        for (e in events.sortedBy { it.timeMs }) when (e.type) {
            UsageEventType.RESUMED -> {
                if (e.packageName == openPkg) continue
                close(e.timeMs)
                val label = e.packageName?.let(labelFor) ?: continue
                openPkg = e.packageName
                openLabel = label
                openStart = e.timeMs
            }
            UsageEventType.PAUSED, UsageEventType.STOPPED -> if (e.packageName == openPkg) close(e.timeMs)
            UsageEventType.SCREEN_OFF, UsageEventType.SHUTDOWN -> close(e.timeMs)
        }
        if (openPkg != null && nowMs > openStart) raw += Session(openLabel, openStart, nowMs, isOngoing = true)

        val merged = mutableListOf<Session>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.label == s.label && s.startMs - last.endMs < mergeGapMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs), isOngoing = s.isOngoing)
            } else {
                merged += s
            }
        }
        return merged
    }
}

/** Splits a session at local midnights, for daily totals. */
fun Session.splitByDay(zone: ZoneId): List<Pair<LocalDate, Session>> {
    val out = mutableListOf<Pair<LocalDate, Session>>()
    var start = startMs
    while (start < endMs) {
        val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val end = minOf(endMs, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        out += date to copy(startMs = start, endMs = end)
        start = end
    }
    return out
}
