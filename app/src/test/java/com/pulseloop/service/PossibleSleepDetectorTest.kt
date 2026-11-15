package com.pulseloop.service

import com.pulseloop.service.PossibleSleepDetector.HrSample
import com.pulseloop.service.PossibleSleepDetector.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PossibleSleepDetectorTest {
    private val min = 60_000L
    private val hour = 60 * min
    /** 2026-11-14 00:00 UTC — a whole hour, so hour buckets line up with the epoch hour grid. */
    private val day = 1_794_960_000_000L
    private val t0 = day + 14 * hour        // 14:00
    private val baseline = 60.0              // threshold = 65 with the default 5 bpm margin

    /** A 5-minute HR series from [from], one value per slot. */
    private fun series(from: Long, vararg bpm: Int) = bpm.mapIndexed { i, v -> HrSample(from + i * 5 * min, v.toDouble()) }

    private fun awakeAround(core: List<HrSample>): List<HrSample> {
        val before = series(core.first().at - 30 * min, 80, 82, 78, 85, 80, 79)
        val after = series(core.last().at + 5 * min, 84, 88, 90, 86)
        return before + core + after
    }

    @Test
    fun `baseline is a low percentile and needs enough nights`() {
        assertNull(PossibleSleepDetector.baseline(List(59) { 55.0 }))
        // 70 % sleeping readings 55–64, 30 % artifacts at 110: the 30th percentile ignores the artifacts.
        val readings = List(70) { 55.0 + it % 10 } + List(30) { 110.0 }
        val b = PossibleSleepDetector.baseline(readings)!!
        assertTrue("baseline $b", b in 55.0..64.0)
    }

    @Test
    fun `quiet stretch with artifacts is a candidate`() {
        // 14:10–14:55: nine quiet slots, two artifact spikes, one isolated awake-looking reading.
        val core = series(t0 + 10 * min, 60, 62, 115, 58, 59, 70, 61, 112, 60, 59)
        val c = PossibleSleepDetector.detect(awakeAround(core), emptyList(), emptyMap(), emptyMap(), baseline)
        assertEquals(1, c.size)
        assertEquals(t0 + 10 * min, c[0].start)
        assertEquals(t0 + 60 * min, c[0].end)
        assertEquals(50, c[0].minutes)
        assertEquals(65, c[0].thresholdBpm)
    }

    @Test
    fun `too short is not suggested`() {
        val core = series(t0 + 10 * min, 60, 60, 60, 60, 60) // 25 min
        assertTrue(PossibleSleepDetector.detect(awakeAround(core), emptyList(), emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `minimum duration is configurable`() {
        val core = series(t0 + 10 * min, 60, 60, 60, 60, 60)
        val config = PossibleSleepDetector.Config(minMinutes = 20)
        assertEquals(1, PossibleSleepDetector.detect(awakeAround(core), emptyList(), emptyMap(), emptyMap(), baseline, config).size)
    }

    @Test
    fun `two consecutive awake readings split the run`() {
        val core = series(t0, 60, 60, 60, 72, 74, 60, 60, 60) // 15 min + 15 min halves
        assertTrue(PossibleSleepDetector.detect(awakeAround(core), emptyList(), emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `mostly artifacts is not sleep`() {
        val core = series(t0, 60, 110, 112, 60, 115, 118, 60, 111, 113, 60)
        assertTrue(PossibleSleepDetector.detect(awakeAround(core), emptyList(), emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `a gap in the ring data ends the run`() {
        val a = series(t0, 60, 60, 60, 60)
        val b = series(t0 + 40 * min, 60, 60, 60, 60) // 25 min hole
        assertTrue(PossibleSleepDetector.detect(a + b, emptyList(), emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `readings inside a stored session are not suggested again`() {
        val core = series(t0 + 10 * min, 60, 60, 60, 60, 60, 60, 60, 60)
        val sleeps = listOf(Interval(t0, t0 + 2 * hour))
        assertTrue(PossibleSleepDetector.detect(awakeAround(core), sleeps, emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `a stretch touching a stored session is dropped`() {
        // Quiet 21:00–21:40, the ring's night starts 21:38: the run's last slot reaches into it.
        val start = day + 21 * hour
        val core = series(start, 60, 60, 60, 60, 60, 60, 60, 60)
        val sleeps = listOf(Interval(start + 38 * min, start + 9 * hour))
        assertTrue(PossibleSleepDetector.detect(core, sleeps, emptyMap(), emptyMap(), baseline).isEmpty())
    }

    @Test
    fun `steps in a fully covered hour veto`() {
        val core = series(t0, *IntArray(13) { 60 }) // 14:00–15:05
        assertTrue(PossibleSleepDetector.detect(core, emptyList(), mapOf(t0 to 400), emptyMap(), baseline).isEmpty())
        assertEquals(1, PossibleSleepDetector.detect(core, emptyList(), mapOf(t0 to 20), emptyMap(), baseline).size)
    }

    @Test
    fun `steps in the uncovered part of an hour are allowed`() {
        // Asleep 14:20–15:00; 300 steps in hour 14 fit in the 20 awake minutes.
        val core = series(t0 + 20 * min, *IntArray(8) { 60 })
        assertEquals(1, PossibleSleepDetector.detect(core, emptyList(), mapOf(t0 to 300), emptyMap(), baseline).size)
    }

    @Test
    fun `screen on vetoes`() {
        val core = series(t0, *IntArray(12) { 60 }) // 14:00–15:00
        assertTrue(PossibleSleepDetector.detect(core, emptyList(), emptyMap(), mapOf(t0 to 30.0), baseline).isEmpty())
        assertEquals(1, PossibleSleepDetector.detect(core, emptyList(), emptyMap(), mapOf(t0 to 1.0), baseline).size)
    }

    @Test
    fun `quietPhone checks every hour touched`() {
        val start = t0 + 40 * min
        val end = t0 + 100 * min // 14:40–15:40
        assertTrue(PossibleSleepDetector.quietPhone(start, end, mapOf(t0 to 2000), emptyMap()))
        assertFalse(PossibleSleepDetector.quietPhone(start, end, mapOf(t0 + hour to 2500), emptyMap()))
    }

    @Test
    fun `answered intervals suppress overlapping candidates`() {
        val c = PossibleSleepDetector.Candidate(t0, t0 + 45 * min, 60, 65)
        assertTrue(PossibleSleepDetector.alreadyAnswered(c, listOf(Interval(t0 + 5 * min, t0 + 40 * min))))
        assertFalse(PossibleSleepDetector.alreadyAnswered(c, listOf(Interval(t0 + 45 * min, t0 + 90 * min))))
    }
}
