package io.github.romanfabio.screentime.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonPeriodTest {
    private val today = LocalDate.of(2026, 3, 10)

    @Test
    fun `today compares with yesterday`() {
        assertEquals(ComparisonPeriod.YESTERDAY, comparisonPeriod(DashboardRange.TODAY, today, today))
    }

    @Test
    fun `an earlier day compares with the day before it, not yesterday`() {
        assertEquals(ComparisonPeriod.PREVIOUS_DAY, comparisonPeriod(DashboardRange.TODAY, today.minusDays(1), today))
        assertEquals(ComparisonPeriod.PREVIOUS_DAY, comparisonPeriod(DashboardRange.TODAY, today.minusDays(40), today))
    }

    @Test
    fun `ranges compare with the previous range of the same length`() {
        assertEquals(ComparisonPeriod.PREVIOUS_WEEK, comparisonPeriod(DashboardRange.WEEK, today, today))
        assertEquals(ComparisonPeriod.PREVIOUS_WEEK, comparisonPeriod(DashboardRange.WEEK, today.minusDays(7), today))
        assertEquals(ComparisonPeriod.PREVIOUS_MONTH, comparisonPeriod(DashboardRange.MONTH, today, today))
    }
}
