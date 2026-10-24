package com.pulseloop.adaptive

import com.pulseloop.adaptive.AdaptiveState.BASELINE
import com.pulseloop.adaptive.AdaptiveState.COOLDOWN
import com.pulseloop.adaptive.AdaptiveState.ESCALATED
import com.pulseloop.adaptive.AdaptiveState.WATCH
import com.pulseloop.adaptive.AdaptiveStateMachine.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveStateMachineTest {

    private val cfg = AdaptiveConfig()
    private val min = 60_000L
    private val day = 24 * 60 * min
    private val t0 = 10 * day + 10 * 60 * min   // 10:00 on day 10 (UTC-day buckets in this test)
    private var episodes = 0

    private fun dayStart(t: Long) = t - t % day

    /** Resting, calm sample unless overridden. */
    private fun s(
        at: Long, hr: Int? = 70, rest: Double? = 68.0, stress: Int? = 30, hrv: Int? = 50, hrvMed: Double? = 50.0,
        steps10: Int? = 0, steps2: Int? = 0, asleep: Boolean = false, battery: Int? = 80,
    ) = AdaptiveSample(at, hr, rest, stress, hrv, hrvMed, steps10, steps2, asleep, battery)

    private fun high(at: Long, hr: Int = 105, battery: Int? = 80, asleep: Boolean = false) =
        s(at, hr = hr, battery = battery, asleep = asleep)

    private fun step(m: AdaptiveMemory, sample: AdaptiveSample, c: AdaptiveConfig = cfg) =
        AdaptiveStateMachine.step(m, sample, c, ::dayStart) { "ep-${++episodes}" }

    private fun run(m0: AdaptiveMemory, vararg samples: AdaptiveSample): Pair<AdaptiveMemory, List<AdaptiveTransition>> {
        var m = m0
        val all = mutableListOf<AdaptiveTransition>()
        for (x in samples) step(m, x).also { m = it.memory; all += it.transitions }
        return m to all
    }

    /** BASELINE → WATCH → ESCALATED at t0 / t0+1min. */
    private fun escalated(): AdaptiveMemory = run(AdaptiveMemory(), high(t0), high(t0 + min)).first

    // --- triggers ---------------------------------------------------------------------------

    @Test
    fun `calm resting sample stays in baseline`() {
        val r = step(AdaptiveMemory(), s(t0))
        assertEquals(BASELINE, r.memory.state)
        assertTrue(r.transitions.isEmpty())
    }

    @Test
    fun `absolute HR 100 at rest opens WATCH with a new episode`() {
        val r = step(AdaptiveMemory(), s(t0, hr = 100, rest = null))
        assertEquals(WATCH, r.memory.state)
        assertEquals("hr_high", r.transitions.single().reason)
        assertEquals("ep-1", r.memory.episodeId)
        assertEquals(t0, r.memory.watchStartedAt)
    }

    @Test
    fun `HR 20 above the 3h resting median triggers below 100`() {
        assertEquals(WATCH, step(AdaptiveMemory(), s(t0, hr = 80, rest = 60.0)).memory.state)
        assertEquals(BASELINE, step(AdaptiveMemory(), s(t0, hr = 79, rest = 60.0)).memory.state)
    }

    @Test
    fun `stress 70 and HRV drop are triggers too`() {
        assertEquals("stress_high", step(AdaptiveMemory(), s(t0, stress = 70)).transitions.single().reason)
        assertEquals("hrv_drop", step(AdaptiveMemory(), s(t0, hrv = 35, hrvMed = 50.0)).transitions.single().reason)
        assertEquals(BASELINE, step(AdaptiveMemory(), s(t0, hrv = 36, hrvMed = 50.0)).memory.state)
        assertEquals("hr_high+stress_high", step(AdaptiveMemory(), s(t0, hr = 120, stress = 80)).transitions.single().reason)
    }

    @Test
    fun `no trigger without rest context`() {
        assertEquals(BASELINE, step(AdaptiveMemory(), s(t0, hr = 120, steps10 = 11)).memory.state)
        assertEquals(BASELINE, step(AdaptiveMemory(), s(t0, hr = 120, steps10 = 5, steps2 = 1)).memory.state)
        assertEquals(BASELINE, step(AdaptiveMemory(), s(t0, hr = 120, steps10 = null)).memory.state)  // unknown ≠ rest
        assertEquals(WATCH, step(AdaptiveMemory(), s(t0, hr = 120, steps10 = 10, steps2 = 0)).memory.state)
    }

    // --- WATCH ------------------------------------------------------------------------------

    @Test
    fun `WATCH confirms into ESCALATED on the next triggering sample`() {
        val (m, ts) = run(AdaptiveMemory(), high(t0), high(t0 + min))
        assertEquals(ESCALATED, m.state)
        assertEquals(listOf(BASELINE to WATCH, WATCH to ESCALATED), ts.map { it.from to it.to })
        assertEquals(Reason.CONFIRMED, ts.last().reason)
        assertEquals("ep-1", ts.last().episodeId)
    }

    @Test
    fun `WATCH waits on calm samples and falls back after 10 minutes unconfirmed`() {
        val (m1, _) = run(AdaptiveMemory(), high(t0), s(t0 + 4 * min), s(t0 + 10 * min))
        assertEquals(WATCH, m1.state)
        val r = step(m1, s(t0 + 10 * min + 1))
        assertEquals(BASELINE, r.memory.state)
        assertEquals(Reason.UNCONFIRMED, r.transitions.single().reason)
        assertNull(r.memory.episodeId)
    }

    @Test
    fun `WATCH does not escalate when the budget is gone`() {
        val spent = AdaptiveMemory(budgetDay = dayStart(t0), budgetUsedMs = 90 * min, state = WATCH, watchStartedAt = t0, episodeId = "x")
        val r = step(spent, high(t0 + min))
        assertEquals(BASELINE, r.memory.state)
        assertEquals(Reason.BUDGET, r.transitions.single().reason)
    }

    @Test
    fun `WATCH ends when sleep is detected or the battery is low`() {
        val watch = run(AdaptiveMemory(), high(t0)).first
        assertEquals(Reason.ASLEEP, step(watch, s(t0 + min, asleep = true)).transitions.single().reason)
        assertEquals(Reason.BATTERY_LOW, step(watch, high(t0 + min, battery = 19)).transitions.single().reason)
    }

    // --- ESCALATED exits -------------------------------------------------------------------

    @Test
    fun `exit needs the calm thresholds held for 3 minutes - hysteresis`() {
        var m = escalated()
        // 95 bpm is below the 100 trigger but not below the 90 exit threshold: no exit timer.
        m = step(m, s(t0 + 2 * min, hr = 95)).memory
        assertNull(m.exitOkSince)
        // 85 is below 90 but not below resting median 68 + 10: still no exit timer.
        m = step(m, s(t0 + 3 * min, hr = 85)).memory
        assertNull(m.exitOkSince)
        m = step(m, s(t0 + 4 * min, hr = 75)).memory
        assertEquals(t0 + 4 * min, m.exitOkSince)
        m = step(m, s(t0 + 6 * min, hr = 75)).memory
        assertEquals(ESCALATED, m.state)   // only 2 min calm
        val r = step(m, s(t0 + 7 * min, hr = 74))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.RECOVERED, r.transitions.single().reason)
        assertEquals(t0 + 7 * min + 30 * min, r.memory.cooldownUntil)
        assertFalse(r.memory.capped)
    }

    @Test
    fun `a relapse resets the exit timer and a failed spot does not`() {
        var m = escalated()
        m = step(m, s(t0 + 2 * min, hr = 75)).memory
        m = step(m, s(t0 + 3 * min, hr = null)).memory   // spot failed: no information
        assertEquals(t0 + 2 * min, m.exitOkSince)
        m = step(m, s(t0 + 4 * min, hr = 75, stress = 60)).memory   // stress 60 ≥ 55 exit
        assertNull(m.exitOkSince)
        assertEquals(ESCALATED, m.state)
    }

    @Test
    fun `exit also requires stress below 55 and no HRV drop`() {
        var m = escalated()
        for (i in 2..8) m = step(m, s(t0 + i * min, hr = 72, stress = 56)).memory
        assertEquals(ESCALATED, m.state)
        for (i in 9..14) m = step(m, s(t0 + i * min, hr = 72, hrv = 30, hrvMed = 50.0)).memory
        assertEquals(ESCALATED, m.state)
    }

    @Test
    fun `20 minute cap ends the episode with a 60 minute cooldown`() {
        var m = escalated()
        val start = t0 + min
        for (i in 1..19) m = step(m, high(start + i * min)).memory
        assertEquals(ESCALATED, m.state)
        val r = step(m, high(start + 20 * min))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.CAP, r.transitions.single().reason)
        assertTrue(r.memory.capped)
        assertEquals(start + 20 * min + 60 * min, r.memory.cooldownUntil)
    }

    @Test
    fun `losing rest ends the episode`() {
        val r = step(escalated(), s(t0 + 2 * min, hr = 110, steps10 = 40, steps2 = 12))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.REST_LOST, r.transitions.single().reason)
    }

    @Test
    fun `unknown steps do not end an episode`() {
        assertEquals(ESCALATED, step(escalated(), s(t0 + 2 * min, hr = 110, steps10 = null, steps2 = null)).memory.state)
    }

    @Test
    fun `battery below 20 percent stops escalation`() {
        val r = step(escalated(), high(t0 + 2 * min, battery = 19))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.BATTERY_LOW, r.transitions.single().reason)
        assertEquals(ESCALATED, step(escalated(), high(t0 + 2 * min, battery = 20)).memory.state)
        assertEquals(ESCALATED, step(escalated(), high(t0 + 2 * min, battery = null)).memory.state)
    }

    @Test
    fun `detected sleep stops escalation`() {
        val r = step(escalated(), high(t0 + 2 * min, asleep = true))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.ASLEEP, r.transitions.single().reason)
    }

    @Test
    fun `an interrupted episode (service killed) is closed on the next sample`() {
        val r = step(escalated(), high(t0 + 40 * min))
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.INTERRUPTED, r.transitions.single().reason)
        // Only maxAccrual (= staleMs, 5 min) of the dead gap is charged to the budget.
        assertEquals(5 * min, r.memory.budgetUsedMs)
    }

    @Test
    fun `a slow but live gap is charged in full to the budget`() {
        // 4 min between samples: under staleMs, so the episode goes on and all of it counts.
        val r = step(escalated(), high(t0 + 5 * min))
        assertEquals(ESCALATED, r.memory.state)
        assertEquals(4 * min, r.memory.budgetUsedMs)
        // Exactly staleMs is still live and charged in full.
        val atStale = step(escalated(), high(t0 + 6 * min))
        assertEquals(ESCALATED, atStale.memory.state)
        assertEquals(5 * min, atStale.memory.budgetUsedMs)
    }

    @Test
    fun `unknown steps keep an episode only until the cap`() {
        var m = escalated()
        val start = t0 + min
        for (i in 1..19) m = step(m, s(start + i * min, hr = 110, steps10 = null, steps2 = null)).memory
        assertEquals(ESCALATED, m.state)
        val r = step(m, s(start + 20 * min, hr = 110, steps10 = null, steps2 = null))
        assertEquals(Reason.CAP, r.transitions.single().reason)
    }

    // --- COOLDOWN ---------------------------------------------------------------------------

    @Test
    fun `cooldown suppresses triggers then returns to baseline and can re-trigger`() {
        val cooling = step(escalated(), high(t0 + 2 * min, battery = 10)).memory
        val until = cooling.cooldownUntil!!
        val during = step(cooling, high(until - 1))
        assertEquals(COOLDOWN, during.memory.state)
        assertTrue(during.transitions.isEmpty())
        val after = step(during.memory, high(until))
        assertEquals(listOf(COOLDOWN to BASELINE, BASELINE to WATCH), after.transitions.map { it.from to it.to })
        assertEquals(Reason.COOLDOWN_DONE, after.transitions.first().reason)
        assertEquals("ep-2", after.memory.episodeId)
    }

    @Test
    fun `calm sample after cooldown returns to baseline`() {
        val cooling = AdaptiveMemory(state = COOLDOWN, cooldownUntil = t0, episodeId = "e", capped = true)
        val r = step(cooling, s(t0 + 1))
        assertEquals(BASELINE, r.memory.state)
        assertNull(r.memory.episodeId)
        assertFalse(r.memory.capped)
    }

    // --- budget / sleep / suppression ------------------------------------------------------

    @Test
    fun `escalated time accrues into the daily budget and the budget ends the episode`() {
        var m = escalated()
        val c = cfg.copy(dailyBudgetMinutes = 5)
        for (i in 2..5) m = AdaptiveStateMachine.step(m, high(t0 + i * min), c, ::dayStart) { "x" }.memory
        assertEquals(4 * min, m.budgetUsedMs)
        assertEquals(ESCALATED, m.state)
        // The step that reaches the budget is the one that closes the episode.
        val r = AdaptiveStateMachine.step(m, high(t0 + 6 * min), c, ::dayStart) { "x" }
        assertEquals(5 * min, r.memory.budgetUsedMs)
        assertEquals(COOLDOWN, r.memory.state)
        assertEquals(Reason.BUDGET, r.transitions.single().reason)
    }

    @Test
    fun `exhausted budget suppresses new triggers and is logged once per 30 minutes`() {
        val spent = AdaptiveMemory(budgetDay = dayStart(t0), budgetUsedMs = 90 * min)
        val r1 = step(spent, high(t0))
        assertEquals(BASELINE, r1.memory.state)
        val t = r1.transitions.single()
        assertTrue(t.suppressed)
        assertEquals(Reason.SUPPRESSED_PREFIX + Reason.BUDGET, t.reason)
        assertTrue(step(r1.memory, high(t0 + 10 * min)).transitions.isEmpty())
        assertEquals(1, step(r1.memory, high(t0 + 31 * min)).transitions.size)
    }

    @Test
    fun `budget resets at the local day boundary`() {
        val spent = AdaptiveMemory(budgetDay = dayStart(t0), budgetUsedMs = 90 * min)
        val r = step(spent, high(t0 + day))
        assertEquals(WATCH, r.memory.state)
        assertEquals(0L, r.memory.budgetUsedMs)
    }

    @Test
    fun `no escalation during detected sleep - trigger is suppressed`() {
        val r = step(AdaptiveMemory(), high(t0, asleep = true))
        assertEquals(BASELINE, r.memory.state)
        assertEquals(Reason.SUPPRESSED_PREFIX + Reason.ASLEEP, r.transitions.single().reason)
    }

    @Test
    fun `low battery suppresses a new trigger`() {
        val r = step(AdaptiveMemory(), high(t0, battery = 15))
        assertEquals(BASELINE, r.memory.state)
        assertEquals(Reason.SUPPRESSED_PREFIX + Reason.BATTERY_LOW, r.transitions.single().reason)
    }

    @Test
    fun `budget minutes include the episode in progress`() {
        val m = escalated()
        assertEquals(2, AdaptiveStateMachine.budgetUsedMinutes(m, t0 + 3 * min, ::dayStart, cfg))
        assertEquals(0, AdaptiveStateMachine.budgetUsedMinutes(m, t0 + day, ::dayStart, cfg))
    }

    @Test
    fun `disabling closes any episode`() {
        val r = AdaptiveStateMachine.disable(escalated(), s(t0 + 2 * min))
        assertEquals(BASELINE, r.memory.state)
        assertEquals(Reason.DISABLED, r.transitions.single().reason)
        assertNull(r.memory.episodeId)
        assertTrue(AdaptiveStateMachine.disable(AdaptiveMemory(), s(t0)).transitions.isEmpty())
    }

    @Test
    fun `transition carries the values it decided on`() {
        val t = step(AdaptiveMemory(), s(t0, hr = 104, stress = 72)).transitions.single()
        assertEquals(104, t.sample.hr)
        assertEquals(72, t.sample.stress)
        assertNotNull(t.episodeId)
    }

    @Test
    fun `needsLink only for WATCH and ESCALATED`() {
        assertEquals(listOf(WATCH, ESCALATED), AdaptiveState.entries.filter(AdaptiveStateMachine::needsLink))
    }

    @Test
    fun `interrupt cools an escalation down and closes a watch`() {
        val esc = AdaptiveStateMachine.interrupt(escalated(), s(t0 + 2 * min), cfg, "link_lost")
        assertEquals(COOLDOWN, esc.memory.state)
        assertEquals("link_lost", esc.transitions.single().reason)
        assertEquals(t0 + 2 * min + 30 * min, esc.memory.cooldownUntil)
        val watch = run(AdaptiveMemory(), high(t0)).first
        val w = AdaptiveStateMachine.interrupt(watch, s(t0 + min), cfg, "fgs_denied")
        assertEquals(BASELINE, w.memory.state)
        assertNull(w.memory.episodeId)
        assertTrue(AdaptiveStateMachine.interrupt(AdaptiveMemory(), s(t0), cfg, "x").transitions.isEmpty())
    }
}
