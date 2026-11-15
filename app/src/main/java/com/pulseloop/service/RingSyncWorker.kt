package com.pulseloop.service

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import androidx.work.ListenableWorker.Result
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.health.HealthConnectExportWorker
import com.pulseloop.remote.RingHubSyncWorker
import com.pulseloop.ring.PulseEvent
import com.pulseloop.ring.PulseEventBus
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.settings.ApiKeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic task that connects to the ring, pulls data, and disconnects.
 * Matches the official JRing app's background sync behavior.
 * Runs every ~30 minutes (minimum WorkManager interval with flex).
 */
class RingSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "ring_background_sync"
        private const val TAG = "RingSyncWorker"
        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_ALARM = "alarm"
        const val TRIGGER_PERIODIC = "periodic"

        /**
         * Schedule background ring sync: the exact-alarm clock ([RingSyncAlarm], primary on MagicOS)
         * plus this periodic work as the fallback. Call from app start and after boot/update.
         */
        fun schedule(context: Context) {
            RingSyncAlarm.schedule(context)
            val request = PeriodicWorkRequestBuilder<RingSyncWorker>(
                30, TimeUnit.MINUTES,  // repeat interval
                15, TimeUnit.MINUTES,  // flex interval
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            RingSyncAlarm.cancel(context)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result =
        RingBackgroundSync.run(applicationContext, inputData.getString(KEY_TRIGGER) ?: TRIGGER_PERIODIC)
}

/**
 * One background ring pass, shared by [RingSyncWorker] (periodic or expedited) and
 * [RingSyncAlarm] when it runs the pass in-process. A process-wide flag keeps two triggers from
 * opening two transient clients at once; the GATT-ownership guards below apply to every caller.
 */
object RingBackgroundSync {
    private const val SYNC_TIMEOUT_SECONDS = 45L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    suspend fun run(app: Context, trigger: String): Result {
        if (!running.compareAndSet(false, true)) {
            RingSyncAlarm.log(app, "Background sync ($trigger): skipped, another pass running")
            return Result.success()
        }
        var outcome = "error"
        try {
            return runPassLocked(app) { outcome = it }
        } finally {
            running.set(false)
            RingSyncAlarm.log(app, "Background sync ($trigger): $outcome")
        }
    }

    /** One pass; [report] receives a short outcome for the SYNC diagnostics log. */
    private suspend fun runPassLocked(app: Context, report: (String) -> Unit): Result {
        report("skipped, app in foreground")
        // The foreground app owns the live GATT. An overdue periodic job can start at the same
        // moment as MainActivity after process launch; opening a second client interleaves both
        // command queues and lets this worker's cleanup disconnect the UI-owned session.
        if (isAppForeground()) return Result.success()

        val keyStore = ApiKeyStore(app)
        val db = PulseLoopDatabase.getInstance(app)

        // Only sync if a REAL ring was previously paired — the seeded demo device row
        // must not trigger background BLE connects.
        val device = db.deviceDao().currentReal()
        report("skipped, no paired ring")
        if (device == null) return Result.success()

        // The adaptive monitor holds the app's own link while it samples; a second client would
        // fight it for GATT ownership. Its samples feed the evaluation directly.
        report("skipped, adaptive monitor holds the link")
        if (com.pulseloop.context.MonitorService.holdsRingLink) return Result.success()
        RingSyncAlarm.markAttempt(app)
        report("started")

        // A cold background process (the app was swiped away or killed — routine on MagicOS) has
        // no UI composition, so nothing would persist what this pass receives. Keep a subscriber
        // on the bus: the app's own when it exists, else a process-level one.
        (com.pulseloop.AppServices.existing()?.persistence
            ?: BackgroundPersistence.get(app)).start()

        val bleClient = RingBLEClient(app, transientOwner = true)

        // Load the persisted measurement config + profile up front so the connect
        // handshake pushes the user's saved settings. Null (never saved) makes the
        // engine seed from the ring's own pref reads instead of force-writing
        // defaults, so ring-side settings from the official app are preserved.
        val measurementSettings = loadPersistedMeasurementSettings(db)
        val profileValues = loadPersistedUserProfile(db, keyStore)

        var synced = false
        return try {
            withTimeout(SYNC_TIMEOUT_SECONDS * 1000L) {
                // Connect to last-known ring
                report("skipped, no BLE permission")
                if (!bleClient.hasPermissions()) return@withTimeout Result.success()
                report("connecting")

                var connected = false
                bleClient.onConnected = {
                    connected = true
                    // Run startup sync to pull activity, HR, sleep, etc.
                    val engine = bleClient.syncEngine
                    engine?.setMeasurementSettings(measurementSettings)
                    profileValues?.let { engine?.setUserProfile(it) }
                    engine?.runStartup()
                }

                bleClient.connectLastKnown()

                // Wait for connection and data to arrive
                var waited = 0L
                while (!connected && waited < 20_000L) {
                    delay(1000)
                    waited += 1000
                    if (isAppForeground()) { report("yielded to app"); return@withTimeout Result.success() }
                }

                if (!connected) {
                    report("ring not reached in 20 s")
                    return@withTimeout Result.success()
                }
                report("connected, yielded to app")

                // Let data stream in after connect, but yield ownership promptly if the app opens.
                repeat(15) {
                    delay(1000L)
                    if (isAppForeground()) return@withTimeout Result.success()
                }

                // Health Connect export trigger (Phase 1): the background sync has streamed its
                // data — run the (debounced) export pass. The foreground path triggers the same
                // worker via the SyncProgress("done") event, so both sync owners cover it.
                HealthConnectExportWorker.enqueue(app)
                RingHubSyncWorker.enqueue(app)
                synced = true
                report("synced")

                Result.success()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) report("timed out after ${SYNC_TIMEOUT_SECONDS} s")
            else report("failed: ${e.javaClass.simpleName}")
            Result.retry()
        } finally {
            // This client is worker-owned. Cancel its watchdog as well as closing GATT so it
            // cannot reconnect after doWork() has returned.
            val releasedConnection = bleClient.destroy()
            // A private client must not overwrite a foreground client's state during an ownership
            // race. If the app remains backgrounded, persist the worker's actual teardown.
            if (releasedConnection && !isAppForeground()) {
                PulseEventBus.publishBlocking(
                    PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
                )
            }
            // Adaptive sampling: this pass is the periodic check (see AdaptiveController). Only
            // after this worker's own client let go of GATT, so a monitor episode can connect.
            if (synced) com.pulseloop.adaptive.AdaptiveController.onSyncCompleted(app)
        }
    }

    suspend fun isAppForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
