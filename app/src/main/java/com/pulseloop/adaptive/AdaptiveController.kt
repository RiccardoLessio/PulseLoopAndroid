package com.pulseloop.adaptive

import android.content.Context
import android.util.Log
import com.pulseloop.context.MonitorService
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AdaptiveTransitionEntity
import com.pulseloop.labels.LabelPrompter
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.ZenModeTracker
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Glue between the stored readings, [AdaptiveStateMachine] and the side effects.
 *
 * **Where fresh samples come from (battery design).** The ring keeps logging HR every 5 min and
 * stress/HRV every 30 min on its own; the app never polls it just to look. An evaluation runs:
 *
 * 1. after every completed ring sync — foreground (`SyncProgress("done")`) and the 30-minute
 *    background `RingSyncWorker`, which is the "lightweight periodic check": it already pays for
 *    the BLE connection, the evaluation is a few Room reads on top;
 * 2. inside [MonitorService] while WATCH/ESCALATED hold the link, after every app-driven spot
 *    measurement ([evaluate] with [LiveReadings]).
 *
 * So in BASELINE the extra cost is zero radio time; detection latency is bounded by the background
 * sync period (15–30 min, more if MagicOS defers the job). Only a confirmed trigger pays for a
 * held link and spot measurements, within the daily budget.
 */
