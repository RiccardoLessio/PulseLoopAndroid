package com.pulseloop.labels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.pulseloop.R
import com.pulseloop.adaptive.AdaptivePrefs
import com.pulseloop.context.ContextPrefs
import com.pulseloop.context.ZoneMatcher
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityLabelEntity
import com.pulseloop.ui.DeepLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Cosa stai facendo?" — a normal notification (never full-screen), posted when an escalation
 * starts or a stress trigger was suppressed. Rate-limited by [LabelPromptPolicy]; one tap on the
 * pre-suggested label or any of the six buttons answers it, "Altro…" takes free text, ignoring it
 * is fine: it times out after [TIMEOUT_MS] and is never re-posted.
 */
object LabelPrompter {
    const val CHANNEL_ID = "activity_labels"
    private const val NOTIFICATION_ID = 2201
    const val TIMEOUT_MS = 30 * 60_000L
    const val ACTION_LABEL = "com.pulseloop.labels.LABEL"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_AT = "at"
    const val EXTRA_EPISODE = "episode"
    const val EXTRA_TRANSITION = "transition"
    const val EXTRA_SUGGESTED = "suggested"
    const val REMOTE_INPUT_KEY = "label_text"
    private const val CONTEXT_MAX_AGE_MS = 30 * 60_000L

    private val buttonIds = listOf(R.id.label_btn_1, R.id.label_btn_2, R.id.label_btn_3, R.id.label_btn_4, R.id.label_btn_5, R.id.label_btn_6)

    /** Builds the moment's context from the last location fix, zones/places and activity. */
    suspend fun currentContext(context: Context, at: Long): Pair<LabelContext, String?> {
        val db = PulseLoopDatabase.getInstance(context)
        val point = db.locationPointDao().latest()?.takeIf { at - it.timestamp <= CONTEXT_MAX_AGE_MS }
        val activity = db.activityTransitionDao().latestEnter()?.takeIf { at - it.timestamp <= 3 * 3_600_000L }?.activityType
        val place = point?.let { ZoneMatcher.match(it.latitude, it.longitude, db.zoneDao().active(), ContextPrefs.get(context).places.value) }
        return LabelContext(at = at, activity = activity, placeCategory = place?.category, speedMps = point?.speed) to place?.name
    }

    suspend fun onEpisode(context: Context, at: Long, episodeId: String?, transitionId: String?) {
        val app = context.applicationContext
        val prefs = AdaptivePrefs.get(app)
        val policy = prefs.settings.value.promptPolicy()
        val labelled = episodeId != null && PulseLoopDatabase.getInstance(app).activityLabelDao().forEpisode(episodeId) != null
        val now = System.currentTimeMillis()
        if (!policy.shouldPrompt(now, prefs.lastLabelPromptAt, labelled)) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val (ctx, placeName) = currentContext(app, at)
        val suggestion = LabelSuggester.suggest(ctx)
        post(app, at, episodeId, transitionId, suggestion, LabelSuggester.describe(ctx, placeName))
        prefs.lastLabelPromptAt = now
    }

    private fun labelIntent(context: Context, requestCode: Int, category: LabelCategory?, at: Long, episodeId: String?, transitionId: String?, suggested: LabelCategory?, mutable: Boolean): PendingIntent {
        val intent = Intent(context, LabelActionReceiver::class.java).apply {
            action = ACTION_LABEL
            category?.let { putExtra(EXTRA_CATEGORY, it.code) }
            putExtra(EXTRA_AT, at)
            putExtra(EXTRA_EPISODE, episodeId)
            putExtra(EXTRA_TRANSITION, transitionId)
            putExtra(EXTRA_SUGGESTED, suggested?.code)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun post(context: Context, at: Long, episodeId: String?, transitionId: String?, suggested: LabelCategory?, where: String?) {
        createChannel(context)
        val big = RemoteViews(context.packageName, R.layout.notification_label_prompt)
        LabelCategory.buttons.forEachIndexed { i, category ->
            val text = if (category == suggested) "★ ${category.label}" else category.label
            big.setTextViewText(buttonIds[i], text)
            big.setOnClickPendingIntent(buttonIds[i], labelIntent(context, 2300 + i, category, at, episodeId, transitionId, suggested, mutable = false))
        }
        val other = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "Altro…",
            labelIntent(context, 2390, LabelCategory.OTHER, at, episodeId, transitionId, suggested, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Cosa stai facendo?").build()).build()

        val open = DeepLinks.intent(context, "labels")
        val text = buildString {
            append(if (suggested != null) "Tocca per confermare: ${suggested.label}" else "Un tocco per etichettare questo momento")
            where?.let { append(" · ").append(it) }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Cosa stai facendo?")
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(big)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(PendingIntent.getActivity(context, 2399, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        suggested?.let {
            builder.addAction(0, "✓ ${it.label}", labelIntent(context, 2380, it, at, episodeId, transitionId, suggested, mutable = false))
        }
        builder.addAction(other)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            android.util.Log.w("LabelPrompter", "notification permission missing", e)
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cosa stai facendo?", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Chiede un'etichetta quando l'anello rileva un possibile episodio di stress"
            },
        )
    }

    /** The row a notification answer becomes. */
    fun labelFrom(
        category: LabelCategory,
        text: String?,
        at: Long,
        episodeId: String?,
        transitionId: String?,
        suggested: String?,
        now: Long,
    ) = ActivityLabelEntity(
        timestamp = at,
        category = category.code,
        text = text?.trim()?.takeIf { it.isNotEmpty() && category == LabelCategory.OTHER },
        suggested = suggested,
        source = "notification",
        episodeId = episodeId,
        transitionId = transitionId,
        createdAt = now,
        updatedAt = now,
    )
}

class LabelActionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LabelPrompter.ACTION_LABEL) return
        val category = LabelCategory.of(intent.getStringExtra(LabelPrompter.EXTRA_CATEGORY)) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(LabelPrompter.REMOTE_INPUT_KEY)?.toString()
        if (category == LabelCategory.OTHER && text.isNullOrBlank()) return
        val row = LabelPrompter.labelFrom(
            category = category,
            text = text,
            at = intent.getLongExtra(LabelPrompter.EXTRA_AT, System.currentTimeMillis()),
            episodeId = intent.getStringExtra(LabelPrompter.EXTRA_EPISODE),
            transitionId = intent.getStringExtra(LabelPrompter.EXTRA_TRANSITION),
            suggested = intent.getStringExtra(LabelPrompter.EXTRA_SUGGESTED),
            now = System.currentTimeMillis(),
        )
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                PulseLoopDatabase.getInstance(app).activityLabelDao().upsert(row)
                LabelPrompter.cancel(app)
                com.pulseloop.remote.RingHubSyncWorker.enqueue(app)
            } finally {
                pending.finish()
            }
        }
    }
}
