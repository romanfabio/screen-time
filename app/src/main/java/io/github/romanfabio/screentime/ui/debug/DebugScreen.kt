package io.github.romanfabio.screentime.ui.debug

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import io.github.romanfabio.screentime.sync.SyncScheduler
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.data.source.RawEvent
import io.github.romanfabio.screentime.data.source.UsageEventSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Diagnostics"

private val EVENT_NAMES = mapOf(
    1 to "ACTIVITY_RESUMED", 2 to "ACTIVITY_PAUSED", 5 to "CONFIGURATION_CHANGE",
    7 to "USER_INTERACTION", 8 to "SHORTCUT_INVOCATION", 9 to "CHOOSER_ACTION",
    10 to "NOTIFICATION_SEEN", 11 to "STANDBY_BUCKET_CHANGED", 12 to "NOTIFICATION_INTERRUPTION",
    15 to "SCREEN_INTERACTIVE", 16 to "SCREEN_NON_INTERACTIVE", 17 to "KEYGUARD_SHOWN",
    18 to "KEYGUARD_HIDDEN", 19 to "FOREGROUND_SERVICE_START", 20 to "FOREGROUND_SERVICE_STOP",
    23 to "ACTIVITY_STOPPED", 26 to "DEVICE_SHUTDOWN", 27 to "DEVICE_STARTUP", 30 to "LOCUS_ID_SET",
)

private fun typeName(type: Int) = EVENT_NAMES[type] ?: "TYPE_$type"

private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private fun format(ms: Long): String = formatter.format(Instant.ofEpochMilli(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val source = remember { UsageEventSource(context) }
    var hasAccess by remember { mutableStateOf(source.hasUsageAccess()) }
    var refresh by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf(listOf("Loading...")) }
    var latest by remember { mutableStateOf(emptyList<RawEvent>()) }
    var ingestLines by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()

    fun runIngest() {
        scope.launch {
            ingestLines = listOf("Ingesting...")
            ingestLines = withContext(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val s = context.container.ingest.run()
                val dao = context.container.db.usageDao()
                val lines = mutableListOf<String>()
                lines += "Ingest took ${System.currentTimeMillis() - started} ms: events=${s.events} " +
                    "sessions=${s.sessions} intervals=${s.intervals} unlocks=${s.unlocks} notif=${s.notifications}"
                lines += "-- daily (date: unlocked / screen-on / unlocks / first / last) --"
                val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                dao.dailyBetween("0000-00-00", "9999-99-99").forEach {
                    lines += "${it.date}: ${it.unlockedMs / 60_000}m / ${it.screenOnMs / 60_000}m / ${it.unlocks} / " +
                        "${it.firstPickupMs?.let { t -> timeFmt.format(Instant.ofEpochMilli(t)) }} / " +
                        "${it.lastUseMs?.let { t -> timeFmt.format(Instant.ofEpochMilli(t)) }}"
                }
                val today = LocalDate.now().toString()
                lines += "-- top apps today (fg min / opens / notif) --"
                val labels = dao.allAppInfo().associate { it.packageName to it.label }
                dao.appTotalsBetween(today, today).take(12).forEach {
                    lines += "${labels[it.packageName] ?: it.packageName}: ${it.foregroundMs / 60_000}m / ${it.opens} / ${it.notifications}"
                }
                lines += "-- 6 longest UNLOCKED intervals --"
                dao.intervalsOverlapping(0, Long.MAX_VALUE).filter { it.kind == "UNLOCKED" }
                    .sortedByDescending { it.endMs - it.startMs }.take(6).forEach {
                        lines += "${format(it.startMs)} -> ${format(it.endMs)} (${(it.endMs - it.startMs) / 60_000}m)"
                    }
                lines.forEach { Log.i(TAG, it) }
                lines
            }
        }
    }

    var syncLines by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(refresh) {
        syncLines = withContext(Dispatchers.Default) {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("sync-periodic").get()
            val last = context.container.db.usageDao().getState("last_sync_ms")?.toLongOrNull()
            val out = mutableListOf("-- background sync --")
            infos.forEach {
                val next = if (it.nextScheduleTimeMillis == Long.MAX_VALUE) "n/a" else format(it.nextScheduleTimeMillis)
                out += "periodic work: ${it.state}, next ~$next, attempts=${it.runAttemptCount}"
            }
            if (infos.isEmpty()) out += "periodic work: NOT SCHEDULED"
            out += "last sync: ${last?.let { format(it) } ?: "never"}"
            out.forEach { Log.i(TAG, it) }
            out
        }
        hasAccess = source.hasUsageAccess()
        if (!hasAccess) {
            summary = listOf("Usage access NOT granted")
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val events = source.queryEvents(0L, now)
            val lines = mutableListOf<String>()
            lines += "Total events: ${events.size}"
            if (events.isNotEmpty()) {
                val first = events.first().timeMs
                lines += "Earliest: ${format(first)} (${(now - first) / 86_400_000.0} days back)"
                lines += "Latest: ${format(events.last().timeMs)}"
            }
            lines += "-- by type --"
            events.groupingBy { it.type }.eachCount().toSortedMap()
                .forEach { (t, c) -> lines += "${typeName(t)} ($t): $c" }
            lines += "-- per day (all types) --"
            events.groupingBy { format(it.timeMs).substring(0, 10) }.eachCount().toSortedMap()
                .forEach { (d, c) -> lines += "$d: $c" }
            val notif = events.filter { it.type == 12 }
            lines += "-- notification events (12) --"
            lines += "count=${notif.size}, withPackage=${notif.count { it.packageName != null }}"
            notif.take(3).forEach { lines += "${format(it.timeMs)} ${it.packageName}" }
            lines.forEach { Log.i(TAG, it) }
            // Full raw log for offline analysis (adb: run-as <pkg> cat files/raw_events.csv).
            context.filesDir.resolve("raw_events.csv").bufferedWriter().use { w ->
                events.forEach { w.write("${it.timeMs},${it.type},${it.packageName.orEmpty()},${it.className.orEmpty()}\n") }
            }
            lines to events.takeLast(150).reversed()
        }
        summary = result.first
        latest = result.second
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug: raw events") },
                actions = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasAccess) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) { Text("Open usage access settings") }
                    }
                    Button(onClick = { refresh++ }) { Text("Reload") }
                    if (hasAccess) Button(onClick = { runIngest() }) { Text("Ingest now") }
                    Button(onClick = { SyncScheduler.syncNow(context) }) { Text("Run background worker now") }
                }
            }
            items(syncLines) { Text(it, style = MaterialTheme.typography.bodySmall) }
            items(ingestLines) { Text(it, style = MaterialTheme.typography.bodySmall) }
            items(summary) { Text(it, style = MaterialTheme.typography.bodySmall) }
            item { Text("-- latest 150 events --", style = MaterialTheme.typography.labelMedium) }
            items(latest) {
                Text(
                    "${format(it.timeMs)} ${typeName(it.type)} ${it.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
