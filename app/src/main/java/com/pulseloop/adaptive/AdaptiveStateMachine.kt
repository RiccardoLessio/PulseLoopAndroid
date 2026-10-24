package com.pulseloop.adaptive

import kotlinx.serialization.Serializable

/**
 * Adaptive sampling state machine — docs/analytics-design.md §4, with the absolute fallbacks the
 * design prescribes until personal baselines exist (§3: "before validity … absolute fallbacks for
 * triggers").
 *
 * Pure and clock-free: every input arrives in an [AdaptiveSample], every output is the next
 * [AdaptiveMemory] plus the transitions to log. The service, the sync hooks and the tests all
 * drive the same [step].
 *
 * ```
 * BASELINE  → WATCH      trigger T1 = rest AND (HR high OR stress high OR HRV drop), not asleep
 * WATCH     → ESCALATED  the next sample is still T1, the daily budget and the battery allow it
 * WATCH     → BASELINE   10 min without confirmation (or asleep / budget gone)
 * ESCALATED → COOLDOWN   exit thresholds held 3 min (hysteresis), 20 min cap, rest lost,
 *                        battery < 20 %, asleep, budget used up, or sampling interrupted
 * COOLDOWN  → BASELINE   after 30 min (60 min when the episode hit the cap)
 * ```
 *
 * Suppressed triggers (asleep, low battery, budget) are logged as BASELINE → BASELINE rows, at
 * most one per [AdaptiveConfig.suppressedLogIntervalMs], so the log can re-tune thresholds later
 * (§9 phase 3) without filling up overnight.
 */
enum class AdaptiveState { BASELINE, WATCH, ESCALATED, COOLDOWN }

@Serializable
data class AdaptiveConfig(
    val dailyBudgetMinutes: Int = 90,
    val capMinutes: Int = 20,
    val cooldownMinutes: Int = 30,
    val cappedCooldownMinutes: Int = 60,
    val watchTimeoutMinutes: Int = 10,
    val exitHoldMinutes: Int = 3,
    val minBatteryPercent: Int = 20,
    /** Trigger: HR ≥ this, or ≥ [hrDeltaAboveRest] above the 3 h resting median. */
    val hrAbsolute: Int = 100,
    val hrDeltaAboveRest: Int = 20,
    /** Exit: HR below both of these (z < 1 ≈ half the entry offset). */
    val hrExitAbsolute: Int = 90,
    val hrExitDeltaAboveRest: Int = 10,
    val stressHigh: Int = 70,
    val stressExit: Int = 55,
    val hrvDropRatio: Double = 0.7,
    /** Rest = steps in the previous 10 min ≤ this and none in the previous 2 min. */
    val restMaxSteps10: Int = 10,
    /** An ESCALATED episode with no sample for this long was interrupted (service killed). */
    val staleMs: Long = 5 * 60_000L,
    /** Longest gap between two samples that still counts towards the daily budget. */
    val maxAccrualMs: Long = 3 * 60_000L,
    val suppressedLogIntervalMs: Long = 30 * 60_000L,
)

/** One evaluation's view of the world. Null = not known (never treated as "normal"). */
@Serializable
data class AdaptiveSample(
    val at: Long,
    val hr: Int? = null,
    /** Median of the last 3 h of resting HR samples (null when too few). */
    val restingHrMedian: Double? = null,
    val stress: Int? = null,
    val hrv: Int? = null,
    /** Median of the last 6 resting HRV samples (null when too few). */
    val hrvMedian: Double? = null,
    val stepsPrev10: Int? = null,
    val stepsPrev2: Int? = null,
    val asleep: Boolean = false,
    val batteryPercent: Int? = null,
)

@Serializable
data class AdaptiveMemory(
    val state: AdaptiveState = AdaptiveState.BASELINE,
    val stateSince: Long = 0L,
    val episodeId: String? = null,
    val watchStartedAt: Long? = null,
    val escalatedAt: Long? = null,
    val exitOkSince: Long? = null,
    val cooldownUntil: Long? = null,
    val capped: Boolean = false,
    /** Local-day start the budget counter belongs to. */
    val budgetDay: Long = 0L,
    val budgetUsedMs: Long = 0L,
    val lastSampleAt: Long? = null,
    val lastSuppressedAt: Long? = null,
)

