package io.github.romanfabio.screentime.domain

/** Plain-text exports of the stored aggregates. Pure Kotlin so it is easy to test. */
object Exporter {

    /** One row per app and day: `date,package,app,foreground_seconds,opens,notifications`. */
    fun csv(hourly: List<HourlyRow>, apps: Map<String, AppMeta>): String {
        val sb = StringBuilder("date,package,app,foreground_seconds,opens,notifications\n")
        hourly.groupBy { it.date to it.packageName }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, rows) ->
                sb.append(key.first).append(',')
                    .append(csvField(key.second)).append(',')
                    .append(csvField(apps[key.second]?.label ?: key.second)).append(',')
                    .append(rows.sumOf { it.foregroundMs } / 1000).append(',')
                    .append(rows.sumOf { it.opens }).append(',')
                    .append(rows.sumOf { it.notifications }).append('\n')
            }
        return sb.toString()
    }

    /** Everything we aggregate: device totals per day, apps, and per-hour app usage. */
    fun json(daily: List<DailyRow>, hourly: List<HourlyRow>, apps: Map<String, AppMeta>, exportedAtMs: Long): String {
        val sb = StringBuilder()
        sb.append("{\n  \"exportedAtMs\": ").append(exportedAtMs).append(",\n")

        sb.append("  \"daily\": [")
        sb.appendList(daily) { d ->
            "{\"date\":${q(d.date.toString())},\"unlockedMs\":${d.unlockedMs},\"screenOnMs\":${d.screenOnMs}," +
                "\"unlocks\":${d.unlocks},\"firstPickupMs\":${d.firstPickupMs},\"lastUseMs\":${d.lastUseMs}}"
        }
        sb.append("],\n  \"apps\": [")
        sb.appendList(apps.entries.sortedBy { it.key }) { (pkg, m) ->
            "{\"package\":${q(pkg)},\"label\":${q(m.label)},\"isSystem\":${m.isSystem},\"isLauncher\":${m.isLauncher}}"
        }
        sb.append("],\n  \"hourly\": [")
        sb.appendList(hourly) { h ->
            "{\"date\":${q(h.date.toString())},\"hour\":${h.hour},\"package\":${q(h.packageName)}," +
                "\"foregroundMs\":${h.foregroundMs},\"opens\":${h.opens},\"notifications\":${h.notifications}}"
        }
        sb.append("]\n}\n")
        return sb.toString()
    }

    private fun <T> StringBuilder.appendList(items: List<T>, render: (T) -> String) {
        items.forEachIndexed { i, item ->
            append(if (i == 0) "\n    " else ",\n    ").append(render(item))
        }
        if (items.isNotEmpty()) append("\n  ")
    }

    internal fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    internal fun q(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
