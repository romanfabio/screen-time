package io.github.romanfabio.screentime.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val MIN = 60_000L
private val D = LocalDate.of(2026, 3, 10)

private fun row(date: LocalDate, hour: Int, pkg: String, min: Long, opens: Int = 1, notifs: Int = 0) =
    HourlyRow(date, hour, pkg, min * MIN, opens, notifs)

class AppDetailBuilderTest {

    private fun build(range: DashboardRange, rows: List<HourlyRow>, historyStart: LocalDate? = D.minusDays(60)) =
        AppDetailBuilder.build("yt", "YouTube", range, D, rows, historyStart)

    @Test
    fun `totals, opens and average session for one app only`() {
        val r = build(
            DashboardRange.WEEK,
            listOf(row(D, 9, "yt", 30, opens = 2), row(D.minusDays(1), 20, "yt", 30, opens = 4), row(D, 9, "other", 99)),
        )
        assertEquals(60 * MIN, r.foreground.value)
        assertEquals(6L, r.opens.value)
        assertEquals(10 * MIN, r.avgSessionMs)
        assertEquals("YouTube", r.label)
    }

    @Test
    fun `today has 24 hourly bars and week has 7 daily bars`() {
        val rows = listOf(row(D, 9, "yt", 30))
        val today = build(DashboardRange.TODAY, rows)
        assertEquals(24, today.bars.size)
        assertEquals(30 * MIN, today.bars[9].totalMs)
        val week = build(DashboardRange.WEEK, rows)
        assertEquals(7, week.bars.size)
        assertEquals(30 * MIN, week.bars.last().totalMs)
    }

    @Test
    fun `hour of day sums across days`() {
        val r = build(DashboardRange.WEEK, listOf(row(D, 21, "yt", 10), row(D.minusDays(2), 21, "yt", 20), row(D, 8, "yt", 5)))
        assertEquals(30 * MIN, r.hourOfDayMs[21])
        assertEquals(5 * MIN, r.hourOfDayMs[8])
        assertEquals(24, r.hourOfDayMs.size)
    }

    @Test
    fun `compares with the previous period only when history covers it`() {
        val rows = listOf(row(D, 9, "yt", 60), row(D.minusDays(7), 9, "yt", 30))
        assertEquals(100, build(DashboardRange.WEEK, rows).foreground.changePercent)
        assertNull(build(DashboardRange.WEEK, rows, historyStart = D.minusDays(8)).foreground.previous)
    }

    @Test
    fun `no rows means no data and zero average`() {
        val r = build(DashboardRange.WEEK, emptyList())
        assertFalse(r.hasData)
        assertEquals(0L, r.avgSessionMs)
    }
}
