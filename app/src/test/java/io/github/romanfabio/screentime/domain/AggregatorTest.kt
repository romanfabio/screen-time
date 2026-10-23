package io.github.romanfabio.screentime.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val ROME = ZoneId.of("Europe/Rome")
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
    ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

class AggregatorTest {

    private fun aggregate(
        sessions: List<AppSession> = emptyList(),
        intervals: List<DeviceInterval> = emptyList(),
        unlocks: List<Long> = emptyList(),
        notifications: List<NotificationEvent> = emptyList(),
        zone: ZoneId = ROME,
        from: LocalDate = LocalDate.of(2026, 1, 1),
        to: LocalDate = LocalDate.of(2026, 12, 31),
    ) = Aggregator.aggregate(sessions, intervals, unlocks, notifications, zone, from, to)

    @Test
    fun `session crossing an hour boundary is split, opens go to the start hour`() {
        val start = at(ROME, 2026, 3, 10, 9, 50)
        val r = aggregate(sessions = listOf(AppSession("a", start, start + 30 * MIN)))
        val rows = r.hourly.associateBy { it.hour }
        assertEquals(10 * MIN, rows.getValue(9).foregroundMs)
        assertEquals(20 * MIN, rows.getValue(10).foregroundMs)
        assertEquals(1, rows.getValue(9).opens)
        assertEquals(0, rows.getValue(10).opens)
    }

    @Test
    fun `interval crossing midnight is split between two days`() {
        val start = at(ROME, 2026, 3, 10, 23, 30)
        val r = aggregate(intervals = listOf(DeviceInterval(IntervalKind.UNLOCKED, start, start + HOUR)))
        val days = r.daily.associateBy { it.date }
        assertEquals(30 * MIN, days.getValue(LocalDate.of(2026, 3, 10)).unlockedMs)
        assertEquals(30 * MIN, days.getValue(LocalDate.of(2026, 3, 11)).unlockedMs)
        assertEquals(start, days.getValue(LocalDate.of(2026, 3, 10)).firstPickupMs)
        assertEquals(at(ROME, 2026, 3, 11, 0, 30), days.getValue(LocalDate.of(2026, 3, 11)).lastUseMs)
    }

    @Test
    fun `first pickup ignores a session carried over midnight`() {
        val start = at(ROME, 2026, 3, 10, 23, 30)
        val morning = at(ROME, 2026, 3, 11, 8)
        val r = aggregate(
            intervals = listOf(
                DeviceInterval(IntervalKind.UNLOCKED, start, start + HOUR),
                DeviceInterval(IntervalKind.UNLOCKED, morning, morning + 10 * MIN),
            ),
        )
        val days = r.daily.associateBy { it.date }
        assertEquals(morning, days.getValue(LocalDate.of(2026, 3, 11)).firstPickupMs)
        assertEquals(morning + 10 * MIN, days.getValue(LocalDate.of(2026, 3, 11)).lastUseMs)
        // The late session ends on the next day, so day 10 has no genuine "last use".
        assertEquals(start, days.getValue(LocalDate.of(2026, 3, 10)).firstPickupMs)
        assertEquals(null, days.getValue(LocalDate.of(2026, 3, 10)).lastUseMs)
    }

    @Test
    fun `daily row combines screen on, unlocked, unlocks`() {
        val t = at(ROME, 2026, 3, 10, 8)
        val r = aggregate(
            intervals = listOf(
                DeviceInterval(IntervalKind.SCREEN_ON, t, t + 10 * MIN),
                DeviceInterval(IntervalKind.UNLOCKED, t + MIN, t + 10 * MIN),
            ),
            unlocks = listOf(t + MIN, t + 5 * MIN),
        )
        val d = r.daily.single()
        assertEquals(10 * MIN, d.screenOnMs)
        assertEquals(9 * MIN, d.unlockedMs)
        assertEquals(2, d.unlocks)
    }

    @Test
    fun `notifications are counted per app and hour`() {
        val t = at(ROME, 2026, 3, 10, 14, 5)
        val r = aggregate(notifications = listOf(NotificationEvent("a", t), NotificationEvent("a", t + MIN), NotificationEvent("b", t)))
        assertEquals(2, r.hourly.single { it.packageName == "a" }.notifications)
        assertEquals(1, r.hourly.single { it.packageName == "b" }.notifications)
    }

    @Test
    fun `output is limited to the requested dates`() {
        val day1 = at(ROME, 2026, 3, 10, 12)
        val day2 = at(ROME, 2026, 3, 11, 12)
        val r = aggregate(
            sessions = listOf(AppSession("a", day1, day1 + MIN), AppSession("a", day2, day2 + MIN)),
            from = LocalDate.of(2026, 3, 11), to = LocalDate.of(2026, 3, 11),
        )
        assertEquals(listOf(LocalDate.of(2026, 3, 11)), r.hourly.map { it.date })
    }

    @Test
    fun `fall back DST day keeps all 25 hours of usage`() {
        // 2026-10-25 in Rome has 25 hours. Unlocked for the whole day.
        val start = at(ROME, 2026, 10, 25, 0)
        val end = at(ROME, 2026, 10, 26, 0)
        assertEquals(25 * HOUR, end - start)
        val r = aggregate(
            sessions = listOf(AppSession("a", start, end)),
            intervals = listOf(DeviceInterval(IntervalKind.UNLOCKED, start, end)),
        )
        assertEquals(25 * HOUR, r.daily.single().unlockedMs)
        assertEquals(25 * HOUR, r.hourly.sumOf { it.foregroundMs })
        // Local hour 2 happens twice and is folded into one bucket with 2 hours.
        assertEquals(2 * HOUR, r.hourly.single { it.hour == 2 }.foregroundMs)
    }

    @Test
    fun `half hour offset zones split on local hours`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val start = at(kolkata, 2026, 3, 10, 9, 45)
        val r = aggregate(sessions = listOf(AppSession("a", start, start + 30 * MIN)), zone = kolkata)
        assertEquals(15 * MIN, r.hourly.single { it.hour == 9 }.foregroundMs)
        assertEquals(15 * MIN, r.hourly.single { it.hour == 10 }.foregroundMs)
    }
}
