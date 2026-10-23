package com.pulseloop.settings

import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Albabit's ring (issue #103, 2026-10-23): `colmi-smarthealth`, SmartHealth/YCBT protocol,
     *  `manualSpo2` in its capabilities — both #103 controls must reach it, not just `YCBT`. */
    @Test
    fun `COLMI_SMART_HEALTH with MANUAL_SPO2 is supported`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.COLMI_SMART_HEALTH.name,
            capabilitiesRaw = WearableCapability.MANUAL_SPO2.key,
        )
        assertTrue(ScheduledSpo2Prefs.isSupported(device))
    }

    @Test
    fun `YCBT with MANUAL_SPO2 is still supported`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.YCBT.name,
            capabilitiesRaw = WearableCapability.MANUAL_SPO2.key,
        )
        assertTrue(ScheduledSpo2Prefs.isSupported(device))
    }

    @Test
    fun `the MANUAL_SPO2 capability is still required`() {
        val device = DeviceEntity(
            deviceTypeRaw = RingDeviceType.COLMI_SMART_HEALTH.name,
            capabilitiesRaw = WearableCapability.SPO2.key,
        )
        assertFalse(ScheduledSpo2Prefs.isSupported(device))
    }
}
