package com.pulseloop.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DiaryLogicTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(rome).toInstant().toEpochMilli()

    private val day = LocalDate.of(2026, 10, 20)

    @Test
    fun `a new entry is all unknown, never no`() {
        val e = DiaryLogic.empty(day, 1L)
        assertEquals("diary:2026-10-20", e.id)
        assertEquals("2026-10-20", e.date)
        listOf(e.caffeine, e.lateCaffeine, e.alcohol, e.lateMeal, e.sport, e.screensInBed, e.illness, e.mood, e.perceivedStress)
            .forEach { assertNull(it) }
        assertFalse(DiaryLogic.eveningAnswered(e))
    }

    @Test
    fun `a chip answers yes for itself only`() {
        val e = DiaryLogic.applyChip(DiaryLogic.empty(day, 1L), DiaryChip.LATE_CAFFEINE, 5L)
        assertEquals(true, e.lateCaffeine)
        assertEquals(true, e.caffeine)   // coffee after 15 implies coffee
        assertNull(e.alcohol)
        assertNull(e.lateMeal)
        assertEquals(5L, e.updatedAt)
        assertTrue(DiaryLogic.eveningAnswered(e))
        val both = DiaryLogic.applyChip(e, DiaryChip.ALCOHOL, 6L)
        assertEquals(true, both.alcohol)
        assertEquals(true, both.lateCaffeine)
    }

    @Test
    fun `setChip supports explicit no and back to unknown`() {
        var e = DiaryLogic.empty(day, 1L)
        e = DiaryLogic.setChip(e, DiaryChip.LATE_MEAL, false, 2L)
        assertEquals(false, e.lateMeal)
        e = DiaryLogic.setChip(e, DiaryChip.LATE_MEAL, null, 3L)
        assertNull(e.lateMeal)
        e = DiaryLogic.setChip(e, DiaryChip.LATE_CAFFEINE, false, 4L)
        assertNull(e.caffeine)   // no coffee after 15 says nothing about coffee earlier
    }

    @Test
    fun `time parsing`() {
        assertEquals("09:05", DiaryLogic.parseTime("9:5"))
        assertEquals("21:30", DiaryLogic.parseTime("2130"))
        assertEquals("21:30", DiaryLogic.parseTime("21.30"))
        assertEquals("07:00", DiaryLogic.parseTime("7"))
        assertNull(DiaryLogic.parseTime("25:00"))
        assertNull(DiaryLogic.parseTime(""))
        assertNull(DiaryLogic.parseTime("abc"))
        assertTrue(DiaryLogic.isValidTime("23:59"))
        assertFalse(DiaryLogic.isValidTime("24:00"))
    }

    @Test
    fun `next trigger is later today or tomorrow, across DST`() {
        assertEquals(at(2026, 10, 20, 21, 30), DiaryLogic.nextTrigger(at(2026, 10, 20, 12), 21 * 60 + 30, rome))
        assertEquals(at(2026, 10, 21, 21, 30), DiaryLogic.nextTrigger(at(2026, 10, 20, 21, 30), 21 * 60 + 30, rome))
        assertEquals(at(2026, 10, 21, 21, 30), DiaryLogic.nextTrigger(at(2026, 10, 20, 22), 21 * 60 + 30, rome))
        // 2026-10-25 is the end of CEST: the local 21:30 is still 21:30 local.
        val afterDst = DiaryLogic.nextTrigger(at(2026, 10, 24, 22), 21 * 60 + 30, rome)
        assertEquals(at(2026, 10, 25, 21, 30), afterDst)
        assertEquals(25L * 3_600_000L - 30 * 60_000L, afterDst - at(2026, 10, 24, 22))
    }

    @Test
    fun `late delivery after midnight still belongs to the evening before`() {
        assertEquals(day, DiaryLogic.diaryDateFor(at(2026, 10, 20, 21, 30), rome))
        assertEquals(day, DiaryLogic.diaryDateFor(at(2026, 10, 21, 1, 10), rome))
        assertEquals(day.plusDays(1), DiaryLogic.diaryDateFor(at(2026, 10, 21, 4, 0), rome))
    }

    @Test
    fun `scheduler and delivery mode resolution`() {
        assertEquals(DiaryLogic.Scheduler.EXACT_ALARM, DiaryLogic.scheduler(true))
        assertEquals(DiaryLogic.Scheduler.INEXACT_WORK, DiaryLogic.scheduler(false))
        assertEquals(DiaryDeliveryMode.AUTO_OPEN, DiaryLogic.effectiveMode(null, canUseFullScreenIntent = true))
        assertEquals(DiaryDeliveryMode.NOTIFICATION, DiaryLogic.effectiveMode(null, canUseFullScreenIntent = false))
        assertEquals(DiaryDeliveryMode.NOTIFICATION, DiaryLogic.effectiveMode(DiaryDeliveryMode.AUTO_OPEN, false))
        assertEquals(DiaryDeliveryMode.NOTIFICATION, DiaryLogic.effectiveMode(DiaryDeliveryMode.NOTIFICATION, true))
        assertEquals(DiaryDeliveryMode.AUTO_OPEN, DiaryLogic.effectiveMode(DiaryDeliveryMode.AUTO_OPEN, true))
    }

    @Test
    fun `morning check needs both taps`() {
        val e = DiaryLogic.empty(day, 1L)
        assertFalse(DiaryLogic.morningAnswered(e.copy(morningMood = 3)))
        assertTrue(DiaryLogic.morningAnswered(e.copy(morningMood = 3, morningFeeling = 4)))
        assertEquals(3, DiaryLogic.clampScale(3, 1..5))
        assertNull(DiaryLogic.clampScale(6, 1..5))
    }

    @Test
    fun `auto-derived fields alone are not compiled and do not skip the reminder`() {
        val e = DiaryLogic.empty(day, 1L).copy(sport = true, sportStart = "18:00", sportDurationMin = 45, screensInBed = true)
        assertFalse(DiaryLogic.anyAnswered(e))
        assertFalse(DiaryLogic.eveningAnswered(e))
        assertFalse(DiaryLogic.anyAnswered(DiaryLogic.empty(day, 1L)))
        assertFalse(DiaryLogic.anyAnswered(null))
    }

    @Test
    fun `any manual field makes the day compiled`() {
        val base = DiaryLogic.empty(day, 1L).copy(sport = true, screensInBed = true)
        listOf(
            base.copy(lateCaffeine = false), base.copy(alcohol = true), base.copy(lateMeal = false),
            base.copy(supplements = emptyList()), base.copy(mood = 3), base.copy(morningMood = 4),
            base.copy(morningFeeling = 2), base.copy(caffeine = true), base.copy(perceivedStress = 2), base.copy(illness = false),
        ).forEach { assertTrue(DiaryLogic.anyAnswered(it)) }
    }
}
