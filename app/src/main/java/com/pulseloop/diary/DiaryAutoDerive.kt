package com.pulseloop.diary

import com.pulseloop.data.entity.ActivityTransitionEntity
import com.pulseloop.data.entity.PhoneUsageHourlyEntity

/**
 * What the diary does not need to ask: sport from Activity Recognition and recorded workouts,
 * "schermi a letto" from the last screen-off before the night's sleep onset. Shown next to the
 * form as suggestions; the server derives the same from the synced data.
 */
object DiaryAutoDerive {
    private val SPORT = setOf("RUNNING", "ON_BICYCLE")
    const val SCREENS_IN_BED_WINDOW_MS = 30 * 60_000L

    data class Segment(val activity: String, val start: Long, val end: Long)

    /** ENTER → EXIT (or next ENTER, or [until]) segments, mirroring ringhub.places.activity_segments. */
    fun segments(transitions: List<ActivityTransitionEntity>, until: Long): List<Segment> {
        val out = mutableListOf<Segment>()
        var current: Pair<String, Long>? = null
        for (t in transitions.sortedBy { it.timestamp }) {
            if (t.transition == "ENTER") {
                current?.let { out += Segment(it.first, it.second, t.timestamp) }
                current = t.activityType to t.timestamp
            } else if (current != null && current.first == t.activityType) {
                out += Segment(current.first, current.second, t.timestamp)
                current = null
            }
        }
        current?.let { out += Segment(it.first, it.second, maxOf(until, it.second)) }
        return out
    }

    /** Sport minutes in [from, to): recognised running/cycling plus workouts, overlaps counted once. */
    fun sportMinutes(transitions: List<ActivityTransitionEntity>, workouts: List<Pair<Long, Long>>, from: Long, to: Long): Int {
        val intervals = (segments(transitions, to).filter { it.activity in SPORT }.map { it.start to it.end } + workouts)
            .map { maxOf(it.first, from) to minOf(it.second, to) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        var total = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((s, e) in intervals) {
            if (s > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = s; curEnd = e
            } else curEnd = maxOf(curEnd, e)
        }
        if (curEnd > curStart) total += curEnd - curStart
        return (total / 60_000L).toInt()
    }

    /** Last screen-off at or before [sleepStart] (within 6 h), or null. */
    fun lastUseBefore(usage: List<PhoneUsageHourlyEntity>, sleepStart: Long): Long? =
        usage.mapNotNull { it.lastScreenOffAt }.filter { it <= sleepStart && sleepStart - it <= 6 * 3_600_000L }.maxOrNull()

    fun screensInBed(lastUse: Long?, sleepStart: Long): Boolean? =
        lastUse?.let { sleepStart - it <= SCREENS_IN_BED_WINDOW_MS }
}