object AdaptiveController {
    private const val TAG = "AdaptiveController"
    const val SOURCE_SPOT = "adaptive_spot"
    private const val READ_BACK_MS = 49 * 3_600_000L
    private const val BUCKET_READ_BACK_MS = 4 * 3_600_000L
    private const val BATTERY_MAX_AGE_MS = 6 * 3_600_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = false }

    /** Readings just taken by the monitor; they win over Room (persistence lags behind the bus). */
    data class LiveReadings(
        val hr: Int? = null,
        val stress: Int? = null,
        val hrv: Int? = null,
        val battery: Int? = null,
        val steps: List<AdaptiveInputs.LiveSteps> = emptyList(),
    )

    fun onSyncCompleted(context: Context) {
        val app = context.applicationContext
        if (!AdaptivePrefs.get(app).settings.value.enabled) return
        scope.launch { runCatching { evaluate(app) }.onFailure { Log.w(TAG, "evaluation failed", it) } }
    }

    suspend fun evaluate(context: Context, live: LiveReadings? = null): AdaptiveStep? = mutex.withLock {
        val prefs = AdaptivePrefs.get(context)
        val settings = prefs.settings.value
        if (!settings.enabled) return@withLock null
        val now = System.currentTimeMillis()
        val sample = buildSample(context, now, live, evidenceSince(prefs.memory.value))
        val result = AdaptiveStateMachine.step(prefs.memory.value, sample, settings.config(), { TimeUtil.startOfDayLocal(it) }) {
            UUID.randomUUID().toString()
        }
        commit(context, prefs, result)
        result
    }

    /** The monitor could not hold the link (ring away, FGS refused): close the episode. */
    suspend fun interrupt(context: Context, reason: String) = mutex.withLock {
        val prefs = AdaptivePrefs.get(context)
        val memory = prefs.memory.value
        if (!AdaptiveStateMachine.needsLink(memory.state)) return@withLock
        val now = System.currentTimeMillis()
        val result = AdaptiveStateMachine.interrupt(memory, AdaptiveSample(now), prefs.settings.value.config(), reason)
        commit(context, prefs, result)
    }

    /** Switching the mode off ends any episode and releases the link. */
    suspend fun disable(context: Context) = mutex.withLock {
        val prefs = AdaptivePrefs.get(context)
        val result = AdaptiveStateMachine.disable(prefs.memory.value, AdaptiveSample(System.currentTimeMillis()))
        commit(context, prefs, result)
    }

    private suspend fun commit(context: Context, prefs: AdaptivePrefs, result: AdaptiveStep) {
        prefs.saveMemory(result.memory)
        val dao = PulseLoopDatabase.getInstance(context).adaptiveTransitionDao()
        for (t in result.transitions) {
            val row = AdaptiveTransitionEntity(
                timestamp = t.at, fromState = t.from.name, toState = t.to.name, reason = t.reason,
                episodeId = t.episodeId, valuesJson = valuesJson(t),
            )
            dao.insert(row)
            Log.i(TAG, "${t.from} -> ${t.to} (${t.reason})")
            val stressCause = AdaptiveStateMachine.stressHigh(t.sample, prefs.settings.value.config())
            when {
                t.from == AdaptiveState.WATCH && t.to == AdaptiveState.ESCALATED ->
                    LabelPrompter.onEpisode(context, at = result.memory.watchStartedAt ?: t.at, episodeId = t.episodeId, transitionId = row.id)
                // A stress episode the budget/battery kept us from escalating is still worth a label.
                t.suppressed && stressCause && !t.sample.asleep ->
                    LabelPrompter.onEpisode(context, at = t.at, episodeId = null, transitionId = row.id)
            }
        }
        MonitorService.refresh(context)
    }

    private fun valuesJson(t: AdaptiveTransition): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            t.sample.hr?.let { put("hr", it) }
            t.sample.restingHrMedian?.let { put("restingHrMedian", it) }
            t.sample.stress?.let { put("stress", it) }
            t.sample.hrv?.let { put("hrv", it) }
            t.sample.hrvMedian?.let { put("hrvMedian", it) }
            t.sample.stepsPrev10?.let { put("stepsPrev10", it) }
            t.sample.stepsPrev2?.let { put("stepsPrev2", it) }
            t.sample.batteryPercent?.let { put("battery", it) }
            put("asleep", t.sample.asleep)
            put("budgetUsedMin", t.budgetUsedMs / 60_000L)
        },
    )

    /**
     * While an episode runs, only readings taken after it began may confirm or end it: the stored
     * reading that opened WATCH (stress lives 35 min) would otherwise confirm it again with no new
     * evidence, and keep an escalation from ever recovering.
     */
    internal fun evidenceSince(memory: AdaptiveMemory): Long? = when (memory.state) {
        AdaptiveState.WATCH -> memory.watchStartedAt?.plus(1)
        AdaptiveState.ESCALATED -> memory.escalatedAt
        else -> null
    }

    private suspend fun buildSample(context: Context, now: Long, live: LiveReadings?, since: Long?): AdaptiveSample {
        val db = PulseLoopDatabase.getInstance(context)
        val read = db.adaptiveReadDao()
        fun readings(rows: List<com.pulseloop.data.entity.MeasurementEntity>) =
            rows.map { AdaptiveInputs.Reading(it.timestamp, it.value) }

        val hr = readings(read.readings("HEART_RATE", now - READ_BACK_MS)).toMutableList()
        val stress = readings(read.readings("STRESS", now - READ_BACK_MS)).toMutableList()
        val hrv = readings(read.readings("HRV", now - READ_BACK_MS)).toMutableList()
        if (since != null) {
            hr.removeAll { it.at < since }
            stress.removeAll { it.at < since }
            hrv.removeAll { it.at < since }
        }
        live?.hr?.let { hr += AdaptiveInputs.Reading(now, it.toDouble()) }
        live?.stress?.let { stress += AdaptiveInputs.Reading(now, it.toDouble()) }
        live?.hrv?.let { hrv += AdaptiveInputs.Reading(now, it.toDouble()) }
        // Medians come from the unbiased auto grid only: escalated spots would drag them upwards.
        val baselineHr = readings(read.baselineReadings("HEART_RATE", now - READ_BACK_MS))
        val baselineHrv = readings(read.baselineReadings("HRV", now - READ_BACK_MS))
        val buckets = read.buckets(now - BUCKET_READ_BACK_MS).map { AdaptiveInputs.StepBucket(it.startEpoch, it.steps) }
        val steps = live?.steps.orEmpty()
        val battery = live?.battery ?: read.latestBattery(now - BATTERY_MAX_AGE_MS)
        val bedtime = QuietHoursPrefs.isZenTrackingActive(context) && ZenModeTracker.isZenModeActive(context)

        val base = AdaptiveInputs.sample(
            now = now, hr = hr, stress = stress, hrv = hrv, buckets = buckets, live = steps,
            battery = battery, lastSleepEnd = read.lastSleepEnd(), bedtimeActive = bedtime,
        )
        // The reading under evaluation is not part of its own baseline.
        val currentHrAt = AdaptiveInputs.latestFresh(hr, now, AdaptiveInputs.HR_MAX_AGE_MS)?.at
        val currentHrvAt = AdaptiveInputs.latestFresh(hrv, now, AdaptiveInputs.SLOW_VITAL_MAX_AGE_MS)?.at
        return base.copy(
            restingHrMedian = AdaptiveInputs.restingHrMedian(baselineHr.filter { it.at != currentHrAt }, buckets, steps, now),
            hrvMedian = AdaptiveInputs.hrvMedian(baselineHrv, buckets, steps, now, excludeAt = currentHrvAt),
        )
    }
}
