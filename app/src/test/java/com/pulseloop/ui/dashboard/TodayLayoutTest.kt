package com.pulseloop.ui.dashboard

import com.pulseloop.ui.dashboard.DashboardCard.ACTIVITY
import com.pulseloop.ui.dashboard.DashboardCard.DIARY
import com.pulseloop.ui.dashboard.DashboardCard.HEART_RATE
import com.pulseloop.ui.dashboard.DashboardCard.HRV
import com.pulseloop.ui.dashboard.DashboardCard.SLEEP
import com.pulseloop.ui.dashboard.DashboardCard.SPO2
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Diario di oggi" as a customizable Today card (fork v0.3.1): placement, rows, saved-layout migration. */
class TodayLayoutTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun decode(s: String) = json.decodeFromString(MetricPrefs.serializer(), s)
    private val defaultKeys = DashboardCard.todayDefault.map { it.key }

    private fun resolve(p: MetricPrefs, visible: List<DashboardCard> = DashboardCard.todayDefault) =
        p.resolvedOrder(visible.filter { !p.isHidden(it, MetricScope.TODAY) }.map { it.key }.toSet(), defaultKeys, MetricScope.TODAY)
            .map { DashboardCard.fromKey(it)!! }

    // ── default position ──

    @Test fun diaryLeadsTheDefaultOrderRightAfterTheHero() {
        assertEquals(DIARY, DashboardCard.todayDefault.first())
        assertEquals(DIARY, resolve(MetricPrefs.DEFAULT).first())
    }

    @Test fun diaryIsNotAVitalsCard() {
        assertFalse(DIARY in DashboardCard.vitalsDefault)
    }

    // ── compiled / not compiled × option on / off ──

    private val custom = listOf(ACTIVITY, DIARY, SLEEP, HEART_RATE)

    @Test fun notCompiledKeepsCustomizedPositionWithOptionOn() {
        assertEquals(custom, TodayLayout.displayOrder(custom, diaryCompiled = false, moveDiaryToEnd = true))
    }

    @Test fun notCompiledKeepsCustomizedPositionWithOptionOff() {
        assertEquals(custom, TodayLayout.displayOrder(custom, diaryCompiled = false, moveDiaryToEnd = false))
    }

    @Test fun compiledMovesToTheEndWithOptionOn() {
        assertEquals(
            listOf(ACTIVITY, SLEEP, HEART_RATE, DIARY),
            TodayLayout.displayOrder(custom, diaryCompiled = true, moveDiaryToEnd = true),
        )
    }

    @Test fun compiledStaysPutWithOptionOff() {
        assertEquals(custom, TodayLayout.displayOrder(custom, diaryCompiled = true, moveDiaryToEnd = false))
    }

    @Test fun hiddenDiaryStaysHiddenWhenCompiled() {
        val noDiary = listOf(ACTIVITY, SLEEP)
        assertEquals(noDiary, TodayLayout.displayOrder(noDiary, diaryCompiled = true, moveDiaryToEnd = true))
    }

    @Test fun optionDefaultsOnAndRoundTrips() {
        assertTrue(MetricPrefs.DEFAULT.todayDiaryToEndWhenCompiled)
        val off = MetricPrefs.DEFAULT.copy(todayDiaryToEndWhenCompiled = false)
        assertEquals(off, decode(json.encodeToString(MetricPrefs.serializer(), off)))
    }

    // ── saved layout migration ──

    @Test fun blobWrittenBeforeTheOptionExistedDefaultsItOn() {
        val p = decode("""{"todayOrder":["sleep","steps"],"todayHiddenMetrics":["hrv"]}""")
        assertTrue(p.todayDiaryToEndWhenCompiled)
    }

    @Test fun savedOrderWithoutDiaryGetsItAtItsDefaultPosition() {
        // A user who reordered before v0.3.1: the diary is inserted first (after the hero), visible,
        // and their own order is otherwise untouched.
        val p = decode("""{"todayOrder":["sleep","heartRate","steps","spo2"],"todayHiddenMetrics":["hrv"]}""")
        val order = resolve(p)
        assertEquals(listOf(DIARY, SLEEP, HEART_RATE, ACTIVITY, SPO2), order.take(5))
        assertFalse(p.isHidden(DIARY, MetricScope.TODAY))
        assertFalse(HRV in order)
    }

    @Test fun movedAndHiddenDiaryPersist() {
        var p = MetricPrefs.DEFAULT.withOrder(listOf("steps", "sleep", "diary", "heartRate"), MetricScope.TODAY)
        assertEquals(listOf(ACTIVITY, SLEEP, DIARY, HEART_RATE), resolve(p).take(4))
        p = p.withHidden(DIARY, true, MetricScope.TODAY)
        assertFalse(DIARY in resolve(p))
        p = p.withHidden(DIARY, false, MetricScope.TODAY)
        assertEquals(listOf(ACTIVITY, SLEEP, DIARY, HEART_RATE), resolve(p).take(4))
    }

    // ── rows ──

    @Test fun diaryTakesAFullRowAndPairingRestartsAfterIt() {
        assertEquals(
            listOf(listOf(ACTIVITY, SLEEP), listOf(HEART_RATE), listOf(DIARY), listOf(SPO2, HRV)),
            TodayLayout.rows(listOf(ACTIVITY, SLEEP, HEART_RATE, DIARY, SPO2, HRV)),
        )
    }

    @Test fun rowsWithoutDiaryArePlainPairs() {
        assertEquals(
            listOf(listOf(ACTIVITY, SLEEP), listOf(HEART_RATE)),
            TodayLayout.rows(listOf(ACTIVITY, SLEEP, HEART_RATE)),
        )
    }
}
