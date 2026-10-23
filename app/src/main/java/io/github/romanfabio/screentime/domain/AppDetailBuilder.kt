package io.github.romanfabio.screentime.domain

import java.time.LocalDate

data class AppDetailData(
    val range: DashboardRange,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val packageName: String,
    val label: String,
    val foreground: Metric,
    val opens: Metric,
    val notifications: Metric,
    /** Average length of one open (foreground time divided by number of opens). */
    val avgSessionMs: Long,
    /** One single-segment bar per hour (Today) or per day. */
    val bars: List<Bar>,
    /** Foreground milliseconds per hour of day (0-23) summed over the period: "when do I use it". */
    val hourOfDayMs: List<Long>,
    val hasData: Boolean,
)

object AppDetailBuilder {

    fun build(
        packageName: String,
        label: String,
        range: DashboardRange,
        endDate: LocalDate,
        rows: List<HourlyRow>,
        historyStart: LocalDate?,
    ): AppDetailData {
        val start = DashboardBuilder.periodStart(range, endDate)
        val prevEnd = start.minusDays(1)
        val prevStart = DashboardBuilder.periodStart(range, prevEnd)

        val now = rows.filter { it.packageName == packageName && !it.date.isBefore(start) && !it.date.isAfter(endDate) }
        val prev = rows.filter { it.packageName == packageName && !it.date.isBefore(prevStart) && !it.date.isAfter(prevEnd) }
        val hasPrevious = historyStart != null && historyStart <= prevStart

        fun bar(hour: Int?, date: LocalDate?, ms: Long) =
            Bar(hour, date, if (ms > 0) listOf(BarSegment(packageName, 0, ms)) else emptyList())

        val bars = if (range == DashboardRange.TODAY) {
            (0..23).map { h -> bar(h, null, now.filter { it.hour == h }.sumOf { it.foregroundMs }) }
        } else {
            (0 until range.days).map { i ->
                val d = start.plusDays(i.toLong())
                bar(null, d, now.filter { it.date == d }.sumOf { it.foregroundMs })
            }
        }

        val foreground = now.sumOf { it.foregroundMs }
        val opens = now.sumOf { it.opens }
        return AppDetailData(
            range = range,
            startDate = start,
            endDate = endDate,
            packageName = packageName,
            label = label,
            foreground = Metric(foreground, if (hasPrevious) prev.sumOf { it.foregroundMs } else null),
            opens = Metric(opens.toLong(), if (hasPrevious) prev.sumOf { it.opens }.toLong() else null),
            notifications = Metric(
                now.sumOf { it.notifications }.toLong(),
                if (hasPrevious) prev.sumOf { it.notifications }.toLong() else null,
            ),
            avgSessionMs = if (opens > 0) foreground / opens else 0L,
            bars = bars,
            hourOfDayMs = (0..23).map { h -> now.filter { it.hour == h }.sumOf { it.foregroundMs } },
            hasData = now.isNotEmpty(),
        )
    }
}
