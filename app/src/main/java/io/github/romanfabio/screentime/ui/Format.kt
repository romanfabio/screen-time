package io.github.romanfabio.screentime.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "2h 15m", "45m", "<1m" — short and the same in every language. */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    return when {
        ms <= 0 -> "0m"
        totalMinutes < 1 -> "<1m"
        totalMinutes < 60 -> "${totalMinutes}m"
        else -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
    }
}

fun formatClock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(Instant.ofEpochMilli(ms))

fun formatShortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM").format(date)

fun formatMediumDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)
