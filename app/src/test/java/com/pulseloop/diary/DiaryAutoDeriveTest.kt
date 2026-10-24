package com.pulseloop.diary

import com.pulseloop.data.entity.ActivityTransitionEntity
import com.pulseloop.data.entity.PhoneUsageHourlyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiaryAutoDeriveTest {
    private val min = 60_000L
    private fun t(at: Long, a: String, tr: String) = ActivityTransitionEntity(timestamp = at, activityType = a, transition = tr)

    @Test
    fun `segments pair enter with exit or the next enter`() {
        val segs = DiaryAutoDerive.segments(
            listOf(t(0, "WALKING", "ENTER"), t(10 * min, "RUNNING", "ENTER"), t(40 * min, "RUNNING", "EXIT"), t(50 * min, "STILL", "ENTER")),
            until = 60 * min,
        )
        assertEquals(listOf("WALKING", "RUNNING", "STILL"), segs.map { it.activity })
        assertEquals(10 * min, segs[0].end)
        assertEquals(40 * min, segs[1].end)
        assertEquals(60 * min, segs[2].end)
    }

    @Test
    fun `sport minutes merge recognised activity with workouts`() {
        val transitions = listOf(t(0, "RUNNING", "ENTER"), t(30 * min, "RUNNING", "EXIT"), t(100 * min, "ON_BICYCLE", "ENTER"), t(110 * min, "ON_BICYCLE", "EXIT"))
        val workout = listOf(20 * min to 50 * min)   // overlaps the run by 10 min
        assertEquals(60, DiaryAutoDerive.sportMinutes(transitions, workout, 0, 200 * min))
        assertEquals(0, DiaryAutoDerive.sportMinutes(listOf(t(0, "WALKING", "ENTER")), emptyList(), 0, 60 * min))
        assertEquals(15, DiaryAutoDerive.sportMinutes(transitions, emptyList(), 15 * min, 60 * min))   // clipped
    }

    @Test
    fun `screens in bed from the last screen-off before sleep onset`() {
        val sleep = 1_000 * min
        val usage = listOf(
            PhoneUsageHourlyEntity(id = "a", hourStart = 0, screenOnMinutes = 5.0, lastScreenOffAt = sleep - 20 * min),
            PhoneUsageHourlyEntity(id = "b", hourStart = 0, screenOnMinutes = 5.0, lastScreenOffAt = sleep + 10 * min),
        )
        val last = DiaryAutoDerive.lastUseBefore(usage, sleep)
        assertEquals(sleep - 20 * min, last)
        assertEquals(true, DiaryAutoDerive.screensInBed(last, sleep))
        assertEquals(false, DiaryAutoDerive.screensInBed(sleep - 90 * min, sleep))
        assertNull(DiaryAutoDerive.screensInBed(null, sleep))
    }
}
