package com.pulseloop.context

import com.pulseloop.data.entity.PhoneUsageHourlyEntity

/**
 * Folds UsageStatsManager screen events into per-hour aggregates. Pure, so it is tested without a
 * device: [UsageStatsCollector] only reads the events and hands them over.
 *
 * Events: [Kind.SCREEN_ON] / [Kind.SCREEN_OFF] (SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE, API 28+)
 * and [Kind.UNLOCK] (KEYGUARD_HIDDEN). An interval that is open at the start of the window counts
 * from the window start when [screenOnAtStart] says so; one still open at [to] counts up to [to].
 */
object UsageAggregation {
    enum class Kind { SCREEN_ON, SCREEN_OFF, UNLOCK }

    data class Event(val at: Long, val kind: Kind)

    const val HOUR_MS = 3_600_000L

    fun hourStart(t: Long, zoneOffsetMs: (Long) -> Long): Long {
        // Local hour boundary: shift into local time, truncate, shift back.
        val offset = zoneOffsetMs(t)
        return ((t + offset) / HOUR_MS) * HOUR_MS - offset
    }

    fun aggregate(
        events: List<Event>,
        from: Long,
        to: Long,
        screenOnAtStart: Boolean,
        now: Long,
        zoneOffsetMs: (Long) -> Long,
    ): List<PhoneUsageHourlyEntity> {
        val sorted = events.filter { it.at in from..to }.sortedBy { it.at }
        val onMs = mutableMapOf<Long, Long>()
        val unlocks = mutableMapOf<Long, Int>()
        val lastOff = mutableMapOf<Long, Long>()
        val hours = mutableSetOf<Long>()
        var h = hourStart(from, zoneOffsetMs)
        while (h < to) { hours += h; h += HOUR_MS }

        fun addOn(start: Long, end: Long) {
            var s = start
            while (s < end) {
                val hs = hourStart(s, zoneOffsetMs)
                val he = minOf(end, hs + HOUR_MS)
                onMs[hs] = (onMs[hs] ?: 0L) + (he - s)
                s = he
            }
        }

        var onSince: Long? = if (screenOnAtStart) from else null
        for (e in sorted) {
            when (e.kind) {
                Kind.SCREEN_ON -> if (onSince == null) onSince = e.at
                Kind.SCREEN_OFF -> {
                    onSince?.let { addOn(it, e.at) }
                    onSince = null
                    val hs = hourStart(e.at, zoneOffsetMs)
                    lastOff[hs] = maxOf(lastOff[hs] ?: 0L, e.at)
                }
                Kind.UNLOCK -> {
                    val hs = hourStart(e.at, zoneOffsetMs)
                    unlocks[hs] = (unlocks[hs] ?: 0) + 1
                }
            }
        }
        onSince?.let { addOn(it, to) }

        return hours.sorted().map { hs ->
            PhoneUsageHourlyEntity(
                id = PhoneUsageHourlyEntity.idFor(hs),
                hourStart = hs,
                screenOnMinutes = ((onMs[hs] ?: 0L) / 60_000.0).coerceAtMost(60.0),
                unlocks = unlocks[hs] ?: 0,
                lastScreenOffAt = lastOff[hs],
                updatedAt = now,
            )
        }
    }
}
