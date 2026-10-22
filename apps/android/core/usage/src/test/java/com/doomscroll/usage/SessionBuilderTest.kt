package com.doomscroll.usage

import com.doomscroll.usage.UsageEventType.PAUSED
import com.doomscroll.usage.UsageEventType.RESUMED
import com.doomscroll.usage.UsageEventType.SCREEN_OFF
import com.doomscroll.usage.UsageEventType.SHUTDOWN
import com.doomscroll.usage.UsageEventType.STOPPED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

const val TIKTOK = "com.zhiliaoapp.musically"
const val INSTA = "com.instagram.android"
const val YT = "com.google.android.youtube"
const val CHROME = "com.android.chrome"
val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
val LABELS = mapOf(TIKTOK to "social", INSTA to "social", YT to "video")

/** Epoch ms for a local time on 2026-10-18 (or [day]). */
fun t(h: Int, m: Int = 0, s: Int = 0, day: Int = 18): Long =
    LocalDateTime.of(2026, 10, day, h, m, s).atZone(ZONE).toInstant().toEpochMilli()

fun ev(time: Long, type: UsageEventType, pkg: String? = null) = UsageEvent(time, type, pkg)

class SessionBuilderTest {
    private val builder = SessionBuilder(LABELS::get)
    private val now = t(23)

    @Test
    fun `resume then pause makes one session`() {
        val s = builder.build(listOf(ev(t(10), RESUMED, TIKTOK), ev(t(10, 20), PAUSED, TIKTOK)), now)
        assertEquals(listOf(Session("social", t(10), t(10, 20))), s)
    }

    @Test
    fun `untracked apps are ignored`() {
        val s = builder.build(listOf(ev(t(10), RESUMED, CHROME), ev(t(10, 20), PAUSED, CHROME)), now)
        assertEquals(emptyList<Session>(), s)
    }

    @Test
    fun `stop closes a session`() {
        val s = builder.build(listOf(ev(t(10), RESUMED, TIKTOK), ev(t(10, 5), STOPPED, TIKTOK)), now)
        assertEquals(listOf(Session("social", t(10), t(10, 5))), s)
    }

    @Test
    fun `screen off and shutdown close a session`() {
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 5), SCREEN_OFF), ev(t(10, 6), PAUSED, TIKTOK),
                ev(t(11), RESUMED, YT), ev(t(11, 30), SHUTDOWN),
            ),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 5)), Session("video", t(11), t(11, 30))), s)
    }

    @Test
    fun `overlapping resumes - next app resuming closes the previous one`() {
        // No pause between apps (e.g. multi-window), then a late pause for the first app.
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), RESUMED, YT),
                ev(t(10, 11), PAUSED, TIKTOK), ev(t(10, 20), PAUSED, YT),
            ),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 10)), Session("video", t(10, 10), t(10, 20))), s)
    }

    @Test
    fun `untracked app resuming closes a tracked session`() {
        val s = builder.build(listOf(ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), RESUMED, CHROME)), now)
        assertEquals(listOf(Session("social", t(10), t(10, 10))), s)
    }

    @Test
    fun `duplicate resume of the same app keeps the original start`() {
        val s = builder.build(
            listOf(ev(t(10), RESUMED, TIKTOK), ev(t(10, 5), RESUMED, TIKTOK), ev(t(10, 10), PAUSED, TIKTOK)),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 10))), s)
    }

    @Test
    fun `events are sorted before processing`() {
        val s = builder.build(listOf(ev(t(10, 20), PAUSED, TIKTOK), ev(t(10), RESUMED, TIKTOK)), now)
        assertEquals(listOf(Session("social", t(10), t(10, 20))), s)
    }

    @Test
    fun `short gap between same label merges, even across apps`() {
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), PAUSED, TIKTOK),
                ev(t(10, 10, 20), RESUMED, INSTA), ev(t(10, 30), PAUSED, INSTA),
            ),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 30))), s)
    }

    @Test
    fun `gap at or above merge threshold splits`() {
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), PAUSED, TIKTOK),
                ev(t(10, 10, 30), RESUMED, TIKTOK), ev(t(10, 30), PAUSED, TIKTOK),
            ),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 10)), Session("social", t(10, 10, 30), t(10, 30))), s)
    }

    @Test
    fun `short gap between different labels does not merge`() {
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), PAUSED, TIKTOK),
                ev(t(10, 10, 5), RESUMED, YT), ev(t(10, 30), PAUSED, YT),
            ),
            now,
        )
        assertEquals(2, s.size)
    }

    @Test
    fun `brief notification shade or app switch does not break a session`() {
        val s = builder.build(
            listOf(
                ev(t(10), RESUMED, TIKTOK), ev(t(10, 10), RESUMED, CHROME),
                ev(t(10, 10, 10), RESUMED, TIKTOK), ev(t(10, 40), PAUSED, TIKTOK),
            ),
            now,
        )
        assertEquals(listOf(Session("social", t(10), t(10, 40))), s)
    }

    @Test
    fun `open session counts up to now and is ongoing`() {
        val s = builder.build(listOf(ev(t(22), RESUMED, TIKTOK)), now)
        assertEquals(listOf(Session("social", t(22), now, isOngoing = true)), s)
    }

    @Test
    fun `ongoing session merged onto a finished one stays ongoing`() {
        val s = builder.build(
            listOf(ev(t(22), RESUMED, TIKTOK), ev(t(22, 10), PAUSED, TIKTOK), ev(t(22, 10, 5), RESUMED, INSTA)),
            now,
        )
        assertEquals(listOf(Session("social", t(22), now, isOngoing = true)), s)
    }

    @Test
    fun `midnight split keeps duration`() {
        val session = Session("social", t(23, 30), t(0, 45, day = 19))
        val parts = session.splitByDay(ZONE)
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 18) to Session("social", t(23, 30), t(0, day = 19)),
                LocalDate.of(2026, 10, 19) to Session("social", t(0, day = 19), t(0, 45, day = 19)),
            ),
            parts,
        )
    }
}
