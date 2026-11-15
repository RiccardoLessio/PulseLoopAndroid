package com.pulseloop.service

/**
 * App-side "possible sleep" detector — the naps and sofa dozing the ring does not record.
 *
 * The Colmi R09 reports only the main night (big-data `0x27`); its nap record (`0x3E`) always comes
 * back empty (`bc3e0100bf4000`), so a 45-minute afternoon nap or an evening doze before bed is never
 * a sleep session. What the app does have is the ring's 5-minute all-day HR, hourly step buckets and
 * the phone's hourly screen-on minutes. A stretch outside every stored sleep session where HR sits
 * at the wearer's own sleeping level, nothing is walking and the screen is dark is a candidate the
 * user can confirm with one tap — it is never stored as sleep without that tap.
 *
 * Pure: no Android, no Room. [PossibleSleepSuggestions] feeds it and stores the answer.
 *
 * Robustness, because the input is noisy: about 20 % of the ring's in-sleep HR readings are
 * artifacts at 100–120 bpm. So the baseline is a low percentile of in-sleep HR (not a mean), and a
 * run tolerates artifact readings (≥ [Config.artifactBpm]) and a single isolated "awake-looking"
 * reading, while requiring most of its readings to be quiet.
 */
object PossibleSleepDetector {

    data class HrSample(val at: Long, val bpm: Double)
    data class Interval(val start: Long, val end: Long)
    data class Candidate(val start: Long, val end: Long, val medianBpm: Int, val thresholdBpm: Int) {
        val minutes: Int get() = ((end - start) / 60_000L).toInt()
    }

    data class Config(
        /** Shortest stretch worth suggesting. */
        val minMinutes: Int = 30,
        /** How far above the sleeping baseline a reading may sit and still count as quiet. */
        val marginBpm: Double = 5.0,
        /** Percentile of in-sleep HR taken as the sleeping level (robust to the 100+ artifacts). */
        val baselinePercentile: Double = 0.30,
        /** Fewer in-sleep readings than this (≈ 5 h at 5 min) and there is no baseline to trust. */
        val minBaselineSamples: Int = 60,
        /** At or above this a reading is treated as a sensor artifact, not as being awake. */
        val artifactBpm: Double = 100.0,
        /** Consecutive "awake" readings (between threshold and artifact) a run may absorb. */
        val maxAwakeRun: Int = 1,
        /** Share of a run's readings that must be quiet. */
        val minQuietFraction: Double = 0.6,
        /** A hole in the HR series longer than this ends a run (the ring samples every 5 min). */
        val maxGapMs: Long = 15 * 60_000L,
        /** The ring's sampling slot: a run's end is its last quiet reading plus one slot. */
        val slotMs: Long = 5 * 60_000L,
        /** Steps allowed in an hour bucket the candidate covers entirely ("~0 steps"). */
        val maxStepsFullHour: Int = 30,
        /** Each minute of the hour outside the candidate may add this many steps (≈ walking pace). */
        val stepsPerUncoveredMinute: Int = 100,
        /** Screen-on minutes allowed in an hour the candidate covers entirely ("screen ~off"). */
        val maxScreenMinutesFullHour: Double = 2.0,
    )

    private const val HOUR_MS = 3_600_000L

    /** The wearer's sleeping HR level from readings taken inside stored sleep sessions, or null. */
    fun baseline(inSleepBpm: List<Double>, config: Config = Config()): Double? {
        if (inSleepBpm.size < config.minBaselineSamples) return null
        val sorted = inSleepBpm.sorted()
        return sorted[(config.baselinePercentile * (sorted.size - 1)).toInt()]
    }

    /**
     * Candidates in [hr] (any order) outside [sleeps] at or below [baselineBpm] + margin, vetoed by
     * [stepsByHour] / [screenMinutesByHour] (keys = local hour start, epoch millis; a missing hour
     * counts as unknown and does not veto). Earliest first.
     */
    fun detect(
        hr: List<HrSample>,
        sleeps: List<Interval>,
        stepsByHour: Map<Long, Int>,
        screenMinutesByHour: Map<Long, Double>,
        baselineBpm: Double,
        config: Config = Config(),
    ): List<Candidate> {
        val threshold = baselineBpm + config.marginBpm
        val outside = hr.sortedBy { it.at }.filter { s -> sleeps.none { s.at >= it.start && s.at < it.end } }
        val runs = mutableListOf<List<HrSample>>()
        var current = mutableListOf<HrSample>()
        var awake = 0
        fun close() {
            while (current.isNotEmpty() && current.last().bpm > threshold) current.removeAt(current.lastIndex)
            if (current.isNotEmpty()) runs += current
            current = mutableListOf()
            awake = 0
        }
        for (s in outside) {
            if (current.isNotEmpty() && s.at - current.last().at > config.maxGapMs) close()
            when {
                s.bpm <= threshold -> { awake = 0; current += s }
                s.bpm >= config.artifactBpm -> { if (current.isNotEmpty()) current += s }
                else -> {
                    awake++
                    if (awake > config.maxAwakeRun) close() else if (current.isNotEmpty()) current += s
                }
            }
        }
        close()

        return runs.mapNotNull { run ->
            val start = run.first().at
            val end = run.last().at + config.slotMs
            if (end - start < config.minMinutes * 60_000L) return@mapNotNull null
            // The run must not reach into a stored session (its last slot can).
            if (sleeps.any { start < it.end && end > it.start }) return@mapNotNull null
            val quiet = run.filter { it.bpm <= threshold }
            if (quiet.size < config.minQuietFraction * run.size) return@mapNotNull null
            if (!quietPhone(start, end, stepsByHour, screenMinutesByHour, config)) return@mapNotNull null
            val sortedQuiet = quiet.map { it.bpm }.sorted()
            Candidate(
                start = start,
                end = end,
                medianBpm = sortedQuiet[sortedQuiet.size / 2].toInt(),
                thresholdBpm = threshold.toInt(),
            )
        }
    }

    /** Steps and screen in every hour the interval touches stay within what its uncovered minutes allow. */
    internal fun quietPhone(
        start: Long,
        end: Long,
        stepsByHour: Map<Long, Int>,
        screenMinutesByHour: Map<Long, Double>,
        config: Config = Config(),
    ): Boolean {
        var hour = Math.floorDiv(start, HOUR_MS) * HOUR_MS
        while (hour < end) {
            val covered = (minOf(end, hour + HOUR_MS) - maxOf(start, hour)) / 60_000.0
            val uncovered = 60.0 - covered
            stepsByHour[hour]?.let { if (it > config.maxStepsFullHour + config.stepsPerUncoveredMinute * uncovered) return false }
            screenMinutesByHour[hour]?.let { if (it > config.maxScreenMinutesFullHour + uncovered) return false }
            hour += HOUR_MS
        }
        return true
    }

    /** True when [candidate] overlaps any interval the user already answered (confirmed or ignored). */
    fun alreadyAnswered(candidate: Candidate, answered: List<Interval>): Boolean =
        answered.any { candidate.start < it.end && candidate.end > it.start }
}
