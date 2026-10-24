package com.pulseloop.diary

import com.pulseloop.data.entity.DiaryEntryEntity
import com.pulseloop.data.entity.StringListConverter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** v0.3: Today card one-tap chips, supplements, "Integratori rapidi". */
class DiaryOneTapTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun empty() = DiaryLogic.empty(day, 1L)

    @Test
    fun `tap cycle is unknown, si, no, si - only a clear goes back to unknown`() {
        assertEquals(true, DiaryLogic.nextTap(null))
        assertEquals(false, DiaryLogic.nextTap(true))
        assertEquals(true, DiaryLogic.nextTap(false))

        var e = empty()
        e = DiaryLogic.tapChip(e, DiaryChip.ALCOHOL, 2L)
        assertEquals(true, e.alcohol)
        e = DiaryLogic.tapChip(e, DiaryChip.ALCOHOL, 3L)
        assertEquals(false, e.alcohol)
        e = DiaryLogic.tapChip(e, DiaryChip.ALCOHOL, 4L)
        assertEquals(true, e.alcohol)
        assertEquals(4L, e.updatedAt)
        e = DiaryLogic.setChip(e, DiaryChip.ALCOHOL, null, 5L)   // long-press
        assertNull(e.alcohol)
        assertNull(e.lateMeal)
        assertNull(e.lateCaffeine)
    }

    @Test
    fun `late coffee tap implies coffee, its no does not deny earlier coffee`() {
        var e = DiaryLogic.tapChip(empty(), DiaryChip.LATE_CAFFEINE, 2L)
        assertEquals(true, e.lateCaffeine)
        assertEquals(true, e.caffeine)
        e = DiaryLogic.tapChip(empty(), DiaryChip.LATE_CAFFEINE, 2L).copy(caffeine = null)
        e = DiaryLogic.tapChip(e, DiaryChip.LATE_CAFFEINE, 3L)
        assertEquals(false, e.lateCaffeine)
        assertNull(e.caffeine)
    }

    @Test
    fun `supplements - null is unknown, taking adds, untaking removes but keeps the answer`() {
        var e = empty()
        assertNull(e.supplements)
        assertNull(DiaryLogic.supplementValue(e, "teanina"))
        assertFalse(DiaryLogic.anyAnswered(e))

        e = DiaryLogic.tapSupplement(e, "teanina", 2L)
        assertEquals(listOf("teanina"), e.supplements)
        assertEquals(true, DiaryLogic.supplementValue(e, "teanina"))
        assertEquals(false, DiaryLogic.supplementValue(e, "ashwagandha"))   // the day is answered
        assertTrue(DiaryLogic.anyAnswered(e))

        e = DiaryLogic.tapSupplement(e, "ashwagandha", 3L)
        assertEquals(listOf("teanina", "ashwagandha"), e.supplements)

        e = DiaryLogic.tapSupplement(e, "teanina", 4L)   // second tap: not taken
        assertEquals(listOf("ashwagandha"), e.supplements)
        assertEquals(false, DiaryLogic.supplementValue(e, "teanina"))

        e = DiaryLogic.tapSupplement(e, "ashwagandha", 5L)
        assertEquals(emptyList<String>(), e.supplements)   // "none taken" is an answer, not unknown
        assertEquals(5L, e.updatedAt)

        e = DiaryLogic.tapSupplement(e, "teanina", 6L)   // from no back to sì
        assertEquals(listOf("teanina"), e.supplements)
    }

    @Test
    fun `clearing the last supplement drops back to unknown, never duplicates`() {
        var e = DiaryLogic.setSupplement(empty(), "teanina", true, 2L)
        e = DiaryLogic.setSupplement(e, "teanina", true, 3L)
        assertEquals(listOf("teanina"), e.supplements)
        e = DiaryLogic.setSupplement(e, "ashwagandha", true, 4L)
        e = DiaryLogic.setSupplement(e, "teanina", null, 5L)
        assertEquals(listOf("ashwagandha"), e.supplements)
        e = DiaryLogic.setSupplement(e, "ashwagandha", null, 6L)
        assertNull(e.supplements)
        // Clearing an untouched day stays untouched.
        assertNull(DiaryLogic.setSupplement(empty(), "teanina", null, 7L).supplements)
        // Supplements do not count as the evening answer that skips the reminder.
        assertFalse(DiaryLogic.eveningAnswered(DiaryLogic.setSupplement(empty(), "teanina", true, 8L)))
    }

    @Test
    fun `quick supplement list - defaults, normalised adds, no duplicates, removal`() {
        assertEquals(listOf("teanina", "ashwagandha"), DiarySettings().quickSupplements)
        var list = DiarySettings().quickSupplements
        list = DiaryLogic.addQuickSupplement(list, "  Magnesio   Bisglicinato ")
        assertEquals(listOf("teanina", "ashwagandha", "magnesio bisglicinato"), list)
        assertEquals(list, DiaryLogic.addQuickSupplement(list, "TEANINA"))
        assertEquals(list, DiaryLogic.addQuickSupplement(list, "   "))
        assertEquals(list, DiaryLogic.addQuickSupplement(list, null))
        assertEquals(list, DiaryLogic.addQuickSupplement(list, "x".repeat(41)))
        list = DiaryLogic.removeQuickSupplement(list, "teanina")
        assertEquals(listOf("ashwagandha", "magnesio bisglicinato"), list)
        assertEquals("Ashwagandha", DiaryLogic.supplementLabel("ashwagandha"))
    }

    @Test
    fun `quick supplement list persists in the diary settings and old settings get the defaults`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val custom = DiarySettings(enabled = true, quickSupplements = listOf("magnesio"))
        val back = json.decodeFromString(DiarySettings.serializer(), json.encodeToString(DiarySettings.serializer(), custom))
        assertEquals(listOf("magnesio"), back.quickSupplements)
        assertTrue(back.enabled)
        val emptied = json.decodeFromString(DiarySettings.serializer(), json.encodeToString(DiarySettings.serializer(), DiarySettings(quickSupplements = emptyList())))
        assertEquals(emptyList<String>(), emptied.quickSupplements)
        // v0.2 stored settings have no field.
        assertEquals(DiaryLogic.DEFAULT_SUPPLEMENTS, json.decodeFromString(DiarySettings.serializer(), """{"enabled":true}""").quickSupplements)
    }

    @Test
    fun `room column mapping keeps null, empty and taken apart`() {
        val c = StringListConverter()
        assertNull(c.fromList(null))
        assertNull(c.toList(null))
        assertEquals("[]", c.fromList(emptyList()))
        assertEquals(emptyList<String>(), c.toList("[]"))
        val text = c.fromList(listOf("teanina", "ashwagandha"))
        assertEquals("[\"teanina\",\"ashwagandha\"]", text)
        assertEquals(listOf("teanina", "ashwagandha"), c.toList(text))
        assertNull(c.toList("not json"))   // a corrupt cell reads as unknown, never crashes the screen
        assertNull(DiaryEntryEntity(id = "d", date = "2026-10-20").supplements)
    }
}