data class AdaptiveTransition(
    val at: Long,
    val from: AdaptiveState,
    val to: AdaptiveState,
    val reason: String,
    val episodeId: String?,
    val sample: AdaptiveSample,
    val budgetUsedMs: Long,
) {
    val suppressed: Boolean get() = from == to
}

data class AdaptiveStep(val memory: AdaptiveMemory, val transitions: List<AdaptiveTransition>) {
    val entered: AdaptiveState? get() = transitions.lastOrNull { !it.suppressed }?.to
}

object AdaptiveStateMachine {

    object Reason {
        const val COOLDOWN_DONE = "cooldown_done"
        const val CONFIRMED = "confirmed"
        const val UNCONFIRMED = "unconfirmed"
        const val RECOVERED = "recovered"
        const val CAP = "cap"
        const val REST_LOST = "rest_lost"
        const val BATTERY_LOW = "battery_low"
        const val ASLEEP = "asleep"
        const val BUDGET = "budget_exhausted"
        const val INTERRUPTED = "interrupted"
        const val DISABLED = "disabled"
        const val SUPPRESSED_PREFIX = "suppressed_"
    }

    /** Rest per §2 step 3; unknown step counts are not rest (a trigger needs positive evidence). */
    fun isRest(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean =
        s.stepsPrev10 != null && s.stepsPrev2 != null && s.stepsPrev10 <= cfg.restMaxSteps10 && s.stepsPrev2 == 0

    /** Movement evidence; unknown steps are not "rest lost" (an escalation is not ended on no data). */
    fun isMoving(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean =
        (s.stepsPrev10 ?: 0) > cfg.restMaxSteps10 || (s.stepsPrev2 ?: 0) > 0

    fun hrHigh(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean {
        val hr = s.hr ?: return false
        return hr >= cfg.hrAbsolute || (s.restingHrMedian != null && hr >= s.restingHrMedian + cfg.hrDeltaAboveRest)
    }

    fun stressHigh(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean = s.stress != null && s.stress >= cfg.stressHigh

    fun hrvDrop(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean {
        val hrv = s.hrv ?: return false
        val median = s.hrvMedian ?: return false
        return hrv <= median * cfg.hrvDropRatio
    }

    /** The trigger causes present in [s] (empty = none), e.g. `["hr_high", "stress_high"]`. */
    fun causes(s: AdaptiveSample, cfg: AdaptiveConfig): List<String> = buildList {
        if (hrHigh(s, cfg)) add("hr_high")
        if (stressHigh(s, cfg)) add("stress_high")
        if (hrvDrop(s, cfg)) add("hrv_drop")
    }

    fun trigger(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean = isRest(s, cfg) && causes(s, cfg).isNotEmpty()

    /**
     * Exit thresholds, with hysteresis below the entry ones. Null when the sample can't say (no HR):
     * then the exit timer neither starts nor resets.
     */
    fun exitOk(s: AdaptiveSample, cfg: AdaptiveConfig): Boolean? {
        val hr = s.hr ?: return null
        val hrOk = hr < cfg.hrExitAbsolute &&
            (s.restingHrMedian == null || hr < s.restingHrMedian + cfg.hrExitDeltaAboveRest)
        val stressOk = s.stress == null || s.stress < cfg.stressExit
        return hrOk && stressOk && !hrvDrop(s, cfg)
    }

    fun budgetMs(cfg: AdaptiveConfig): Long = cfg.dailyBudgetMinutes * 60_000L

    private fun batteryLow(s: AdaptiveSample, cfg: AdaptiveConfig) =
        s.batteryPercent != null && s.batteryPercent < cfg.minBatteryPercent

    /**
     * One evaluation. [dayStart] maps an instant to its local-day start (the budget resets there);
     * [newEpisodeId] names the episode a BASELINE → WATCH opens.
     */
    fun step(
        memory: AdaptiveMemory,
        s: AdaptiveSample,
        cfg: AdaptiveConfig,
        dayStart: (Long) -> Long,
        newEpisodeId: () -> String,
    ): AdaptiveStep {
        val now = s.at
        val out = mutableListOf<AdaptiveTransition>()
        var m = memory

        // Budget: accrue escalated time since the previous sample, then roll the day.
        if (m.state == AdaptiveState.ESCALATED && m.lastSampleAt != null) {
            val gap = (now - m.lastSampleAt!!).coerceIn(0L, cfg.maxAccrualMs)
            m = m.copy(budgetUsedMs = m.budgetUsedMs + gap)
        }
        val today = dayStart(now)
        if (m.budgetDay != today) m = m.copy(budgetDay = today, budgetUsedMs = 0L)
        val previousSampleAt = m.lastSampleAt
        m = m.copy(lastSampleAt = now)

        fun move(to: AdaptiveState, reason: String, base: AdaptiveMemory = m): AdaptiveMemory {
            out += AdaptiveTransition(now, base.state, to, reason, base.episodeId, s, base.budgetUsedMs)
            return base.copy(state = to, stateSince = now)
        }

        fun suppress(cause: String): AdaptiveMemory {
            val last = m.lastSuppressedAt
            if (last != null && now - last < cfg.suppressedLogIntervalMs) return m
            out += AdaptiveTransition(now, m.state, m.state, Reason.SUPPRESSED_PREFIX + cause, m.episodeId, s, m.budgetUsedMs)
            return m.copy(lastSuppressedAt = now)
        }

        val budgetLeft = m.budgetUsedMs < budgetMs(cfg)
        val trig = trigger(s, cfg)

        if (m.state == AdaptiveState.COOLDOWN) {
            if (now < (m.cooldownUntil ?: 0L)) return AdaptiveStep(m, out)
            m = move(AdaptiveState.BASELINE, Reason.COOLDOWN_DONE)
                .copy(episodeId = null, cooldownUntil = null, capped = false, exitOkSince = null)
        }

        when (m.state) {
            AdaptiveState.BASELINE -> if (trig) {
                m = when {
                    s.asleep -> suppress(Reason.ASLEEP)
                    batteryLow(s, cfg) -> suppress(Reason.BATTERY_LOW)
                    !budgetLeft -> suppress(Reason.BUDGET)
                    else -> {
                        val opened = m.copy(episodeId = newEpisodeId())
                        move(AdaptiveState.WATCH, causes(s, cfg).joinToString("+"), opened)
                            .copy(watchStartedAt = now, escalatedAt = null, exitOkSince = null)
                    }
                }
            }

            AdaptiveState.WATCH -> {
                val started = m.watchStartedAt ?: now
                m = when {
                    s.asleep -> closeEpisode(move(AdaptiveState.BASELINE, Reason.ASLEEP))
                    trig && batteryLow(s, cfg) -> closeEpisode(move(AdaptiveState.BASELINE, Reason.BATTERY_LOW))
                    trig && !budgetLeft -> closeEpisode(move(AdaptiveState.BASELINE, Reason.BUDGET))
                    trig -> move(AdaptiveState.ESCALATED, Reason.CONFIRMED).copy(escalatedAt = now, exitOkSince = null)
                    now - started > cfg.watchTimeoutMinutes * 60_000L ->
                        closeEpisode(move(AdaptiveState.BASELINE, Reason.UNCONFIRMED))
                    else -> m
                }
            }

            AdaptiveState.ESCALATED -> {
                val since = m.escalatedAt ?: now
                val exit = exitOk(s, cfg)
                val exitSince = when (exit) {
                    true -> m.exitOkSince ?: now
                    false -> null
                    null -> m.exitOkSince
                }
                m = m.copy(exitOkSince = exitSince)
                val stale = previousSampleAt != null && now - previousSampleAt > cfg.staleMs
                val reason = when {
                    stale -> Reason.INTERRUPTED
                    batteryLow(s, cfg) -> Reason.BATTERY_LOW
                    s.asleep -> Reason.ASLEEP
                    isMoving(s, cfg) -> Reason.REST_LOST
                    !budgetLeft -> Reason.BUDGET
                    now - since >= cfg.capMinutes * 60_000L -> Reason.CAP
                    exitSince != null && now - exitSince >= cfg.exitHoldMinutes * 60_000L -> Reason.RECOVERED
                    else -> null
                }
                if (reason != null) {
                    val capped = reason == Reason.CAP
                    val minutes = if (capped) cfg.cappedCooldownMinutes else cfg.cooldownMinutes
                    m = move(AdaptiveState.COOLDOWN, reason)
                        .copy(cooldownUntil = now + minutes * 60_000L, capped = capped, exitOkSince = null)
                }
            }

            AdaptiveState.COOLDOWN -> Unit   // still cooling down: handled above
        }
        return AdaptiveStep(m, out)
    }

    /**
     * The phone could not keep sampling (ring out of reach, link refused, service not allowed to
     * start). An escalation cools down as usual; a WATCH just closes.
     */
    fun interrupt(memory: AdaptiveMemory, s: AdaptiveSample, cfg: AdaptiveConfig, reason: String): AdaptiveStep = when (memory.state) {
        AdaptiveState.ESCALATED -> {
            val t = AdaptiveTransition(s.at, memory.state, AdaptiveState.COOLDOWN, reason, memory.episodeId, s, memory.budgetUsedMs)
            AdaptiveStep(
                memory.copy(state = AdaptiveState.COOLDOWN, stateSince = s.at, cooldownUntil = s.at + cfg.cooldownMinutes * 60_000L, exitOkSince = null),
                listOf(t),
            )
        }
        AdaptiveState.WATCH -> {
            val t = AdaptiveTransition(s.at, memory.state, AdaptiveState.BASELINE, reason, memory.episodeId, s, memory.budgetUsedMs)
            AdaptiveStep(closeEpisode(memory.copy(state = AdaptiveState.BASELINE, stateSince = s.at)), listOf(t))
        }
        else -> AdaptiveStep(memory, emptyList())
    }

    /** Leaving WATCH/ESCALATED/COOLDOWN because the mode was switched off. */
    fun disable(memory: AdaptiveMemory, s: AdaptiveSample): AdaptiveStep {
        if (memory.state == AdaptiveState.BASELINE) return AdaptiveStep(memory, emptyList())
        val t = AdaptiveTransition(s.at, memory.state, AdaptiveState.BASELINE, Reason.DISABLED, memory.episodeId, s, memory.budgetUsedMs)
        return AdaptiveStep(closeEpisode(memory.copy(state = AdaptiveState.BASELINE, stateSince = s.at)), listOf(t))
    }

    private fun closeEpisode(m: AdaptiveMemory) =
        m.copy(episodeId = null, watchStartedAt = null, escalatedAt = null, exitOkSince = null, cooldownUntil = null, capped = false)

    /** Escalated minutes used today as of [now], including the episode in progress. */
    fun budgetUsedMinutes(m: AdaptiveMemory, now: Long, dayStart: (Long) -> Long, cfg: AdaptiveConfig): Int {
        if (m.budgetDay != dayStart(now)) return 0
        var used = m.budgetUsedMs
        if (m.state == AdaptiveState.ESCALATED && m.lastSampleAt != null) {
            used += (now - m.lastSampleAt).coerceIn(0L, cfg.maxAccrualMs)
        }
        return (used / 60_000L).toInt()
    }

    /** True while the phone has to hold the BLE link and drive spot measurements. */
    fun needsLink(state: AdaptiveState): Boolean = state == AdaptiveState.WATCH || state == AdaptiveState.ESCALATED
}
