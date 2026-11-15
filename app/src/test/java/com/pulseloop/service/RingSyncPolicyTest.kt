package com.pulseloop.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingSyncPolicyTest {
    private val now = 1_794_960_000_000L
    private val min = 60_000L

    @Test
    fun `runs when no pass ever started`() {
        assertTrue(RingSyncPolicy.shouldRunAlarmSync(now, null, batteryLow = false))
    }

    @Test
    fun `skips when a pass started recently`() {
        assertFalse(RingSyncPolicy.shouldRunAlarmSync(now, now - 10 * min, batteryLow = false))
        assertTrue(RingSyncPolicy.shouldRunAlarmSync(now, now - 20 * min, batteryLow = false))
        assertTrue(RingSyncPolicy.shouldRunAlarmSync(now, now - 25 * 60 * min, batteryLow = false))
    }

    @Test
    fun `a stamp from the future does not block forever`() {
        assertTrue(RingSyncPolicy.shouldRunAlarmSync(now, now + 60 * min, batteryLow = false))
    }

    @Test
    fun `low battery skips like the periodic constraint`() {
        assertFalse(RingSyncPolicy.shouldRunAlarmSync(now, null, batteryLow = true))
        assertTrue(RingSyncPolicy.batteryLow(15, plugged = false))
        assertFalse(RingSyncPolicy.batteryLow(15, plugged = true))
        assertFalse(RingSyncPolicy.batteryLow(16, plugged = false))
    }
}
