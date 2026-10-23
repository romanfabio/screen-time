package io.github.romanfabio.screentime.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val D = LocalDate.of(2026, 3, 10)
private val apps = mapOf(
    "a.b" to AppMeta("Plain", isSystem = false, isLauncher = false, hasLauncherIcon = true),
    "c.d" to AppMeta("Comma, \"Quote\"", isSystem = true, isLauncher = false, hasLauncherIcon = false),
)

class ExporterTest {

    @Test
    fun `csv sums hours into one row per app and day and escapes fields`() {
        val csv = Exporter.csv(
            listOf(
                HourlyRow(D, 9, "a.b", 30_000, 1, 2), HourlyRow(D, 10, "a.b", 45_000, 2, 0),
                HourlyRow(D.minusDays(1), 1, "c.d", 5_000, 1, 0),
                HourlyRow(D, 1, "unknown", 1_000, 1, 0),
            ),
            apps,
        )
        assertEquals(
            listOf(
                "date,package,app,foreground_seconds,opens,notifications",
                "2026-03-09,c.d,\"Comma, \"\"Quote\"\"\",5,1,0",
                "2026-03-10,a.b,Plain,75,3,2",
                "2026-03-10,unknown,unknown,1,1,0",
            ),
            csv.trim().lines(),
        )
    }

    @Test
    fun `json contains all three sections with escaped strings`() {
        val json = Exporter.json(
            daily = listOf(DailyRow(D, 1, 2, 3, null, 99)),
            hourly = listOf(HourlyRow(D, 9, "a.b", 1000, 1, 0)),
            apps = apps,
            exportedAtMs = 123,
        )
        assertTrue(json.contains("\"exportedAtMs\": 123"))
        assertTrue(json.contains("\"firstPickupMs\":null"))
        assertTrue(json.contains("\"label\":\"Comma, \\\"Quote\\\"\""))
        assertTrue(json.contains("\"hour\":9"))
    }

    @Test
    fun `json escapes control characters`() {
        assertEquals("\"a\\nb\\u0001\"", Exporter.q("a\nb\u0001"))
    }

    @Test
    fun `empty data still gives valid shapes`() {
        val json = Exporter.json(emptyList(), emptyList(), emptyMap(), 0)
        assertTrue(json.contains("\"daily\": []"))
        assertTrue(json.contains("\"hourly\": []"))
    }
}
