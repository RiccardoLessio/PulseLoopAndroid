package com.pulseloop.ui.dashboard

/**
 * Which dashboard the visibility/order preferences apply to. Today and Vitals are fully
 * independent scopes (a card hidden on Today can still show on Vitals). Ported from iOS
 * `MetricScope` (#64).
 */
enum class MetricScope { TODAY, VITALS }

/**
 * The reorderable/hideable cards on the Today and Vitals tabs, ported from iOS #64.
 *
 * [key] reuses iOS's `MetricKey.rawValue` strings so a saved order/hidden set is portable and
 * future-proof (a card added later slots in by key with no migration). Activity and Sleep are
 * Today-only; the eight vitals cards appear on both tabs.
 */
enum class DashboardCard(val key: String) {
    DIARY("diary"),                  // Today-only, full width (fork v0.3.1)
    ACTIVITY("steps"),               // Today-only
    SLEEP("sleep"),                  // Today-only
    HEART_RATE("heartRate"),
    SPO2("spo2"),
    HRV("hrv"),
    TEMPERATURE("temperature"),
    STRESS("stress"),
    FATIGUE("fatigue"),
    GLUCOSE("bloodSugar"),
    BLOOD_PRESSURE("bloodPressureSystolic");

    /** Human label for the Hidden tray / edit-mode affordances. */
    val displayName: String
        get() = when (this) {
            DIARY -> "Diario di oggi"
            ACTIVITY -> "Activity"
            SLEEP -> "Sleep"
            HEART_RATE -> "Heart Rate"
            SPO2 -> "SpO₂"
            HRV -> "HRV"
            TEMPERATURE -> "Temperature"
            STRESS -> "Stress"
            FATIGUE -> "Fatigue"
            GLUCOSE -> "Glucose"
            BLOOD_PRESSURE -> "Blood Pressure"
        }

    companion object {
        /**
         * Default Today order (matches the hardcoded tile order it replaces). [DIARY] leads, i.e. it
         * sits right after the hero card; a saved order from before it existed gets it slotted in
         * here by [CardOrder.resolvedOrder], so no migration is needed.
         */
        val todayDefault = listOf(
            DIARY, ACTIVITY, SLEEP, HEART_RATE, SPO2, HRV, TEMPERATURE, STRESS, FATIGUE, GLUCOSE, BLOOD_PRESSURE,
        )

        /** Default Vitals order (matches iOS `defaultOrder` / the hardcoded card order it replaces). */
        val vitalsDefault = listOf(
            HEART_RATE, SPO2, BLOOD_PRESSURE, HRV, STRESS, FATIGUE, GLUCOSE, TEMPERATURE,
        )

        fun defaultOrder(scope: MetricScope): List<DashboardCard> =
            if (scope == MetricScope.TODAY) todayDefault else vitalsDefault

        fun fromKey(key: String): DashboardCard? = entries.firstOrNull { it.key == key }
    }
}
