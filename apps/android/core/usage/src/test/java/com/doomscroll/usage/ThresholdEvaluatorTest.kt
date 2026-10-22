package com.doomscroll.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ThresholdEvaluatorTest {
    private val evaluator = ThresholdEvaluator(ZONE)
    private val oct18: LocalDate = LocalDate.of(2026, 10, 18)
    private val oct19: LocalDate = LocalDate.of(2026, 10, 19)

    private fun buckets(events: List<PendingEvent>) =
        events.filter { it.kind == EventKind.BUCKET_REACHED }.map { it.localDate to it.bucketMinutes }

    @Test
    fun `emits one event per crossed bucket, at the crossing time`() {
        val sessions = listOf(Session("social", t(10), t(10, 20)), Session("social", t(11), t(11, 40)))
        val events = evaluator.evaluate(sessions, emptyList(), oct18, emptySet())
        assertEquals(listOf(oct18 to 15, oct18 to 30, oct18 to 45, oct18 to 60), buckets(events))
        // 15 min reached inside the first session; 30 = 20 + 10 into the second.
        assertEquals(listOf(t(10, 15), t(11, 10), t(11, 25), t(11, 40)), events.map { it.occurredAtMs })
    }

    @Test
    fun `labels are bucketed separately`() {
        val sessions = listOf(Session("social", t(10), t(10, 20)), Session("video", t(11), t(11, 16)))
        val events = evaluator.evaluate(sessions, emptyList(), oct18, emptySet())
        assertEquals(listOf("social" to 15, "video" to 15), events.map { it.label to it.bucketMinutes })
    }

    @Test
    fun `under 15 minutes emits nothing`() {
        assertEquals(emptyList<PendingEvent>(), evaluator.evaluate(listOf(Session("social", t(10), t(10, 14, 59))), listOf(45), oct18, emptySet()))
    }

    @Test
    fun `midnight crossover splits buckets by day but session limit sees the whole session`() {
        // 23:40 -> 00:30: 20 min on the 18th, 30 min on the 19th, 50 min continuous.
        val sessions = listOf(Session("social", t(23, 40), t(0, 30, day = 19)))
        val events = evaluator.evaluate(sessions, listOf(45), oct18, emptySet())
        assertEquals(listOf(oct18 to 15, oct19 to 15, oct19 to 30), buckets(events))
        val limit = events.single { it.kind == EventKind.SESSION_THRESHOLD }
        assertEquals(oct19, limit.localDate)
        assertEquals(t(0, 25, day = 19), limit.occurredAtMs)
        assertEquals(45, limit.bucketMinutes)
    }

    @Test
    fun `daily totals split at midnight`() {
        val totals = evaluator.dailyTotals(listOf(Session("social", t(23, 40), t(0, 30, day = 19))))
        assertEquals(mapOf((oct18 to "social") to 20 * 60_000L, (oct19 to "social") to 30 * 60_000L), totals)
    }

    @Test
    fun `session limit fires once per session, only when reached`() {
        val sessions = listOf(
            Session("social", t(10), t(10, 44)),
            Session("social", t(12), t(13, 30)),
        )
        val limits = evaluator.evaluate(sessions, listOf(45), oct18, emptySet()).filter { it.kind == EventKind.SESSION_THRESHOLD }
        assertEquals(listOf(t(12, 45)), limits.map { it.occurredAtMs })
    }

    @Test
    fun `ongoing session crossing the limit fires`() {
        val sessions = listOf(Session("social", t(10), t(10, 50), isOngoing = true))
        assertTrue(evaluator.evaluate(sessions, listOf(45), oct18, emptySet()).any { it.kind == EventKind.SESSION_THRESHOLD })
    }

    @Test
    fun `days before fromDate are not emitted`() {
        val sessions = listOf(Session("social", t(10, day = 17), t(11, day = 17)), Session("social", t(10), t(10, 15)))
        assertEquals(listOf(oct18 to 15), buckets(evaluator.evaluate(sessions, listOf(45), oct18, emptySet())))
    }

    @Test
    fun `re-running with already emitted keys emits only new crossings`() {
        // Run 1: ongoing session at 10:20. Run 2: same session has grown to 10:50.
        val first = evaluator.evaluate(listOf(Session("social", t(10), t(10, 20), isOngoing = true)), listOf(45), oct18, emptySet())
        assertEquals(listOf(oct18 to 15), buckets(first))

        val emitted = first.map { it.dedupeKey }.toSet()
        val second = evaluator.evaluate(listOf(Session("social", t(10), t(10, 50))), listOf(45), oct18, emitted)
        assertEquals(listOf(oct18 to 30, oct18 to 45), buckets(second))
        assertEquals(1, second.count { it.kind == EventKind.SESSION_THRESHOLD })

        val third = evaluator.evaluate(listOf(Session("social", t(10), t(10, 50))), listOf(45), oct18, emitted + second.map { it.dedupeKey })
        assertEquals(emptyList<PendingEvent>(), third)
    }

    @Test
    fun `each group's session limit is checked separately, once each`() {
        val sessions = listOf(Session("social", t(10), t(11, 10)))
        val limits = evaluator.evaluate(sessions, listOf(60, 45, 45), oct18, emptySet())
            .filter { it.kind == EventKind.SESSION_THRESHOLD }
        assertEquals(listOf(45 to t(10, 45), 60 to t(11)), limits.map { it.bucketMinutes to it.occurredAtMs })
    }

    @Test
    fun `same input gives the same keys`() {
        val sessions = listOf(Session("social", t(10), t(11)))
        assertEquals(
            evaluator.evaluate(sessions, listOf(45), oct18, emptySet()).map { it.dedupeKey },
            evaluator.evaluate(sessions, listOf(45), oct18, emptySet()).map { it.dedupeKey },
        )
    }
}
