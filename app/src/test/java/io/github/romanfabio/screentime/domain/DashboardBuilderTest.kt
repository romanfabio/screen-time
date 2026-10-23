package io.github.romanfabio.screentime.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L
private val D = LocalDate.of(2026, 3, 10)
private const val OWN = "own.app"

private fun daily(date: LocalDate, unlockedMin: Long, unlocks: Int = 10) =
    DailyRow(date, unlockedMin * MIN, unlockedMin * MIN, unlocks, null, null)

private fun hourly(date: LocalDate, hour: Int, pkg: String, min: Long, opens: Int = 1, notifs: Int = 0) =
    HourlyRow(date, hour, pkg, min * MIN, opens, notifs)

private val apps = mapOf(
    "yt" to AppMeta("YouTube", isSystem = false, isLauncher = false, hasLauncherIcon = true),
    "cam" to AppMeta("Camera", isSystem = true, isLauncher = false, hasLauncherIcon = true),
    "launcher" to AppMeta("Launcher", isSystem = true, isLauncher = true, hasLauncherIcon = false),
    "ime" to AppMeta("Keyboard", isSystem = true, isLauncher = false, hasLauncherIcon = false),
)

class DashboardBuilderTest {

    private fun build(
        range: DashboardRange,
        daily: List<DailyRow> = emptyList(),
        hourly: List<HourlyRow> = emptyList(),
        showHidden: Boolean = false,
    ) = DashboardBuilder.build(range, D, daily, hourly, apps, OWN, showHidden)

    @Test
    fun `today compares with yesterday and has 24 hourly bars`() {
        val r = build(
            DashboardRange.TODAY,
            daily = listOf(daily(D, 150), daily(D.minusDays(1), 130)),
            hourly = listOf(hourly(D, 9, "yt", 120), hourly(D.minusDays(1), 9, "yt", 100)),
        )
        // Headline is app time (120), not unlocked time (150).
        assertEquals(120 * MIN, r.screenTime.value)
        assertEquals(150 * MIN, r.unlockedMs)
        assertEquals(20, r.screenTime.changePercent)
        assertEquals(24, r.bars.size)
        assertEquals(0, r.bars.first().hour)
    }

    @Test
    fun `no change percent without previous data or with a zero baseline`() {
        assertNull(build(DashboardRange.TODAY, daily = listOf(daily(D, 60))).screenTime.changePercent)
        val zero = build(
            DashboardRange.TODAY,
            daily = listOf(daily(D, 60), daily(D.minusDays(1), 0)),
            hourly = listOf(hourly(D, 9, "yt", 60)),
        )
        assertNull(zero.screenTime.changePercent)
    }

    @Test
    fun `week covers seven days, previous week is the seven before`() {
        val rows = (0..13).map { daily(D.minusDays(it.toLong()), 60) }
        val hours = (0..13).map { hourly(D.minusDays(it.toLong()), 12, "yt", 60) }
        val r = build(DashboardRange.WEEK, daily = rows, hourly = hours)
        assertEquals(7 * 60 * MIN, r.screenTime.value)
        assertEquals(7 * 60 * MIN, r.screenTime.previous)
        assertEquals(60 * MIN, r.averageScreenTimeMs)
        assertEquals(7, r.bars.size)
        assertEquals(D.minusDays(6), r.bars.first().date)
        assertEquals(D, r.bars.last().date)
    }

    @Test
    fun `no comparison when history does not cover the whole previous period`() {
        // Nine days of history: the previous week (D-13..D-7) is only partly known.
        val rows = (0..8).map { daily(D.minusDays(it.toLong()), 60) }
        val hours = (0..8).map { hourly(D.minusDays(it.toLong()), 12, "yt", 60) }
        val r = build(DashboardRange.WEEK, daily = rows, hourly = hours)
        assertNull(r.screenTime.previous)
        assertNull(r.screenTime.changePercent)
        // Yesterday is fully covered, so the day view still compares.
        assertEquals(0, build(DashboardRange.TODAY, daily = rows, hourly = hours).screenTime.changePercent)
    }

