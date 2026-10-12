package com.pulseloop.service

import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.settings.ZenWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The gap between two ring records of one night (issues #111, #105): how long it is, how it is
 * drawn, and when an hour-plus one still belongs to the night.
 */
class SleepGapsTest {

    private val utc = ZoneOffset.UTC

    /** Epoch millis for an Oct 11 2026 UTC wall-clock time (day offset for times after midnight). */
    private fun at(hour: Int, minute: Int, second: Int = 0, dayOffset: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 11 + dayOffset, hour, minute, second, 0, utc).toInstant().toEpochMilli()

    private fun block(
        startAt: Long,
        minutes: Int,
        recordStartAt: Long,
        sessionStart: Long,
        stage: SleepStage = SleepStage.LIGHT,
    ) = SleepStageBlockEntity(
        id = "b$startAt",
        sessionId = "s",
        startAt = startAt,
        startMinute = ((startAt - sessionStart) / 60_000L).toInt(),
        durationMinutes = minutes,
        stageRaw = stage.name,
        recordStartAt = recordStartAt,
    )

    // ── #111 point 2: the gap is rounded, not truncated ──────────────────────

    /**
     * The reporter's Oct 10–11 waking: the ring ended the first record at 07:08:53 and opened the
     * next at 07:11:00 — 2m07. Its start (00:30:14) plus whole minutes stores the end at 07:09:14,
     * leaving 1m46, which truncation turned into 1 and dropped under the 2-minute threshold.
     */
    @Test
    fun `a 2m07 waking is not lost to the stored end's rounding`() {
        val firstStart = at(0, 30, 14)
        val secondStart = at(7, 11, 0)
        val blocks = listOf(
            block(firstStart, 399, firstStart, firstStart),                 // ends 07:09:14
            block(secondStart, 60, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(2, betweenRecordAwakeMinutes(runs[0], runs[1]))
        assertEquals(2, awakeMinutes(blocks))
    }

    /** The ring's one-minute reopen (issue #63), stored 29 s early, is still just a seam. */
    @Test
    fun `the one-minute seam stays out even when the stored end is rounded down`() {
        val firstStart = at(0, 30, 29)
        val trueEnd = at(5, 57, 58)                                         // stored end: 05:57:29
        val secondStart = trueEnd + 60_000L
        val blocks = listOf(
            block(firstStart, 327, firstStart, firstStart),
            block(secondStart, 60, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(0, betweenRecordAwakeMinutes(runs[0], runs[1]))
    }

    /** The worst case: stored a full 30 s early, the seam measures 90 s — a tie, rounded down. */
    @Test
    fun `a seam measured at exactly 90 s is still not a waking`() {
        val firstStart = at(0, 30, 30)
        val secondStart = firstStart + 327 * 60_000L + 90_000L
        val blocks = listOf(
            block(firstStart, 327, firstStart, firstStart),
            block(secondStart, 60, secondStart, firstStart),
        )
        val runs = sleepRecordRuns(blocks)
        assertEquals(0, betweenRecordAwakeMinutes(runs[0], runs[1]))
    }

    // ── #111 point 1: the hypnogram draws the counted gap ────────────────────

    @Test
    fun `the hypnogram gets an awake block over a counted gap, and only for display`() {
        val firstStart = at(0, 0)
        val secondStart = at(1, 15)
        val blocks = listOf(
            block(firstStart, 60, firstStart, firstStart, SleepStage.DEEP),
            block(secondStart, 30, secondStart, firstStart),
        )
        val drawn = hypnogramBlocks(blocks)
        val gap = drawn - blocks.toSet()
        assertEquals(1, gap.size)
        assertEquals(SleepStage.AWAKE.name, gap.single().stageRaw)
        assertEquals(60, gap.single().startMinute)
        assertEquals(15, gap.single().durationMinutes)
        // The Awake figure counts the gap itself; it must not see the drawn block too.
        assertEquals(15, awakeMinutes(blocks))
    }

    @Test
    fun `the hypnogram draws nothing over a seam`() {
        val firstStart = at(0, 0)
        val secondStart = at(1, 1)
        val blocks = listOf(
            block(firstStart, 60, firstStart, firstStart),
            block(secondStart, 30, secondStart, firstStart),
        )
        assertEquals(blocks, hypnogramBlocks(blocks))
    }

    // ── #105: a gap inside the Bedtime window keeps the night whole ──────────

    /** The reporter's night: records 00:51–05:44 and 06:56–08:07, Bedtime 00:25–08:06. */
    private val bedtime = ZenWindow(start = at(0, 25), end = at(8, 6))
    private val gapStart = at(5, 44)
    private val gapEnd = at(6, 56)

    @Test
    fun `a gap inside one Bedtime window is bridged`() {
        val bridge = SleepGapBridge.forWindows(true, listOf(bedtime), 22 * 60, 7 * 60, utc)
        assertTrue(bridge.bridges(gapStart, gapEnd))
    }

    @Test
    fun `nothing is bridged while quiet hours are off`() {
        val bridge = SleepGapBridge.forWindows(false, listOf(bedtime), 22 * 60, 7 * 60, utc)
        assertFalse(bridge.bridges(gapStart, gapEnd))
    }

    @Test
    fun `a gap that runs past the end of Bedtime is not bridged`() {
        val earlyEnd = ZenWindow(start = at(0, 25), end = at(6, 0))
        val bridge = SleepGapBridge.forWindows(true, listOf(earlyEnd), 22 * 60, 9 * 60, utc)
        // The clock window (22:00–09:00) would cover it, but this night has a Bedtime record.
        assertFalse(bridge.bridges(gapStart, gapEnd))
    }

    @Test
    fun `without Bedtime windows the clock window decides`() {
        val covering = SleepGapBridge.forWindows(true, null, 22 * 60, 7 * 60, utc)
        assertTrue(covering.bridges(gapStart, gapEnd))
        val endsEarlier = SleepGapBridge.forWindows(true, null, 22 * 60, 6 * 60, utc)
        assertFalse(endsEarlier.bridges(gapStart, gapEnd))
    }

    @Test
    fun `a nap after the night is not bridged by the clock window`() {
        val bridge = SleepGapBridge.forWindows(true, null, 22 * 60, 7 * 60, utc)
        assertFalse(bridge.bridges(at(6, 30), at(14, 0)))
    }

    @Test
    fun `segmentation keeps a bridged night as one session and splits it otherwise`() {
        val first = at(0, 51)
        val second = at(6, 56)
        val blocks = listOf(
            block(first, 293, first, first),                                // 00:51–05:44
            block(second, 71, second, first),                               // 06:56–08:07
        )
        assertEquals(2, SleepSegmentation.segment(blocks).size)
        val bridge = SleepGapBridge.forWindows(true, listOf(bedtime), 22 * 60, 7 * 60, utc)
        val whole = SleepSegmentation.segment(blocks, bridge)
        assertEquals(1, whole.size)
        // The 72 minutes between the records then count as awake, as shorter gaps already do.
        assertEquals(72, awakeMinutes(whole.single()))
    }
}
