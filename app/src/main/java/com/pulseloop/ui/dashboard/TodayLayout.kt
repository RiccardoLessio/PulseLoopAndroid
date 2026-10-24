package com.pulseloop.ui.dashboard

/**
 * Where the Today cards land on screen (fork v0.3.1). Pure, so the diary placement rules are
 * unit-tested without Compose.
 */
object TodayLayout {

    /**
     * The visible Today [order] as displayed: a compiled diary moves below every other card when
     * [moveDiaryToEnd] is on; otherwise (or while not compiled) it keeps its customized position.
     * Edit mode must not call this — the user moves the diary's saved position there.
     */
    fun displayOrder(
        order: List<DashboardCard>,
        diaryCompiled: Boolean,
        moveDiaryToEnd: Boolean,
    ): List<DashboardCard> {
        if (!diaryCompiled || !moveDiaryToEnd || DashboardCard.DIARY !in order) return order
        return order.filter { it != DashboardCard.DIARY } + DashboardCard.DIARY
    }

    /**
     * Grid rows: half-width tiles paired two per row in order; the full-width diary takes a row of
     * its own and the pairing restarts after it.
     */
    fun rows(order: List<DashboardCard>): List<List<DashboardCard>> {
        val rows = mutableListOf<List<DashboardCard>>()
        val run = mutableListOf<DashboardCard>()
        fun flush() {
            rows += run.chunked(2)
            run.clear()
        }
        for (card in order) {
            if (card == DashboardCard.DIARY) {
                flush()
                rows += listOf(card)
            } else {
                run += card
            }
        }
        flush()
        return rows
    }
}
