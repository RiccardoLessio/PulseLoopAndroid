package com.pulseloop.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The interval picker behind issue #103's request to space the app's own spot SpO₂ past an hour.
 * Pure arithmetic — the SharedPreferences round-trip needs a device.
 */
class ScheduledSpo2PrefsTest {

    @Test
    fun `the offered options pass through unchanged`() {
        for (minutes in listOf(60, 120, 180)) {
            assertEquals(minutes, ScheduledSpo2Prefs.sanitizeIntervalMinutes(minutes))
        }
    }

    @Test
    fun `anything else snaps to the nearest option and stays inside the range`() {
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(0))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(59))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(89))
        assertEquals(120, ScheduledSpo2Prefs.sanitizeIntervalMinutes(90))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(150))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(151))
        assertEquals(180, ScheduledSpo2Prefs.sanitizeIntervalMinutes(300))
        assertEquals(60, ScheduledSpo2Prefs.sanitizeIntervalMinutes(-30))
    }
}
