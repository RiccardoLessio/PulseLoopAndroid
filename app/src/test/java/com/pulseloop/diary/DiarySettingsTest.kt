package com.pulseloop.diary

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarySettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `evening reminder is opt-in on a first install`() {
        assertFalse(DiarySettings().enabled)
    }

    @Test
    fun `a stored choice survives, either way`() {
        val on = json.encodeToString(DiarySettings.serializer(), DiarySettings(enabled = true))
        assertTrue(json.decodeFromString(DiarySettings.serializer(), on).enabled)
        val off = json.encodeToString(DiarySettings.serializer(), DiarySettings(enabled = false))
        assertFalse(json.decodeFromString(DiarySettings.serializer(), off).enabled)
    }

    @Test
    fun `stored settings without the field read as off`() {
        assertFalse(json.decodeFromString(DiarySettings.serializer(), """{"eveningMinute":1290}""").enabled)
    }
}
