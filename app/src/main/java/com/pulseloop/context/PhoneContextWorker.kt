package com.pulseloop.context

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.data.PulseLoopDatabase
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Hourly, no radio: folds the last [LOOKBACK_MS] of UsageStatsManager screen events into
 * per-hour rows (re-computed every pass and upserted by hour, so a still-open hour is corrected
 * later). Needs Usage Access; a no-op without it.
 */
class PhoneContextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ContextPrefs.get(app).settings.value.usageEnabled) return Result.success()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !ContextPermissions.hasUsageAccess(app)) return Result.success()
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val offset = { t: Long -> tz.getOffset(t).toLong() }
        val from = UsageAggregation.hourStart(now - LOOKBACK_MS, offset)
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = mutableListOf<UsageAggregation.Event>()
        val raw = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageAggregation.Kind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageAggregation.Kind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageAggregation.Kind.UNLOCK
                else -> null
            } ?: continue
            events += UsageAggregation.Event(e.timeStamp, kind)
        }
        // If the first screen event in the window is an OFF, the screen was on at its start.
        val firstScreen = events.firstOrNull { it.kind != UsageAggregation.Kind.UNLOCK }
        val onAtStart = firstScreen?.kind == UsageAggregation.Kind.SCREEN_OFF ||
            (firstScreen == null && (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
        val rows = UsageAggregation.aggregate(events, from, now, onAtStart, now, offset)
        PulseLoopDatabase.getInstance(app).phoneUsageHourlyDao().upsertAll(rows)
        ContextPrefs.get(app).usageProcessedUntil = now
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "phone_context_hourly"
        private const val LOOKBACK_MS = 26 * 3_600_000L

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!ContextPrefs.get(context).settings.value.usageEnabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PhoneContextWorker>(60, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<PhoneContextWorker>().build(),
            )
        }
    }
}
