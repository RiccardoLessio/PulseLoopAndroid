package com.pulseloop.diary

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulseloop.context.ContextPermissions
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Serializable
data class DiarySettings(
    /**
     * Evening reminder: opt-in (ROADMAP), so off on a first install and after the upgrade that
     * introduces it. A stored choice is kept: the settings are only written by [DiaryPrefs.update].
     */
    val enabled: Boolean = false,
    val eveningMinute: Int = 21 * 60 + 30,
    /** Null = never chosen: auto-open when the full-screen-intent permission allows it. */
    val modeCode: String? = null,
    val morningEnabled: Boolean = false,
    val morningMinute: Int = 7 * 60 + 30,
    /** "Integratori rapidi": the one-tap supplement chips, normalised names (DiaryLogic.normalizeSupplement). */
    val quickSupplements: List<String> = DiaryLogic.DEFAULT_SUPPLEMENTS,
) {
    val chosenMode: DiaryDeliveryMode? get() = DiaryDeliveryMode.of(modeCode)
}

class DiaryPrefs private constructor(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DiarySettings> = _settings.asStateFlow()

    fun update(transform: (DiarySettings) -> DiarySettings) = synchronized(this) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(DiarySettings.serializer(), next)).apply()
    }

    private fun load(): DiarySettings = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(DiarySettings.serializer(), it) }.getOrNull()
    } ?: DiarySettings()

    companion object {
        private const val KEY = "settings"
        @Volatile private var instance: DiaryPrefs? = null
        fun get(context: Context): DiaryPrefs = instance ?: synchronized(this) {
            instance ?: DiaryPrefs(context.applicationContext.getSharedPreferences("diary", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}

/**
 * Evening diary + optional morning check reminders. An exact alarm when the app may schedule one
 * (USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM), otherwise a WorkManager one-shot — inexact, MagicOS
 * may deliver it some minutes late. Each delivery schedules the next day's. A dismissed reminder
 * is not repeated.
 */
object DiaryReminders {
    private const val TAG = "DiaryReminders"
    const val KIND_EVENING = "evening"
    const val KIND_MORNING = "morning"
    const val CHANNEL_ID = "diary"
    const val NOTIFICATION_EVENING = 2401
    const val NOTIFICATION_MORNING = 2402
    const val EXTRA_KIND = "kind"
    const val EXTRA_DATE = "date"
    const val EXTRA_CHIP = "chip"
    const val ACTION_FIRE = "com.pulseloop.diary.FIRE"
    const val ACTION_CHIP = "com.pulseloop.diary.CHIP"
    private const val TIMEOUT_MS = 4 * 3_600_000L

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val s = DiaryPrefs.get(app).settings.value
        if (s.enabled) schedule(app, KIND_EVENING, s.eveningMinute) else cancel(app, KIND_EVENING)
        if (s.morningEnabled) schedule(app, KIND_MORNING, s.morningMinute) else cancel(app, KIND_MORNING)
    }

    private fun alarmIntent(context: Context, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context, if (kind == KIND_EVENING) 2410 else 2411,
        Intent(context, DiaryAlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_KIND, kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun workName(kind: String) = "diary_reminder_$kind"

    private fun schedule(context: Context, kind: String, minuteOfDay: Int) {
        val now = System.currentTimeMillis()
        val at = DiaryLogic.nextTrigger(now, minuteOfDay)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        when (DiaryLogic.scheduler(ContextPermissions.canScheduleExactAlarms(context))) {
            DiaryLogic.Scheduler.EXACT_ALARM -> {
                WorkManager.getInstance(context).cancelUniqueWork(workName(kind))
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, kind))
                    return
                } catch (e: SecurityException) {
                    Log.w(TAG, "exact alarm refused, falling back to WorkManager", e)
                }
                enqueueWork(context, kind, at - now)
            }
            DiaryLogic.Scheduler.INEXACT_WORK -> {
                am.cancel(alarmIntent(context, kind))
                enqueueWork(context, kind, at - now)
            }
        }
    }

    private fun enqueueWork(context: Context, kind: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<DiaryReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_KIND to kind))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(kind), ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancel(context: Context, kind: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context, kind))
        WorkManager.getInstance(context).cancelUniqueWork(workName(kind))
    }

    /** A reminder is due: post it unless the day is already answered, then arm tomorrow's. */
    suspend fun fire(context: Context, kind: String) {
        val app = context.applicationContext
        try {
            val dao = PulseLoopDatabase.getInstance(app).diaryEntryDao()
            if (kind == KIND_EVENING) {
                val date = DiaryLogic.diaryDateFor(System.currentTimeMillis())
                if (!DiaryLogic.eveningAnswered(dao.byDate(date.toString()))) postEvening(app, date)
            } else {
                val date = LocalDate.now()
                if (!DiaryLogic.morningAnswered(dao.byDate(date.toString()))) postMorning(app, date)
            }
        } finally {
            reschedule(app)
        }
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Diario", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Promemoria del diario serale (facoltativo)"
            },
        )
    }

    private fun quickIntent(context: Context, date: LocalDate, morning: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, DiaryQuickActivity::class.java)
                .putExtra(EXTRA_DATE, date.toString())
                .putExtra(DiaryQuickActivity.EXTRA_MORNING, morning)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun chipIntent(context: Context, date: LocalDate, chip: DiaryChip): PendingIntent = PendingIntent.getBroadcast(
        context, 2420 + chip.ordinal,
        Intent(context, DiaryActionReceiver::class.java).setAction(ACTION_CHIP)
            .putExtra(EXTRA_DATE, date.toString()).putExtra(EXTRA_CHIP, chip.code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The evening notification. Both modes carry the three chips as action buttons; AUTO_OPEN adds
     * a full-screen intent, which Android shows as the short diary over the lock screen when the
     * phone is locked/idle and as a heads-up while it is in use.
     */
    suspend fun postEvening(context: Context, date: LocalDate) {
        createChannel(context)
        val entry = PulseLoopDatabase.getInstance(context).diaryEntryDao().byDate(date.toString())
        val answered = DiaryChip.entries.filter { DiaryLogic.chipValue(entry, it) == true }
        val mode = DiaryLogic.effectiveMode(DiaryPrefs.get(context).settings.value.chosenMode, ContextPermissions.canUseFullScreenIntent(context))
        val content = quickIntent(context, date, morning = false, requestCode = 2430)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Diario di stasera")
            .setContentText(
                if (answered.isEmpty()) "Un tocco se vale per oggi. Il resto lo rileva l'app."
                else "Registrato: " + answered.joinToString(", ") { it.label },
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
        DiaryChip.entries.filter { it !in answered }.forEach { builder.addAction(0, it.label, chipIntent(context, date, it)) }
        if (mode == DiaryDeliveryMode.AUTO_OPEN && answered.isEmpty()) {
            builder.setFullScreenIntent(quickIntent(context, date, morning = false, requestCode = 2431), true)
        }
        notify(context, NOTIFICATION_EVENING, builder)
    }

    private fun postMorning(context: Context, date: LocalDate) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Come ti senti stamattina?")
            .setContentText("Due tocchi: umore e come ti senti")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(quickIntent(context, date, morning = true, requestCode = 2432))
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
        notify(context, NOTIFICATION_MORNING, builder)
    }

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
    }

    fun cancelNotification(context: Context, morning: Boolean) =
        NotificationManagerCompat.from(context).cancel(if (morning) NOTIFICATION_MORNING else NOTIFICATION_EVENING)
}

class DiaryAlarmReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DiaryReminders.ACTION_FIRE) return
        val kind = intent.getStringExtra(DiaryReminders.EXTRA_KIND) ?: return
        val pending = goAsync()
        scope.launch { try { DiaryReminders.fire(context, kind) } finally { pending.finish() } }
    }
}

class DiaryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DiaryReminders.fire(applicationContext, inputData.getString(DiaryReminders.EXTRA_KIND) ?: DiaryReminders.KIND_EVENING)
        return Result.success()
    }
}

/** A chip tapped in the notification: record it without opening anything. */
class DiaryActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DiaryReminders.ACTION_CHIP) return
        val chip = DiaryChip.of(intent.getStringExtra(DiaryReminders.EXTRA_CHIP)) ?: return
        val date = intent.getStringExtra(DiaryReminders.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val dao = PulseLoopDatabase.getInstance(app).diaryEntryDao()
                val now = System.currentTimeMillis()
                val entry = dao.byDate(date.toString()) ?: DiaryLogic.empty(date, now)
                dao.upsert(DiaryLogic.applyChip(entry, chip, now))
                DiaryReminders.postEvening(app, date)   // same notification, minus the tapped chip
                com.pulseloop.remote.RingHubSyncWorker.enqueue(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Exact alarms and WorkManager requests do not survive everything: re-arm after boot/update. */
class ContextBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                DiaryReminders.reschedule(context)
                com.pulseloop.context.ActivityRecognitionManager.sync(context)
                com.pulseloop.context.MonitorService.refresh(context)
                com.pulseloop.context.PhoneContextWorker.schedule(context)
            }
        }
    }
}
