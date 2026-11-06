package io.github.romanfabio.screentime.domain

import java.time.LocalDate

enum class DashboardRange(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

/** Aggregated rows as stored in Room, decoupled from the entities so this stays pure and testable. */
data class DailyRow(
    val date: LocalDate,
    val screenOnMs: Long,
    val unlockedMs: Long,
    val unlocks: Int,
    val firstPickupMs: Long?,
    val lastUseMs: Long?,
)

data class HourlyRow(
    val date: LocalDate,
    val hour: Int,
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int,
)

data class AppMeta(
    val label: String,
    val isSystem: Boolean,
    val isLauncher: Boolean,
    val hasLauncherIcon: Boolean,
)

data class AppRow(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int,
)

/** One chart segment. [rank] is the app's position among the top apps (0-based), or -1 for "other". */
data class BarSegment(val packageName: String?, val rank: Int, val ms: Long)

/** [label] is the hour (0-23) for a day view or the date for a range view. */
data class Bar(val hour: Int?, val date: LocalDate?, val segments: List<BarSegment>) {
    val totalMs: Long get() = segments.sumOf { it.ms }
}

data class Metric(val value: Long, val previous: Long?) {
    /** Relative change versus the previous period, or null when there is nothing to compare with. */
    val changePercent: Int?
        get() = previous?.takeIf { it > 0 }?.let { Math.round((value - it) * 100.0 / it).toInt() }
}

data class DashboardData(
    val range: DashboardRange,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** Headline: sum of foreground time of the listed apps, like Digital Wellbeing. */
    val screenTime: Metric,
    /** Time the phone was unlocked (includes time not attributed to an app, e.g. picture-in-picture). */
    val unlockedMs: Long,
    val averageScreenTimeMs: Long,
    val averageUnlocks: Int,
    val screenOnMs: Long,
    val unlocks: Metric,
    val notifications: Metric,
    val firstPickupMs: Long?,
    val lastUseMs: Long?,
    val bars: List<Bar>,
    val topApps: List<String>,
    val apps: List<AppRow>,
    val hasData: Boolean,
)

object DashboardBuilder {
    const val TOP_APPS = 5

    /** All launchers are shown as one "Home screen" row (Digital Wellbeing counts that time in its total too). */
    const val HOME_PACKAGE = "*home"

    /** Hidden by default: our own app and system packages without an app-drawer icon (SystemUI, keyboards...). */
    fun isHidden(pkg: String, meta: AppMeta?, ownPackage: String): Boolean =
        pkg == ownPackage || (meta != null && !meta.isLauncher && meta.isSystem && !meta.hasLauncherIcon)

    private fun isLauncher(pkg: String, meta: AppMeta?, ownPackage: String) = pkg != ownPackage && meta?.isLauncher == true

    fun periodStart(range: DashboardRange, end: LocalDate): LocalDate = end.minusDays(range.days - 1L)

    fun build(
        range: DashboardRange,
        endDate: LocalDate,
        daily: List<DailyRow>,
        hourly: List<HourlyRow>,
        apps: Map<String, AppMeta>,
        ownPackage: String,
        showHidden: Boolean,
    ): DashboardData {
        val start = periodStart(range, endDate)
        val prevEnd = start.minusDays(1)
        val prevStart = periodStart(range, prevEnd)

        fun inCurrent(d: LocalDate) = !d.isBefore(start) && !d.isAfter(endDate)
        fun inPrevious(d: LocalDate) = !d.isBefore(prevStart) && !d.isAfter(prevEnd)
        fun visible(pkg: String) = showHidden || !isHidden(pkg, apps[pkg], ownPackage)
        // Launchers collapse into one row, unless the user asked to see everything separately.
        fun group(row: HourlyRow) =
            if (!showHidden && isLauncher(row.packageName, apps[row.packageName], ownPackage)) row.copy(packageName = HOME_PACKAGE) else row

        val dailyNow = daily.filter { inCurrent(it.date) }
        val dailyPrev = daily.filter { inPrevious(it.date) }
        val hourlyNow = hourly.filter { inCurrent(it.date) && visible(it.packageName) }.map(::group)
        val hourlyPrev = hourly.filter { inPrevious(it.date) && visible(it.packageName) }.map(::group)

        val unlockedMs = dailyNow.sumOf { it.unlockedMs }
        val appTimeMs = hourlyNow.sumOf { it.foregroundMs }
        val daysWithData = dailyNow.count { it.unlockedMs > 0 }.coerceAtLeast(1)
        // Only compare when history covers the whole previous period; otherwise "+318%" is just missing data.
        val hasPrevious = dailyPrev.isNotEmpty() && daily.minOf { it.date } <= prevStart

        val appRows = hourlyNow.groupBy { it.packageName }.map { (pkg, rows) ->
            AppRow(
                packageName = pkg,
                label = if (pkg == HOME_PACKAGE) "" else apps[pkg]?.label ?: pkg,
                foregroundMs = rows.sumOf { it.foregroundMs },
                opens = rows.sumOf { it.opens },
                notifications = rows.sumOf { it.notifications },
            )
        }.filter { it.foregroundMs > 0 || it.opens > 0 || it.notifications > 0 }
            .sortedByDescending { it.foregroundMs }

        val top = appRows.filter { it.foregroundMs > 0 }.take(TOP_APPS).map { it.packageName }
        val rankOf = top.withIndex().associate { it.value to it.index }

        fun segments(rows: List<HourlyRow>): List<BarSegment> {
            val byApp = rows.groupBy { it.packageName }.mapValues { (_, r) -> r.sumOf { it.foregroundMs } }
                .filterValues { it > 0 }
            val named = top.mapNotNull { pkg -> byApp[pkg]?.let { BarSegment(pkg, rankOf.getValue(pkg), it) } }
            val other = byApp.filterKeys { it !in rankOf }.values.sum()
            return if (other > 0) named + BarSegment(null, -1, other) else named
        }

        val bars = if (range == DashboardRange.TODAY) {
            (0..23).map { h -> Bar(h, null, segments(hourlyNow.filter { it.hour == h })) }
        } else {
            (0 until range.days).map { i ->
                val d = start.plusDays(i.toLong())
                Bar(null, d, segments(hourlyNow.filter { it.date == d }))
            }
        }

        val single = if (range == DashboardRange.TODAY) dailyNow.firstOrNull() else null

        return DashboardData(
            range = range,
            startDate = start,
            endDate = endDate,
            screenTime = Metric(appTimeMs, if (hasPrevious) hourlyPrev.sumOf { it.foregroundMs } else null),
            unlockedMs = unlockedMs,
            averageScreenTimeMs = appTimeMs / daysWithData,
            averageUnlocks = Math.round(dailyNow.sumOf { it.unlocks }.toDouble() / daysWithData).toInt(),
            screenOnMs = dailyNow.sumOf { it.screenOnMs },
            unlocks = Metric(
                dailyNow.sumOf { it.unlocks }.toLong(),
                if (hasPrevious) dailyPrev.sumOf { it.unlocks }.toLong() else null,
            ),
            notifications = Metric(
                hourlyNow.sumOf { it.notifications }.toLong(),
                if (hasPrevious) hourlyPrev.sumOf { it.notifications }.toLong() else null,
            ),
            firstPickupMs = single?.firstPickupMs,
            lastUseMs = single?.lastUseMs,
            bars = bars,
            topApps = top,
            apps = appRows,
            hasData = dailyNow.isNotEmpty() || appRows.isNotEmpty(),
        )
    }
}

/** What a "vs previous period" figure is compared with, so the label stays correct when browsing past days. */
enum class ComparisonPeriod { YESTERDAY, PREVIOUS_DAY, PREVIOUS_WEEK, PREVIOUS_MONTH }

/** For a day view, "yesterday" only makes sense while looking at today; any earlier day compares with the day before. */
fun comparisonPeriod(range: DashboardRange, endDate: LocalDate, today: LocalDate): ComparisonPeriod = when (range) {
    DashboardRange.TODAY -> if (endDate == today) ComparisonPeriod.YESTERDAY else ComparisonPeriod.PREVIOUS_DAY
    DashboardRange.WEEK -> ComparisonPeriod.PREVIOUS_WEEK
    DashboardRange.MONTH -> ComparisonPeriod.PREVIOUS_MONTH
}
