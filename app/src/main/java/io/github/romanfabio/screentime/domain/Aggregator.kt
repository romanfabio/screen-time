package io.github.romanfabio.screentime.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HourlyAppUsage(
    val date: LocalDate,
    val hour: Int,
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int,
)

data class DailyDevice(
    val date: LocalDate,
    val screenOnMs: Long,
    val unlockedMs: Long,
    val unlocks: Int,
    val firstPickupMs: Long?,
    val lastUseMs: Long?,
)

data class AggregationResult(val hourly: List<HourlyAppUsage>, val daily: List<DailyDevice>)

/** Buckets sessions and intervals into local-time hours and days. Pure Kotlin. */
object Aggregator {

    fun aggregate(
        sessions: List<AppSession>,
        intervals: List<DeviceInterval>,
        unlocks: List<Long>,
        notifications: List<NotificationEvent>,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): AggregationResult {
        fun inRange(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)

        data class HourKey(val date: LocalDate, val hour: Int, val pkg: String)
        class HourAcc { var ms = 0L; var opens = 0; var notifs = 0 }
        val hours = HashMap<HourKey, HourAcc>()

        for (s in sessions) {
            splitByLocalHour(s.startMs, s.endMs, zone) { date, hour, start, end ->
                if (inRange(date)) hours.getOrPut(HourKey(date, hour, s.packageName)) { HourAcc() }.ms += end - start
            }
            val z = Instant.ofEpochMilli(s.startMs).atZone(zone)
            if (inRange(z.toLocalDate())) {
                hours.getOrPut(HourKey(z.toLocalDate(), z.hour, s.packageName)) { HourAcc() }.opens++
            }
        }
        for (n in notifications) {
            val z = Instant.ofEpochMilli(n.timeMs).atZone(zone)
            if (inRange(z.toLocalDate())) {
                hours.getOrPut(HourKey(z.toLocalDate(), z.hour, n.packageName)) { HourAcc() }.notifs++
            }
        }

        class DayAcc {
            var screenOn = 0L; var unlocked = 0L; var unlocks = 0
            var first: Long? = null; var last: Long? = null
        }
        val days = HashMap<LocalDate, DayAcc>()
        for (i in intervals) {
            splitByLocalHour(i.startMs, i.endMs, zone) { date, _, start, end ->
                if (!inRange(date)) return@splitByLocalHour
                val d = days.getOrPut(date) { DayAcc() }
                when (i.kind) {
                    IntervalKind.SCREEN_ON -> d.screenOn += end - start
                    IntervalKind.UNLOCKED -> {
                        d.unlocked += end - start
                        // First pickup / last use only from intervals that really begin / end on this day:
                        // a late-night session carried over midnight is not "the first pickup" of the next day.
                        if (start == i.startMs) d.first = minOf(d.first ?: start, start)
                        if (end == i.endMs) d.last = maxOf(d.last ?: end, end)
                    }
                }
            }
        }
        for (u in unlocks) {
            val date = Instant.ofEpochMilli(u).atZone(zone).toLocalDate()
            if (inRange(date)) days.getOrPut(date) { DayAcc() }.unlocks++
        }

        return AggregationResult(
            hourly = hours.map { (k, v) ->
                HourlyAppUsage(k.date, k.hour, k.pkg, v.ms, v.opens, v.notifs)
            }.sortedWith(compareBy({ it.date }, { it.hour }, { it.packageName })),
            daily = days.map { (date, v) ->
                DailyDevice(date, v.screenOn, v.unlocked, v.unlocks, v.first, v.last)
            }.sortedBy { it.date },
        )
    }

    /** Calls [action] for every piece of [startMs, endMs) that lies inside one local clock hour. */
    internal fun splitByLocalHour(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        action: (date: LocalDate, hour: Int, startMs: Long, endMs: Long) -> Unit,
    ) {
        var cur = startMs
        while (cur < endMs) {
            val z = Instant.ofEpochMilli(cur).atZone(zone)
            val nextHour = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val pieceEnd = minOf(endMs, nextHour)
            action(z.toLocalDate(), z.hour, cur, pieceEnd)
            cur = pieceEnd
        }
    }
}
