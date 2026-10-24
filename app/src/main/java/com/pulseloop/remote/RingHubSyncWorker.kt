package com.pulseloop.remote

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.BuildConfig
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Pushes the local deltas to ring-hub ([RingHubSync]). Read-only on the database: nothing is
 * deleted or marked on the phone, so an unreachable server (PC off) just means the rows go on a
 * later pass.
 *
 * Mirrors [com.pulseloop.health.HealthConnectExportWorker]: a plain worker, triggers debounced
 * through unique work with [ExistingWorkPolicy.REPLACE] (fired after each completed ring sync),
 * plus an hourly periodic pass. Both need a network connection; an unreachable server or a 5xx
 * returns [Result.retry] with exponential backoff, an auth or validation error does not retry.
 */
class RingHubSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = RingHubPrefsStore.get(applicationContext)
        val prefs = store.current
        if (!prefs.enabled || prefs.serverUrl.isBlank()) return Result.success()
        val token = RingHubTokenStore(applicationContext).token
        if (token.isBlank()) {
            store.update { it.copy(lastAttemptAt = System.currentTimeMillis(), lastOk = false, lastStatus = "Token mancante") }
            return Result.success()
        }

        val client = try {
            RingHubClient(prefs.serverUrl, token)
        } catch (e: IllegalArgumentException) {
            store.update { it.copy(lastAttemptAt = System.currentTimeMillis(), lastOk = false, lastStatus = "Indirizzo non valido") }
            return Result.success()
        }
        val db = PulseLoopDatabase.getInstance(applicationContext)
        return passMutex.withLock {
            val started = System.currentTimeMillis()
            try {
                val result = RingHubSync(
                    source = RoomRingHubSource(db),
                    watermarks = store,
                    appVersion = BuildConfig.VERSION_NAME,
                    send = { client.postBatch(it) },
                ).run()
                val summary = if (result.totalRows == 0) "Nessun dato nuovo"
                else "Inviate ${result.totalRows} righe in ${result.requests} richieste"
                store.update {
                    it.copy(lastAttemptAt = started, lastSuccessAt = System.currentTimeMillis(), lastOk = true, lastStatus = summary)
                }
                Log.i(TAG, "pass done: $summary ${result.rows}")
                Result.success()
            } catch (e: RingHubException) {
                val status = when (e.kind) {
                    RingHubException.Kind.UNREACHABLE -> "Server non raggiungibile, riprovo più tardi"
                    RingHubException.Kind.UNAUTHORIZED -> "Token rifiutato dal server (HTTP ${e.httpCode})"
                    RingHubException.Kind.SERVER -> "Errore del server (HTTP ${e.httpCode}), riprovo più tardi"
                    RingHubException.Kind.REJECTED -> "Richiesta rifiutata (HTTP ${e.httpCode})"
                }
                store.update { it.copy(lastAttemptAt = started, lastOk = false, lastStatus = status) }
                Log.w(TAG, "pass stopped: ${e.message}")
                if (e.retryable) Result.retry() else Result.failure()
            } catch (e: kotlinx.serialization.SerializationException) {
                store.update { it.copy(lastAttemptAt = started, lastOk = false, lastStatus = "Risposta del server non riconosciuta") }
                Log.w(TAG, "unexpected response", e)
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "RingHubSync"
        private const val WORK_NAME = "ringhub_sync"
        private const val PERIODIC_WORK_NAME = "ringhub_sync_periodic"
        private const val DEBOUNCE_SECONDS = 15L
        private const val PERIOD_MINUTES = 60L
        private const val BACKOFF_SECONDS = 60L

        /** Serializes a triggered pass against a periodic one; both run in the app process. */
        private val passMutex = Mutex()

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Debounced pass — cheap to call from every sync trigger; a no-op while disabled. */
        fun enqueue(context: Context, delaySeconds: Long = DEBOUNCE_SECONDS) {
            if (!RingHubPrefsStore.get(context).current.enabled) return
            val request = OneTimeWorkRequestBuilder<RingHubSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Hourly pass while enabled ([ExistingPeriodicWorkPolicy.KEEP], so safe on every app start). */
        fun schedulePeriodic(context: Context) {
            if (!RingHubPrefsStore.get(context).current.enabled) return
            val request = PeriodicWorkRequestBuilder<RingHubSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}

/** [RingHubSource] over Room. */
internal class RoomRingHubSource(private val db: PulseLoopDatabase) : RingHubSource {
    override suspend fun deviceId(): String? = db.deviceDao().currentReal()?.id
    override suspend fun devicesUpdatedSince(ts: Long) = db.deviceDao().realUpdatedSince(ts)
    override suspend fun measurementsAfter(ts: Long, key: String, limit: Int) =
        db.measurementDao().realCreatedAfter(ts, key, limit)
    override suspend fun deletionsAfter(ts: Long, key: String, limit: Int) =
        db.measurementDeletionDao().deletedAfter(ts, key, limit)
    override suspend fun activityDailyAfter(ts: Long, key: String, limit: Int) =
        db.activityDailyDao().realUpdatedAfter(ts, key, limit)
    override suspend fun activityBucketsAfter(ts: Long, startEpoch: Long, limit: Int) =
        db.activityBucketDao().updatedAfter(ts, startEpoch, limit)
    override suspend fun batterySamplesAfter(ts: Long, key: String, limit: Int) =
        db.batterySampleDao().createdAfter(ts, key, limit)
    override suspend fun sleepSessionsAfter(ts: Long, key: String, limit: Int) =
        db.sleepSessionDao().realUpdatedAfter(ts, key, limit)
    override suspend fun stageBlocksFor(sessionIds: List<String>) =
        // SQLite caps bound parameters (999 on older builds); query in slices.
        sessionIds.chunked(500).flatMap { db.sleepStageBlockDao().forSessions(it) }
}
