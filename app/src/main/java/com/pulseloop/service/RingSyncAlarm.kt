package com.pulseloop.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pulseloop.context.ContextPermissions
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.WearableLogCategory
import com.pulseloop.data.entity.WearableLogEntity
import com.pulseloop.data.entity.WearableLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The background ring sync's primary clock. On MagicOS the periodic [RingSyncWorker] routinely
 * went 9–25 h without running (Honor 90 Lite, Nov 2026) although the app is Doze-whitelisted and
 * in the EXEMPTED standby bucket, so JobScheduler alone is not a clock. An exact while-idle alarm
 * fires every [INTERVAL_MS], re-arms itself, and runs [RingBackgroundSync] — in-process under a wake
 * lock while [com.pulseloop.context.MonitorService] keeps the process alive, otherwise as an
 * expedited one-shot of [RingSyncWorker]. Either way every GATT-ownership guard applies
 * (foreground app, adaptive monitor, transient owner).
 * The periodic work stays as a fallback; [RingSyncPolicy.shouldRunAlarmSync] skips the alarm's pass
 * when any pass started recently, so the two never double the cadence.
 */
object RingSyncAlarm {
    private const val TAG = "RingSyncAlarm"
    const val ACTION_FIRE = "com.pulseloop.ringsync.FIRE"
    const val INTERVAL_MS = 30 * 60_000L
    private const val REQUEST_CODE = 2501
    private const val WORK_NAME = "ring_alarm_sync"
    private const val PREFS = "ring_sync"
    private const val KEY_LAST_ATTEMPT = "lastAttemptAt"
    /** The pass caps itself at 45 s; the margin covers the GATT teardown after it. */
    private const val WAKE_LOCK_MS = 75_000L

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, RingSyncAlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Arms (or re-arms, replacing any pending one) the next trigger [INTERVAL_MS] from now. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (ContextPermissions.canScheduleExactAlarms(app)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(app))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(app))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(app))
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    /** Stamped by [RingSyncWorker] when a pass starts, whatever triggered it. */
    fun markAttempt(context: Context, at: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_ATTEMPT, at).apply()
    }

    fun lastAttemptAt(context: Context): Long? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L }

    /** Battery low and not charging — the same rule the periodic work's constraint applies. */
    private fun batteryLow(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return false
        return RingSyncPolicy.batteryLow(level * 100 / scale, plugged)
    }

    internal suspend fun onFire(context: Context) {
        val app = context.applicationContext
        // Re-arm first: whatever happens below, the clock keeps running.
        schedule(app)
        val now = System.currentTimeMillis()
        val last = lastAttemptAt(app)
        val low = batteryLow(app)
        val run = RingSyncPolicy.shouldRunAlarmSync(now, last, low)
        val ago = last?.let { "${(now - it) / 60_000} min" } ?: "never"
        log(app, "Scheduled sync trigger (alarm): ${if (run) "sync enqueued" else if (low) "skipped, battery low" else "skipped, recent pass"}; last pass $ago ago")
        if (!run) return
        if (com.pulseloop.context.MonitorService.isRunning) {
            // The "Monitoraggio attivo" foreground service keeps this process alive and unfrozen,
            // so run the pass right here under a wake lock instead of handing it to JobScheduler,
            // which is exactly what MagicOS was deferring for hours.
            runInProcess(app)
            return
        }
        val builder = OneTimeWorkRequestBuilder<RingSyncWorker>()
            .setInputData(workDataOf(RingSyncWorker.KEY_TRIGGER to RingSyncWorker.TRIGGER_ALARM))
        // Below API 31 an expedited CoroutineWorker needs a foreground notification; plain
        // one-shot work there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, builder.build())
    }

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun runInProcess(app: Context) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulseloop:ringsync")
        wakeLock.acquire(WAKE_LOCK_MS)
        processScope.launch {
            try {
                RingBackgroundSync.run(app, RingSyncWorker.TRIGGER_ALARM)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    suspend fun log(context: Context, message: String, level: WearableLogLevel = WearableLogLevel.INFO) {
        try {
            PulseLoopDatabase.getInstance(context.applicationContext).wearableLogDao().insert(
                WearableLogEntity(categoryRaw = WearableLogCategory.SYNC.name, levelRaw = level.name, message = message),
            )
        } catch (e: Exception) {
            Log.w(TAG, "log failed", e)
        }
    }
}

/** Pure scheduling rules, unit-tested. */
object RingSyncPolicy {
    /** A pass that started less than this long ago (periodic job, earlier alarm) makes the alarm's redundant. */
    const val RECENT_PASS_MS = 20 * 60_000L
    const val LOW_BATTERY_PERCENT = 15

    fun batteryLow(percent: Int, plugged: Boolean): Boolean = !plugged && percent <= LOW_BATTERY_PERCENT

    fun shouldRunAlarmSync(now: Long, lastAttemptAt: Long?, batteryLow: Boolean): Boolean {
        if (batteryLow) return false
        if (lastAttemptAt == null || lastAttemptAt > now) return true
        return now - lastAttemptAt >= RECENT_PASS_MS
    }
}

class RingSyncAlarmReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RingSyncAlarm.ACTION_FIRE) return
        val pending = goAsync()
        scope.launch { try { RingSyncAlarm.onFire(context) } finally { pending.finish() } }
    }
}