    @Test
    fun `average only counts days that have data`() {
        val r = build(
            DashboardRange.MONTH,
            daily = listOf(daily(D, 90), daily(D.minusDays(1), 30)),
            hourly = listOf(hourly(D, 12, "yt", 80), hourly(D.minusDays(1), 12, "yt", 40)),
        )
        assertEquals(60 * MIN, r.averageScreenTimeMs)
        assertEquals(30, r.bars.size)
    }

    @Test
    fun `hidden apps are left out of lists and bars unless requested`() {
        val rows = listOf(
            hourly(D, 9, "yt", 30), hourly(D, 9, "cam", 5), hourly(D, 9, "launcher", 20),
            hourly(D, 9, "ime", 3), hourly(D, 9, OWN, 7),
        )
        val hidden = build(DashboardRange.TODAY, hourly = rows)
        // The launcher shows up as one "Home screen" row; the keyboard and our own app stay hidden.
        assertEquals(listOf("yt", DashboardBuilder.HOME_PACKAGE, "cam"), hidden.apps.map { it.packageName })
        assertEquals(55 * MIN, hidden.bars[9].totalMs)
        assertEquals(55 * MIN, hidden.screenTime.value)
        val all = build(DashboardRange.TODAY, hourly = rows, showHidden = true)
        assertEquals(5, all.apps.size)
        assertTrue(DashboardBuilder.HOME_PACKAGE !in all.apps.map { it.packageName })
    }

    @Test
    fun `apps are ranked by time, unknown packages keep their package name as label`() {
        val rows = listOf(hourly(D, 8, "yt", 10), hourly(D, 9, "yt", 20), hourly(D, 9, "mystery", 40))
        val r = build(DashboardRange.TODAY, hourly = rows)
        assertEquals(listOf("mystery", "yt"), r.apps.map { it.packageName })
        assertEquals("mystery", r.apps.first().label)
        assertEquals(30 * MIN, r.apps.last().foregroundMs)
        assertEquals(2, r.apps.last().opens)
    }

    @Test
    fun `bars segment the top five apps and fold the rest into other`() {
        val pkgs = (1..7).map { "app$it" }
        val rows = pkgs.mapIndexed { i, p -> hourly(D, 12, p, (100 - i * 10).toLong()) }
        val r = build(DashboardRange.TODAY, hourly = rows)
        assertEquals(pkgs.take(5), r.topApps)
        val bar = r.bars[12]
        assertEquals(6, bar.segments.size)
        assertEquals(-1, bar.segments.last().rank)
        assertEquals((50 + 40) * MIN, bar.segments.last().ms)
    }

    @Test
    fun `notifications and unlocks are summed with a comparison`() {
        val r = build(
            DashboardRange.WEEK,
            daily = listOf(daily(D, 10, unlocks = 30), daily(D.minusDays(7), 10, unlocks = 20), daily(D.minusDays(13), 1, unlocks = 0)),
            hourly = listOf(
                hourly(D, 1, "yt", 1, notifs = 6), hourly(D.minusDays(1), 1, "yt", 1, notifs = 4),
                hourly(D.minusDays(8), 1, "yt", 1, notifs = 5),
            ),
        )
        assertEquals(30L, r.unlocks.value)
        assertEquals(50, r.unlocks.changePercent)
        assertEquals(10L, r.notifications.value)
        assertEquals(5L, r.notifications.previous)
    }

    @Test
    fun `first pickup and last use only for a single day`() {
        val row = DailyRow(D, 0, MIN, 1, 111L, 222L)
        assertEquals(111L, build(DashboardRange.TODAY, daily = listOf(row)).firstPickupMs)
        assertNull(build(DashboardRange.WEEK, daily = listOf(row)).firstPickupMs)
    }

    @Test
    fun `empty database reports no data`() {
        assertTrue(!build(DashboardRange.TODAY).hasData)
    }
}
