package com.pulseloop.adaptive

import com.pulseloop.adaptive.AdaptiveInputs.LiveSteps
import com.pulseloop.adaptive.AdaptiveInputs.Reading
import com.pulseloop.adaptive.AdaptiveInputs.StepBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AdaptiveInputsTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val min = 60_000L
    private val now = LocalDateTime.of(2026, 10, 20, 15, 7).atZone(zone).toInstant().toEpochMilli()
    private val quarter = now - (now % (15 * min))
    private val synced = now

    @Test
    fun `median of odd and even lists`() {
        assertEquals(3.0, AdaptiveInputs.median(listOf(5.0, 1.0, 3.0))!!, 0.0)
        assertEquals(2.5, AdaptiveInputs.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.0)
        assertNull(AdaptiveInputs.median(emptyList()))
    }

    @Test
    fun `bucket steps count any overlapping bucket in full`() {
        val buckets = listOf(StepBucket(quarter - 15 * min, 40), StepBucket(quarter, 3))
        // previous 2 min fall in the current bucket only
        assertEquals(3, AdaptiveInputs.stepsInWindow(buckets, emptyList(), now - 2 * min, now, synced))
        // previous 10 min overlap both buckets
        assertEquals(43, AdaptiveInputs.stepsInWindow(buckets, emptyList(), now - 10 * min, now, synced))
        // no rows over a synced span = no steps
        assertEquals(0, AdaptiveInputs.stepsInWindow(emptyList(), emptyList(), now - 10 * min, now, synced))
    }

    @Test
    fun `bucket windows past the last completed sync are unknown, not rest`() {
        val buckets = listOf(StepBucket(quarter - 15 * min, 40))
        // Last sync 10 minutes ago: the previous 2/10 minutes are not covered by any bucket yet.
        assertNull(AdaptiveInputs.stepsInWindow(buckets, emptyList(), now - 2 * min, now, now - 10 * min))
        assertNull(AdaptiveInputs.stepsInWindow(emptyList(), emptyList(), now - 10 * min, now, now - 10 * min))
        // Never synced: nothing can say.
        assertNull(AdaptiveInputs.stepsInWindow(emptyList(), emptyList(), now - 10 * min, now, null))
        // A sync a minute ago is close enough.
        assertEquals(0, AdaptiveInputs.stepsInWindow(emptyList(), emptyList(), now - 2 * min, now, now - min))
        // A window entirely before the sync stays known.
        assertEquals(40, AdaptiveInputs.stepsInWindow(buckets, emptyList(), now - 20 * min, now - 12 * min, now - 10 * min))
        // Live totals still answer while the monitor holds the link, whatever the sync time.
        val live = listOf(LiveSteps(now - 2 * min, 5000), LiveSteps(now, 5000))
        assertEquals(0, AdaptiveInputs.stepsInWindow(buckets, live, now - 2 * min, now, now - 60 * min))

        val stale = AdaptiveInputs.sample(
            now = now, hr = emptyList(), stress = emptyList(), hrv = emptyList(), buckets = emptyList(), live = emptyList(),
            stepsKnownUntil = now - 30 * min, battery = 80, lastSleepEnd = null, bedtimeActive = false, zone = zone,
        )
        assertNull(stale.stepsPrev2)
        assertNull(stale.stepsPrev10)
        val cfg = AdaptiveConfig()
        assertFalse(AdaptiveStateMachine.isRest(stale, cfg))
        assertFalse(AdaptiveStateMachine.isMoving(stale, cfg))
    }

    @Test
    fun `live cumulative totals beat buckets when they bracket the window`() {
        val buckets = listOf(StepBucket(quarter, 300))
        val live = listOf(LiveSteps(now - 10 * min, 5000), LiveSteps(now - 2 * min, 5004), LiveSteps(now, 5004))
        assertEquals(4, AdaptiveInputs.stepsInWindow(buckets, live, now - 10 * min, now, synced))
        assertEquals(0, AdaptiveInputs.stepsInWindow(buckets, live, now - 2 * min, now, synced))
        // live data that doesn't reach back far enough: fall back to buckets
        assertEquals(300, AdaptiveInputs.stepsInWindow(buckets, live.takeLast(1), now - 10 * min, now, synced))
    }

    @Test
    fun `resting HR median ignores samples taken while moving and needs 6`() {
        val hr = (1..8).map { Reading(now - it * 15 * min, 60.0 + it) }
        // A walk 65–50 min ago: the samples 60 and 45 min ago have it in their previous 10 min.
        val walking = listOf(StepBucket(now - 65 * min, 500))
        val median = AdaptiveInputs.restingHrMedian(hr, walking, emptyList(), now, synced)!!
        assertEquals(65.5, median, 0.0)   // 61, 62, 65, 66, 67, 68
        assertNull(AdaptiveInputs.restingHrMedian(hr.take(5), emptyList(), emptyList(), now, synced))
    }

    @Test
    fun `HRV baseline uses the last 6 resting readings and excludes the current one`() {
        val hrv = (0..9).map { Reading(now - it * 30 * min, if (it < 6) 40.0 else 80.0) }
        assertEquals(40.0, AdaptiveInputs.hrvMedian(hrv, emptyList(), emptyList(), now, synced)!!, 0.0)
        assertNull(AdaptiveInputs.hrvMedian(hrv.take(2), emptyList(), emptyList(), now, synced))
    }

    @Test
    fun `asleep heuristics`() {
        assertTrue(AdaptiveInputs.asleep(now, lastSleepEnd = now - 10 * min, bedtimeActive = false, stepsPrev30 = 50, zone = zone))
        assertTrue(AdaptiveInputs.asleep(now, null, bedtimeActive = true, stepsPrev30 = 50, zone = zone))
        assertFalse(AdaptiveInputs.asleep(now, null, false, 0, zone))   // 15:07, still
        val night = LocalDateTime.of(2026, 10, 20, 23, 40).atZone(zone).toInstant().toEpochMilli()
        assertTrue(AdaptiveInputs.asleep(night, null, false, 0, zone))
        assertFalse(AdaptiveInputs.asleep(night, null, false, 30, zone))
        assertFalse(AdaptiveInputs.asleep(night, null, false, null, zone))
    }

    @Test
    fun `sample takes only fresh readings`() {
        val s = AdaptiveInputs.sample(
            now = now,
            hr = listOf(Reading(now - 20 * min, 110.0)),
            stress = listOf(Reading(now - 20 * min, 75.0)),
            hrv = listOf(Reading(now - 40 * min, 30.0)),
            buckets = emptyList(), live = emptyList(), stepsKnownUntil = synced, battery = 55, lastSleepEnd = null, bedtimeActive = false, zone = zone,
        )
        assertNull(s.hr)          // 20 min old > 12 min
        assertEquals(75, s.stress)  // 20 min old ≤ 35 min
        assertNull(s.hrv)         // 40 min old
        assertEquals(0, s.stepsPrev10)
        assertEquals(55, s.batteryPercent)
    }
}
