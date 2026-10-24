package com.pulseloop.adaptive

import java.time.Instant
import java.time.ZoneId

/**
 * Builds an [AdaptiveSample] from stored readings. Pure: the Room reads live in
 * [AdaptiveController]; everything here is unit-tested.
 *
 * Step resolution is the hard part. Ring activity history arrives in 15-minute buckets, which
 * cannot express "0 steps in the previous 2 minutes"; while the ring link is held the app also
 * sees the ring's cumulative day count ([LiveSteps]), which can. So: live totals when they bracket
 * the window, otherwise every bucket that **overlaps** the window counts in full — conservative,
 * it can only make a window look less restful, never more.
 *
 * Buckets only speak for time the ring has already synced: "no row" means 0 steps up to the
 * last completed sync (`stepsKnownUntil`, the device's `lastFullSyncAt`) and *unknown* after it.
 * A window reaching past that point returns null, which [AdaptiveStateMachine.isRest] treats as
 * not-rest and [AdaptiveStateMachine.isMoving] as no evidence — so a stale bucket table can
 * neither fake rest nor end an escalation; the cap and the daily budget still bound it.
 */
object AdaptiveInputs {

    data class Reading(val at: Long, val value: Double)

    data class StepBucket(val start: Long, val steps: Int, val durationMs: Long = BUCKET_MS)

    /** The ring's cumulative step count for the day, as observed live, oldest first. */
    data class LiveSteps(val at: Long, val total: Int)

    const val BUCKET_MS = 15 * 60_000L
    private const val LIVE_BRACKET_SLACK_MS = 90_000L
    /** How far past the last completed sync a bucket window may reach and still count as known. */
    const val STEPS_SYNC_SLACK_MS = 90_000L
    const val REST_HR_WINDOW_MS = 3 * 3_600_000L
    const val REST_HR_MIN_SAMPLES = 6
    const val HRV_BASELINE_COUNT = 6
    const val HRV_BASELINE_MIN = 3
    const val HRV_LOOKBACK_MS = 48 * 3_600_000L
    /** Auto-logged HR every 5 min; a sample older than this is not "now". */
    const val HR_MAX_AGE_MS = 12 * 60_000L
    /** Auto-logged stress/HRV every 30 min. */
    const val SLOW_VITAL_MAX_AGE_MS = 35 * 60_000L
    /** A sleep record reaching this close to now means the ring still sees sleep. */
    const val SLEEP_RECENT_MS = 20 * 60_000L

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Steps in [from, to), or null when nothing can say (see the class KDoc for [stepsKnownUntil]). */
    fun stepsInWindow(buckets: List<StepBucket>, live: List<LiveSteps>, from: Long, to: Long, stepsKnownUntil: Long?): Int? {
        val before = live.lastOrNull { it.at <= from + LIVE_BRACKET_SLACK_MS && it.at >= from - LIVE_BRACKET_SLACK_MS * 2 }
        val after = live.lastOrNull { it.at <= to && it.at >= to - LIVE_BRACKET_SLACK_MS }
        if (before != null && after != null && after.at > before.at && after.total >= before.total) {
            return after.total - before.total
        }
        // Buckets: rows exist only for intervals with steps, so "no row" over a synced span is 0 —
        // but only a synced one: past the last completed sync nothing is known yet.
        if (stepsKnownUntil == null || to > stepsKnownUntil + STEPS_SYNC_SLACK_MS) return null
        return buckets.filter { it.start < to && it.start + it.durationMs > from }.sumOf { it.steps }
    }

    fun restingHrMedian(hr: List<Reading>, buckets: List<StepBucket>, live: List<LiveSteps>, now: Long, stepsKnownUntil: Long?): Double? {
        val resting = hr.filter { it.at in (now - REST_HR_WINDOW_MS)..now }
            .filter { (stepsInWindow(buckets, live, it.at - 10 * 60_000L, it.at, stepsKnownUntil) ?: Int.MAX_VALUE) <= 10 }
        if (resting.size < REST_HR_MIN_SAMPLES) return null
        return median(resting.map { it.value })
    }

    fun hrvMedian(hrv: List<Reading>, buckets: List<StepBucket>, live: List<LiveSteps>, now: Long, stepsKnownUntil: Long?, excludeAt: Long? = null): Double? {
        val resting = hrv.filter { it.at in (now - HRV_LOOKBACK_MS)..now && it.at != excludeAt }
            .filter { (stepsInWindow(buckets, live, it.at - 10 * 60_000L, it.at, stepsKnownUntil) ?: Int.MAX_VALUE) <= 10 }
            .sortedByDescending { it.at }
            .take(HRV_BASELINE_COUNT)
        if (resting.size < HRV_BASELINE_MIN) return null
        return median(resting.map { it.value })
    }

    fun latestFresh(readings: List<Reading>, now: Long, maxAgeMs: Long): Reading? =
        readings.filter { it.at <= now && now - it.at <= maxAgeMs }.maxByOrNull { it.at }

    /**
     * Asleep when any of: the ring's last sleep record runs up to now; the phone's Bedtime/DND
     * mode is on (when the user enabled that tracking); or it is night (23:00–07:00 local) and no
     * step was counted in the last 30 minutes.
     */
    fun asleep(
        now: Long,
        lastSleepEnd: Long?,
        bedtimeActive: Boolean,
        stepsPrev30: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (lastSleepEnd != null && now - lastSleepEnd in 0..SLEEP_RECENT_MS) return true
        if (bedtimeActive) return true
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val night = hour >= 23 || hour < 7
        return night && stepsPrev30 == 0
    }

    /** Everything the controller read, folded into one sample. */
    fun sample(
        now: Long,
        hr: List<Reading>,
        stress: List<Reading>,
        hrv: List<Reading>,
        buckets: List<StepBucket>,
        live: List<LiveSteps>,
        stepsKnownUntil: Long?,
        battery: Int?,
        lastSleepEnd: Long?,
        bedtimeActive: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AdaptiveSample {
        val latestHr = latestFresh(hr, now, HR_MAX_AGE_MS)
        val latestStress = latestFresh(stress, now, SLOW_VITAL_MAX_AGE_MS)
        val latestHrv = latestFresh(hrv, now, SLOW_VITAL_MAX_AGE_MS)
        val steps30 = stepsInWindow(buckets, live, now - 30 * 60_000L, now, stepsKnownUntil)
        return AdaptiveSample(
            at = now,
            hr = latestHr?.value?.toInt(),
            restingHrMedian = restingHrMedian(hr.filter { it.at != latestHr?.at }, buckets, live, now, stepsKnownUntil),
            stress = latestStress?.value?.toInt(),
            hrv = latestHrv?.value?.toInt(),
            hrvMedian = hrvMedian(hrv, buckets, live, now, stepsKnownUntil, excludeAt = latestHrv?.at),
            stepsPrev10 = stepsInWindow(buckets, live, now - 10 * 60_000L, now, stepsKnownUntil),
            stepsPrev2 = stepsInWindow(buckets, live, now - 2 * 60_000L, now, stepsKnownUntil),
            asleep = asleep(now, lastSleepEnd, bedtimeActive, steps30, zone),
            batteryPercent = battery,
        )
    }
}
