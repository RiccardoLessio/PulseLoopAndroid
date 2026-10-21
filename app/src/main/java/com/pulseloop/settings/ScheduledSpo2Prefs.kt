package com.pulseloop.settings

import android.content.Context
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability

/**
 * The opt-in behind issue #103: have the app start a real spot SpO₂ on the SpO₂ interval.
 *
 * On a YCBT ring the hourly SpO₂ is computed from heart rate (one HR always maps to the same
 * value), so the history line is an estimate. The ring's manual measurement is real — red LED,
 * logged on its own — so the app runs that instead, between the ring's own readings
 * ([com.pulseloop.service.ScheduledSpo2Slots]).
 *
 * Off by default: each run holds the connection and the optical sensor for about a minute.
 * In SharedPreferences rather than `device_measurement_configs` to stay clear of a schema bump.
 */
class ScheduledSpo2Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        private const val PREFS_NAME = "scheduled_spo2"
        private const val KEY_ENABLED = "enabled"

        /**
         * Rings this is offered for: a manual SpO₂ that ends with the ring's own `04 0e` verdict,
         * so the run has a known end and the connection can be released right after it. Only the
         * YCBT family signals that today (issue #59).
         */
        fun isSupported(device: DeviceEntity?): Boolean =
            device != null && device.deviceType == RingDeviceType.YCBT &&
                WearableCapability.MANUAL_SPO2 in device.capabilities
    }
}
