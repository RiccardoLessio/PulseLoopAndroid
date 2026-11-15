package com.pulseloop.service

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.ring.SleepStage
import com.pulseloop.util.TimeUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feeds [PossibleSleepDetector] from Room and stores the user's one-tap answer.
 *
 * - **Confirm** writes a sleep session with `sourceRaw = "inferred"` and one [SleepStage.ADDED]
 *   block (asleep, unstaged — the same stage an edit's added minutes use, so it counts in time asleep
 *   and stays out of every stage score). The ring sync's reconcile ignores inferred sessions
 *   (`SleepSessionDao.ringSyncedAllByDay`), so a re-sync cannot merge or wipe them; a ring record
 *   that later covers the same time replaces it.
 * - **Ignore** remembers the interval, so neither it nor an overlapping re-detection reappears.
 *
 * Answers live in a small prefs list (pruned after [KEEP_ANSWERS_MS]), not in Room: they are a UI
 * memory, not health data.
 */
class PossibleSleepSuggestions(
    private val db: PulseLoopDatabase,
    private val prefs: SharedPreferences,
    private val config: PossibleSleepDetector.Config = PossibleSleepDetector.Config(),
) {
    companion object {
        const val SOURCE_INFERRED = "inferred"
        /** Only recent stretches are suggested: the user has to remember them to answer. */
        const val LOOKBACK_MS = 48 * 3_600_000L
        /** A stretch still in progress (or just ended) is not suggested yet. */
        const val SETTLE_MS = 20 * 60_000L
        private const val BASELINE_NIGHTS_MS = 14 * 86_400_000L
        private const val KEEP_ANSWERS_MS = 30 * 86_400_000L
        private const val KEY_ANSWERS = "answers"

        fun create(context: Context, db: PulseLoopDatabase): PossibleSleepSuggestions =
            PossibleSleepSuggestions(db, context.applicationContext.getSharedPreferences("possible_sleep", Context.MODE_PRIVATE))
    }

    /** The most recent unanswered candidate, or null. */
    suspend fun current(now: Long = System.currentTimeMillis()): PossibleSleepDetector.Candidate? {
        val from = now - LOOKBACK_MS
        val hrKind = com.pulseloop.ring.MeasurementKind.HEART_RATE.name
        // Baseline: ring HR history inside the ring's own sessions of the last 14 nights.
        val baselineSessions = db.sleepSessionDao().realOverlapping(now - BASELINE_NIGHTS_MS, now)
            .filter { it.sourceRaw != SOURCE_INFERRED }
        val history = db.measurementDao().rangeReal(hrKind, now - BASELINE_NIGHTS_MS, now)
            .filter { it.sourceRaw == "history" }
        val inSleep = history.filter { m -> baselineSessions.any { m.timestamp >= it.startAt && m.timestamp < it.endAt } }
            .map { it.value }
        val baseline = PossibleSleepDetector.baseline(inSleep, config) ?: return null

        val sleeps = db.sleepSessionDao().realOverlapping(from - 86_400_000L, now)
            .map { PossibleSleepDetector.Interval(it.startAt, it.endAt) }
        val hr = history.filter { it.timestamp >= from }.map { PossibleSleepDetector.HrSample(it.timestamp, it.value) }
        val steps = db.activityBucketDao().between(from - 3_600_000L, now)
            .groupBy({ it.startEpoch }, { it.steps }).mapValues { it.value.sum() }
        val screen = db.phoneUsageHourlyDao().between(from - 3_600_000L, now)
            .associate { it.hourStart to it.screenOnMinutes }
        val answered = answers(now)
        return PossibleSleepDetector.detect(hr, sleeps, steps, screen, baseline, config)
            .filter { it.end <= now - SETTLE_MS }
            .filterNot { PossibleSleepDetector.alreadyAnswered(it, answered) }
            .maxByOrNull { it.start }
    }

    /** Stores [candidate] as a sleep session. Returns false when a session meanwhile covers it. */
    suspend fun confirm(candidate: PossibleSleepDetector.Candidate, now: Long = System.currentTimeMillis()): Boolean {
        remember(candidate, "confirmed", now)
        return db.withTransaction {
            if (db.sleepSessionDao().realOverlapping(candidate.start, candidate.end).isNotEmpty()) return@withTransaction false
            val id = "inferred-${candidate.start}"
            db.sleepSessionDao().upsert(
                SleepSessionEntity(
                    id = id,
                    date = TimeUtil.wakingDayLocal(candidate.start),
                    startAt = candidate.start,
                    endAt = candidate.end,
                    totalMinutes = candidate.minutes,
                    score = null,
                    syncedAt = now,
                    sourceRaw = SOURCE_INFERRED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.sleepStageBlockDao().insert(
                SleepStageBlockEntity(
                    sessionId = id,
                    startAt = candidate.start,
                    startMinute = 0,
                    durationMinutes = candidate.minutes,
                    stageRaw = SleepStage.ADDED.name,
                    recordStartAt = candidate.start,
                ),
            )
            true
        }
    }

    fun ignore(candidate: PossibleSleepDetector.Candidate, now: Long = System.currentTimeMillis()) =
        remember(candidate, "ignored", now)

    private fun answers(now: Long): List<PossibleSleepDetector.Interval> =
        readAnswers().filter { it.optLong("end") > now - KEEP_ANSWERS_MS }
            .map { PossibleSleepDetector.Interval(it.optLong("start"), it.optLong("end")) }

    private fun readAnswers(): List<JSONObject> {
        val raw = prefs.getString(KEY_ANSWERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun remember(candidate: PossibleSleepDetector.Candidate, decision: String, now: Long) = synchronized(this) {
        val kept = readAnswers().filter { it.optLong("end") > now - KEEP_ANSWERS_MS }
        val arr = JSONArray()
        kept.forEach { arr.put(it) }
        arr.put(JSONObject().put("start", candidate.start).put("end", candidate.end).put("decision", decision).put("at", now))
        prefs.edit().putString(KEY_ANSWERS, arr.toString()).apply()
    }
}
