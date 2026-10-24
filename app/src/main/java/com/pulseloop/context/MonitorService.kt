package com.pulseloop.context

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.pulseloop.AppServices
import com.pulseloop.adaptive.AdaptiveController
import com.pulseloop.adaptive.AdaptiveInputs
import com.pulseloop.adaptive.AdaptivePrefs
import com.pulseloop.adaptive.AdaptiveState
import com.pulseloop.adaptive.AdaptiveStateMachine
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.ring.SpotVital
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one foreground service behind the "Monitoraggio attivo" notification. It runs only while
 * something needs it, with only the foreground-service types that thing needs:
 *
 * - **connectedDevice** while adaptive sampling is in WATCH or ESCALATED: it holds the app's own
 *   ring link (the UI would drop it 45 s after backgrounding) and drives spot measurements —
 *   WATCH: HR + stress at entry, an HR re-check every 4 min until confirmed or 10 min pass;
 *   ESCALATED: HR every minute, HRV + stress every 3 min, no SpO₂. Each result goes straight into
 *   [AdaptiveController.evaluate]. When the episode ends the link is released and the app falls
 *   back to its normal behaviour (30-minute background sync).
 * - **location** while automatic location tracking is on ([LocationTracker]).
 *
 * Starting a foreground service from the background is restricted on Android 12+; the start that
 * follows a background sync works only when the app is exempt from battery optimisation (which
 * MagicOS needs anyway). A refused start closes the episode as `fgs_denied` rather than retrying.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        const val CHANNEL_ID = "monitor"
        const val NOTIFICATION_ID = 2101
        private const val WATCH_RECHECK_MS = 4 * 60_000L
        private const val ESCALATED_HR_EVERY_MS = 60_000L
        private const val ESCALATED_SLOW_EVERY_MS = 3 * 60_000L
        private const val CONNECT_WAIT_MS = 45_000L
        private const val SYNC_WAIT_MS = 90_000L
        private const val LIVE_STEPS_KEEP_MS = 30 * 60_000L

        /** True while the adaptive session owns the ring link (the UI and the sync worker defer). */
        @Volatile var holdsRingLink: Boolean = false
            private set

        @Volatile private var instance: MonitorService? = null

        private val LOCATION_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        private val LINK_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun needsLink(context: Context): Boolean {
            val prefs = AdaptivePrefs.get(context)
            return prefs.settings.value.enabled && AdaptiveStateMachine.needsLink(prefs.memory.value.state)
        }

        fun needsLocation(context: Context): Boolean =
            ContextPrefs.get(context).settings.value.locationEnabled && ContextPermissions.hasForegroundLocation(context)

        /**
         * A location-type foreground service started from the background needs "Consenti sempre";
         * with only "while in use" it may run only while the app is visible.
         */
        private fun locationAllowedNow(context: Context): Boolean =
            ContextPermissions.hasBackgroundLocation(context) ||
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        /** Start, update or stop the service to match what is needed right now. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val link = needsLink(app)
            val location = needsLocation(app)
            val intent = Intent(app, MonitorService::class.java)
            if (!link && !location) {
                if (instance != null) app.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException.
                Log.w(TAG, "foreground service refused", e)
                if (link) bgScope.launch { AdaptiveController.interrupt(app, "fgs_denied") }
            }
        }

        fun onActivity(activity: String) {
            instance?.let { svc -> svc.mainScope.launch { svc.tracker.retune(activity) } }
        }
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker by lazy { LocationTracker(this) }
    private var session: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel(this)
    }

    /** The foreground types this instance currently holds; null while not in the foreground. */
    private var heldTypes: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val link = needsLink(this)
        // A background refresh can't newly acquire the location type (while-in-use rule), but a
        // service that already holds it keeps it: dropping it here would stop tracking until the
        // app next comes to the foreground.
        val holdsLocation = heldTypes?.let { it and LOCATION_TYPE != 0 } == true
        val location = needsLocation(this) && (locationAllowedNow(this) || holdsLocation)
        if (!link && !location) {
            // The need went away between refresh() and here.
            stopAnswered()
            return START_NOT_STICKY
        }
        val types = enterForeground(link, location)
        if (types == null) {
            val app = applicationContext
            if (link) bgScope.launch { AdaptiveController.interrupt(app, "fgs_denied") }
            stopAnswered()
            return START_NOT_STICKY
        }
        val trackLocation = location && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || types and LOCATION_TYPE != 0)
        if (trackLocation) {
            ioScope.launch {
                val activity = PulseLoopDatabase.getInstance(this@MonitorService).activityTransitionDao().latestEnter()?.activityType
                withContext(Dispatchers.Main) { if (tracker.isRunning) tracker.retune(activity) else tracker.start(activity) }
            }
        } else {
            mainScope.launch { tracker.stop() }
        }
        if (link && session?.isActive != true) session = ioScope.launch { runSession() }
        return START_STICKY
    }

    /**
     * Goes foreground with the types [link]/[location] need, falling back to the ring link alone
     * (location is the type Android refuses from the background). Returns the types obtained, or
     * null when no wanted type was allowed — the caller then stops via [stopAnswered].
     */
    private fun enterForeground(link: Boolean, location: Boolean): Int? {
        var types = 0
        if (location) types = types or LOCATION_TYPE
        if (link) types = types or LINK_TYPE
        // Already foreground with exactly these types: nothing to change, and no risky re-check
        // (Android clears a repeated startForegroundService()'s obligation for a service that is
        // already in the foreground).
        if (heldTypes == types && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return types
        if (tryStartForeground(types, link, location)) return types
        if (link && location && tryStartForeground(LINK_TYPE, link = true, location = false)) return LINK_TYPE
        return null
    }

    private fun tryStartForeground(types: Int, link: Boolean, location: Boolean): Boolean = try {
        val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(link, location), t)
        heldTypes = types
        true
    } catch (e: Exception) {
        // SecurityException / ForegroundServiceTypeException (API 34+: missing type permission,
        // location while-in-use not granted from the background).
        Log.w(TAG, "startForeground refused (types $types)", e)
        false
    }

    /**
     * Stops the service, answering a pending startForegroundService() first: Android kills the app
     * ("did not then call startForeground") when a service started that way stops without it.
     * Already in the foreground, the contract is met. Otherwise the call uses a type that needs no
     * runtime permission: shortService on API 34+ (where a typeless call is refused), none below.
     */
    private fun stopAnswered() {
        if (heldTypes == null) {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(link = false, location = false), type)
            } catch (e: Exception) {
                Log.w(TAG, "startForeground for stop refused", e)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        heldTypes = null
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        tracker.stop()
        session?.cancel()
        mainScope.cancel()
        ioScope.cancel()
        holdsRingLink = false
        super.onDestroy()
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition()) {
            if (waited >= timeoutMs) return false
            delay(1_000L)
            waited += 1_000L
        }
        return true
    }

    private suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private suspend fun runSession() {
        val services = AppServices.get(applicationContext)
        services.ensureCoreStarted()
        val coordinator = services.coordinator
        val ble = services.bleClient
        val prefs = AdaptivePrefs.get(this)
        holdsRingLink = true
        val steps = ArrayDeque<AdaptiveInputs.LiveSteps>()
        var battery: Int? = null
        val collector = ioScope.launch {
            PulseEventBus.events.collect { e ->
                when (e) {
                    is PulseEvent.ActivityUpdate -> synchronized(steps) {
                        steps.addLast(AdaptiveInputs.LiveSteps(e.timestamp.toEpochMilli(), e.steps))
                        while (steps.isNotEmpty() && steps.first().at < System.currentTimeMillis() - LIVE_STEPS_KEEP_MS) steps.removeFirst()
                    }
                    is PulseEvent.BatteryLevel -> battery = e.percent
                    else -> Unit
                }
            }
        }
        try {
            if (!coordinator.isConnected) ble.connectLastKnown()
            if (!waitUntil(CONNECT_WAIT_MS) { coordinator.isConnected }) {
                AdaptiveController.interrupt(this, "link_unavailable")
                return
            }
            // Let the connect-time history sync finish first: the ring serves one request at a time.
            waitUntil(SYNC_WAIT_MS) { coordinator.syncProgress.value == null }
            var lastSpotAt = 0L
            var lastSlowAt = 0L
            var watchEpisode: String? = null
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val memory = prefs.memory.value
                if (!prefs.settings.value.enabled || !AdaptiveStateMachine.needsLink(memory.state)) break
                if (!coordinator.isConnected) {
                    ble.connectLastKnown()
                    if (!waitUntil(30_000L) { coordinator.isConnected }) {
                        AdaptiveController.interrupt(this, "link_lost")
                        break
                    }
                }
                val now = System.currentTimeMillis()
                val live = { AdaptiveController.LiveReadings(battery = battery, steps = synchronized(steps) { steps.toList() }) }
                when (memory.state) {
                    AdaptiveState.WATCH -> {
                        val first = watchEpisode != memory.episodeId
                        if (first || now - lastSpotAt >= WATCH_RECHECK_MS) {
                            watchEpisode = memory.episodeId
                            lastSpotAt = now
                            val hr = coordinator.measureHeartRateOnly(AdaptiveController.SOURCE_SPOT)
                            val stress = if (first) measure(coordinator, SpotVital.STRESS) else null
                            AdaptiveController.evaluate(this, live().copy(hr = hr, stress = stress))
                        } else {
                            delay((WATCH_RECHECK_MS - (now - lastSpotAt)).coerceIn(1_000L, 30_000L))
                            // Re-evaluate on the stored data so the 10-minute timeout fires on time.
                            if (System.currentTimeMillis() - (memory.watchStartedAt ?: now) > 10 * 60_000L) {
                                AdaptiveController.evaluate(this, live())
                            }
                        }
                    }
                    AdaptiveState.ESCALATED -> {
                        if (now - lastSpotAt < ESCALATED_HR_EVERY_MS) {
                            delay(ESCALATED_HR_EVERY_MS - (now - lastSpotAt))
                            continue
                        }
                        lastSpotAt = now
                        val hr = coordinator.measureHeartRateOnly(AdaptiveController.SOURCE_SPOT)
                        var hrv: Int? = null
                        var stress: Int? = null
                        if (now - lastSlowAt >= ESCALATED_SLOW_EVERY_MS) {
                            lastSlowAt = now
                            hrv = measure(coordinator, SpotVital.HRV)
                            stress = measure(coordinator, SpotVital.STRESS)
                        }
                        AdaptiveController.evaluate(this, live().copy(hr = hr, hrv = hrv, stress = stress))
                    }
                    else -> break
                }
                updateNotification()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "session failed", e)
            AdaptiveController.interrupt(this, "error")
        } finally {
            collector.cancel()
            holdsRingLink = false
            // Runs even when onDestroy cancelled the session (the usual way an episode ends):
            // back to normal behaviour, the UI owns the link when visible, else let the ring sleep.
            withContext(NonCancellable) {
                if (!isAppForeground()) ble.disconnect()
            }
            refresh(applicationContext)
        }
    }

    private suspend fun measure(coordinator: com.pulseloop.service.RingSyncCoordinator, vital: SpotVital): Int? {
        if (vital !in coordinator.measurableSpotVitals) return null
        return coordinator.measureVitalOnly(vital, AdaptiveController.SOURCE_SPOT)?.let { kotlin.math.round(it).toInt() }
    }

    private fun updateNotification() {
        // The episode may have just ended and stopped the service: never post an orphan.
        if (instance == null || (!needsLink(this) && !needsLocation(this))) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(needsLink(this), needsLocation(this)))
    }

    private fun buildNotification(link: Boolean, location: Boolean): Notification {
        val state = AdaptivePrefs.get(this).memory.value.state
        val parts = buildList {
            if (link) add(if (state == AdaptiveState.ESCALATED) "misure ravvicinate dell'anello" else "verifica di un possibile episodio")
            if (location) add("posizione automatica")
        }
        val open = com.pulseloop.ui.DeepLinks.intent(this, "settings/adaptive")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Monitoraggio attivo")
            .setContentText(parts.joinToString(" · ").replaceFirstChar { it.uppercase() })
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 2101, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitoraggio attivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifica fissa mentre l'app misura più spesso o registra la posizione"
                setShowBadge(false)
            },
        )
    }
}
