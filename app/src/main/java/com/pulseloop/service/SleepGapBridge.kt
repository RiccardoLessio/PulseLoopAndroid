package com.pulseloop.service

import android.content.Context
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenModeListener
import com.pulseloop.settings.ZenModeTracker
import com.pulseloop.settings.ZenWindow
import com.pulseloop.util.TimeUtil
import java.time.ZoneId

/**
 * Whether a gap of [SleepSegmentation.SESSION_GAP_MINUTES] or more between two runs of sleep is
 * still one night (issue #105).
 *
 * The hour-long gap rule keeps a nap apart from the night, but it also split a night whose wearer
 * lay awake for 72 minutes between two ring records, both inside the same Bedtime window. The
 * wearer's own window is better evidence than the gap's length, so a gap that lies wholly inside
 * one is bridged: the two parts stay one session, and the gap reads as awake between records.
 */
fun interface SleepGapBridge {
    fun bridges(gapStart: Long, gapEnd: Long): Boolean

    companion object {
        val NONE = SleepGapBridge { _, _ -> false }

        /** The bridge for the user's current quiet-hours settings. Reads them once. */
        fun fromSettings(context: Context): SleepGapBridge {
            val prefs = QuietHoursPrefs(context)
            val zenWindows = if (prefs.gateByZenMode && ZenModeListener.isAccessGranted(context)) {
                ZenModeTracker.getWindows(context)
            } else {
                null
            }
            return forWindows(prefs.enabled, zenWindows, prefs.startMinutes, prefs.endMinutes)
        }

        /**
         * The same source order as the import gate ([QuietHoursPrefs.keptMinutesForRecord]): off
         * unless quiet hours are on; the recorded Bedtime/DND windows when that gating is on and the
         * night has any; otherwise the clock window. A night that has Bedtime windows is judged by
         * them alone, so a gap after Bedtime ended is not bridged by a wider clock schedule.
         *
         * [zenWindows] is null when Bedtime gating is off.
         */
        fun forWindows(
            enabled: Boolean,
            zenWindows: List<ZenWindow>?,
            startMinutes: Int,
            endMinutes: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): SleepGapBridge {
            if (!enabled) return NONE
            return SleepGapBridge { gapStart, gapEnd ->
                if (gapEnd <= gapStart) return@SleepGapBridge false
                val night = zenWindows?.let { nightWindows(it, gapStart, zone) }.orEmpty()
                if (night.isNotEmpty()) {
                    night.any { w -> w.start <= gapStart && gapEnd <= effectiveEnd(w) }
                } else {
                    clockCovers(gapStart, gapEnd, startMinutes, endMinutes, zone)
                }
            }
        }

        /** The windows of the waking day [ts] falls in — the span the import gate reads too. */
        private fun nightWindows(windows: List<ZenWindow>, ts: Long, zone: ZoneId): List<ZenWindow> {
            val wakingDay = TimeUtil.wakingDayLocal(ts, zone)
            val dayStart = wakingDay - 12 * 3_600_000L
            val dayEnd = wakingDay + 36 * 3_600_000L
            return windows.filter { w -> effectiveEnd(w) > dayStart && w.start < dayEnd }
        }

        private fun effectiveEnd(w: ZenWindow): Long =
            w.end ?: (w.start + ZenModeTracker.MAX_WINDOW_DURATION_MS)

        /** Every minute of the gap inside the daily clock window. A gap of a day or more never is. */
        private fun clockCovers(gapStart: Long, gapEnd: Long, start: Int, end: Int, zone: ZoneId): Boolean {
            if (gapEnd - gapStart >= 24 * 3_600_000L) return false
            var t = gapStart
            while (t < gapEnd) {
                if (!QuietHoursPrefs.covers(start, end, QuietHoursPrefs.minuteOfDay(t, zone))) return false
                t += 60_000L
            }
            return QuietHoursPrefs.covers(start, end, QuietHoursPrefs.minuteOfDay(gapEnd - 1, zone))
        }
    }
}
