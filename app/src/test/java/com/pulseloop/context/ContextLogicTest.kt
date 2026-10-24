package com.pulseloop.context

import com.pulseloop.context.UsageAggregation.Event
import com.pulseloop.context.UsageAggregation.Kind
import com.pulseloop.data.entity.ZoneEntity
import com.pulseloop.labels.LabelCategory
import com.pulseloop.labels.LabelContext
import com.pulseloop.labels.LabelPromptPolicy
import com.pulseloop.labels.LabelSuggester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ContextLogicTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(rome).toInstant().toEpochMilli()

    private val home = ZoneEntity(id = "z-home", name = "Casa", category = "home", latitude = 45.0700, longitude = 7.6800, radiusMeters = 150.0)
    private val gym = ZoneEntity(id = "z-gym", name = "Palestra", category = "gym", latitude = 45.0600, longitude = 7.7000, radiusMeters = 80.0)

    // --- zones ------------------------------------------------------------------------------

    @Test
    fun `point in radius matches, outside does not`() {
        assertEquals("z-home", ZoneMatcher.zoneContaining(45.0710, 7.6800, listOf(home, gym))?.id)   // ~111 m
        assertNull(ZoneMatcher.zoneContaining(45.0720, 7.6800, listOf(home, gym)))                   // ~222 m
        assertNull(ZoneMatcher.zoneContaining(45.0710, 7.6800, listOf(home.copy(deletedAt = 1L))))
    }

    @Test
    fun `overlapping zones - nearest centre wins`() {
        val near = home.copy(id = "near", latitude = 45.0712, radiusMeters = 300.0)
        assertEquals("near", ZoneMatcher.zoneContaining(45.0711, 7.6800, listOf(home.copy(radiusMeters = 300.0), near))?.id)
    }

    @Test
    fun `user zones take precedence over auto places`() {
        val autoHome = AutoPlace(id = 1, name = "Casa", autoLabel = "Casa", latitude = 45.0700, longitude = 7.6800)
        val autoWork = AutoPlace(id = 2, name = "Lavoro", autoLabel = "Lavoro", latitude = 45.0800, longitude = 7.6600)
        val zoneOverHome = home.copy(name = "Casetta mia")
        val inHome = ZoneMatcher.match(45.0701, 7.6800, listOf(zoneOverHome), listOf(autoHome, autoWork))!!
        assertEquals("Casetta mia", inHome.name)
        assertTrue(inHome.fromZone)
        val atWork = ZoneMatcher.match(45.0801, 7.6600, listOf(zoneOverHome), listOf(autoHome, autoWork))!!
        assertEquals("work", atWork.category)
        assertFalse(atWork.fromZone)
        assertNull(ZoneMatcher.match(45.2, 7.9, listOf(zoneOverHome), listOf(autoHome, autoWork)))
        // An auto place already tied to a zone is that zone, not a second match.
        assertNull(ZoneMatcher.match(45.0801, 7.6600, emptyList(), listOf(autoWork.copy(zoneId = "z"))))
    }

    @Test
    fun `haversine sanity`() {
        val d = ZoneMatcher.distanceMeters(45.0700, 7.6800, 45.0800, 7.6600)
        assertTrue(d in 1800.0..2000.0)
    }

    // --- label suggestion --------------------------------------------------------------------

    @Test
    fun `suggestions follow activity and place`() {
        val tue = at(2026, 10, 20, 10, 5)
        assertEquals(LabelCategory.TRAFFIC, LabelSuggester.suggest(LabelContext(tue, activity = "IN_VEHICLE"), rome))
        assertEquals(LabelCategory.TRAFFIC, LabelSuggester.suggest(LabelContext(tue, speedMps = 12f), rome))
        assertEquals(LabelCategory.SPORT, LabelSuggester.suggest(LabelContext(tue, activity = "RUNNING"), rome))
        assertEquals(LabelCategory.SPORT, LabelSuggester.suggest(LabelContext(tue, placeCategory = "gym"), rome))
        assertEquals(LabelCategory.MEETING, LabelSuggester.suggest(LabelContext(tue, placeCategory = "work"), rome))
        assertEquals(LabelCategory.WORK, LabelSuggester.suggest(LabelContext(at(2026, 10, 20, 10, 20), placeCategory = "work"), rome))
        assertEquals(LabelCategory.WORK, LabelSuggester.suggest(LabelContext(at(2026, 10, 24, 10, 5), placeCategory = "work"), rome))  // Saturday
        assertNull(LabelSuggester.suggest(LabelContext(tue, placeCategory = "home"), rome))
        assertNull(LabelSuggester.suggest(LabelContext(tue), rome))
    }

    @Test
    fun `prompt policy - rate limit, quiet hours, one per episode`() {
        val p = LabelPromptPolicy()
        val noon = at(2026, 10, 20, 12)
        assertTrue(p.shouldPrompt(noon, lastPromptAt = null, alreadyLabelled = false, zone = rome))
        assertFalse(p.shouldPrompt(noon, noon - 29 * 60_000L, false, rome))
        assertTrue(p.shouldPrompt(noon, noon - 30 * 60_000L, false, rome))
        assertFalse(p.shouldPrompt(noon, null, alreadyLabelled = true, zone = rome))
        assertFalse(p.shouldPrompt(at(2026, 10, 20, 23, 30), null, false, rome))
        assertFalse(p.shouldPrompt(at(2026, 10, 20, 6, 59), null, false, rome))
        assertTrue(p.shouldPrompt(at(2026, 10, 20, 7, 0), null, false, rome))
        assertFalse(p.copy(enabled = false).shouldPrompt(noon, null, false, rome))
        val daytimeQuiet = p.copy(quietStartMinute = 13 * 60, quietEndMinute = 14 * 60)
        assertFalse(daytimeQuiet.shouldPrompt(at(2026, 10, 20, 13, 30), null, false, rome))
        assertTrue(daytimeQuiet.shouldPrompt(at(2026, 10, 20, 23, 30), null, false, rome))
    }

    // --- phone usage -----------------------------------------------------------------------

    @Test
    fun `screen intervals are split across hours and unlocks counted`() {
        val offset = { _: Long -> 0L }
        val h0 = 1_792_483_200_000L   // an exact UTC hour
        val events = listOf(
            Event(h0 + 50 * 60_000L, Kind.SCREEN_ON),
            Event(h0 + 50 * 60_000L + 1, Kind.UNLOCK),
            Event(h0 + 70 * 60_000L, Kind.SCREEN_OFF),   // 10 min in hour 0, 10 min in hour 1
            Event(h0 + 100 * 60_000L, Kind.SCREEN_ON),   // still on at `to`
        )
        val rows = UsageAggregation.aggregate(events, h0, h0 + 110 * 60_000L, screenOnAtStart = false, now = 1L, zoneOffsetMs = offset)
        assertEquals(listOf(h0, h0 + 3_600_000L), rows.map { it.hourStart })
        assertEquals(10.0, rows[0].screenOnMinutes, 1e-6)
        assertEquals(20.0, rows[1].screenOnMinutes, 1e-6)
        assertEquals(1, rows[0].unlocks)
        assertNull(rows[0].lastScreenOffAt)
        assertEquals(h0 + 70 * 60_000L, rows[1].lastScreenOffAt)
        assertEquals("usage:$h0", rows[0].id)
    }

    @Test
    fun `screen already on at the window start counts from the start`() {
        val h0 = 1_792_483_200_000L
        val rows = UsageAggregation.aggregate(
            listOf(Event(h0 + 15 * 60_000L, Kind.SCREEN_OFF)), h0, h0 + 60 * 60_000L,
            screenOnAtStart = true, now = 1L, zoneOffsetMs = { 0L },
        )
        assertEquals(15.0, rows.single().screenOnMinutes, 1e-6)
    }

    @Test
    fun `local hour boundaries follow the zone offset`() {
        val offset = { _: Long -> 30 * 60_000L }   // a +00:30 zone
        val h = UsageAggregation.hourStart(1_792_483_200_000L + 10 * 60_000L, offset)
        assertEquals(1_792_483_200_000L - 30 * 60_000L, h)
    }
}
