package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Issue #103: the app's own spot SpO₂ lands between the ring's readings, never on top of them. */
class ScheduledSpo2SlotsTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 21, month: Int = 10): ZonedDateTime =
        LocalDateTime.of(2026, month, day, h, m, s).atZone(zone)

    @Test
    fun `hourly lands at half past`() {
        // Just after the ring's own 10:00:42 reading — the reporter's timing.
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(10, 0, 42), 60))
        assertEquals(at(10, 30), ScheduledSpo2Slots.nextSlot(at(9, 30, 1), 60))
    }

    @Test
    fun `next slot is strictly after now`() {
        assertEquals(at(11, 30), ScheduledSpo2Slots.nextSlot(at(10, 30), 60))
    }

    @Test
    fun `half hour interval lands at quarter past and quarter to`() {
        assertEquals(at(10, 15), ScheduledSpo2Slots.nextSlot(at(10, 5), 30))
        assertEquals(at(10, 45), ScheduledSpo2Slots.nextSlot(at(10, 20), 30))
    }

    @Test
    fun `last slot of the day rolls over to the first of the next`() {
        assertEquals(at(0, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(23, 45), 60))
    }

    @Test
    fun `interval that does not divide the day restarts its grid at midnight`() {
        // 35 min: grid …23:20, 23:55, then 00:00; slots 23:37:30, then 00:17:30 the next day.
        assertEquals(at(23, 37, 30), ScheduledSpo2Slots.nextSlot(at(23, 20), 35))
        assertEquals(at(0, 17, 30, day = 22), ScheduledSpo2Slots.nextSlot(at(23, 40), 35))
        // 23:57 sits two minutes after the 23:55 reading.
        assertFalse(ScheduledSpo2Slots.isClear(at(23, 57), 35))
    }

    @Test
    fun `slots stay on the half hour across a DST change`() {
        // Europe/Paris falls back on 2026-10-25 (03:00 → 02:00): the slot after 01:40 is still 02:30.
        val slot = ScheduledSpo2Slots.nextSlot(at(1, 40, day = 25), 60)
        assertEquals(2, slot.hour)
        assertEquals(30, slot.minute)
    }

    @Test
    fun `a late run near the top of the hour is not clear`() {
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 30), 60))
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 2), 60))
        // Starting at 10:57 would still be measuring at 10:58:15 — too close to 11:00.
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 57), 60))
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 53), 60))
    }

    @Test
    fun `short intervals use a smaller margin`() {
        // 15 min: margin 3:45, so a run at 10:07:30 (the slot) is clear and 10:02 is not.
        assertTrue(ScheduledSpo2Slots.isClear(at(10, 7, 30), 15))
        assertFalse(ScheduledSpo2Slots.isClear(at(10, 2), 15))
    }

    @Test
    fun `every slot is itself clear`() {
        for (interval in listOf(15, 20, 30, 35, 45, 60)) {
            var t = at(0, 0)
            repeat(200) {
                t = ScheduledSpo2Slots.nextSlot(t, interval)
                assertTrue("interval $interval slot $t", ScheduledSpo2Slots.isClear(t, interval))
            }
        }
    }
}
