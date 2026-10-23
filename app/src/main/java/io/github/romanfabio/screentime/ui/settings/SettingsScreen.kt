package io.github.romanfabio.screentime.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.romanfabio.screentime.R
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.ui.components.rememberPermissionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sync older than this is flagged: Android only keeps ~10 days of events, so a long silence can lose data. */
private const val STALE_AFTER_MS = 3L * 24 * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDebug: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val permissions by rememberPermissionState()

    var showHidden by remember { mutableStateOf(container.prefs.showHiddenApps) }
    var lastSync by remember { mutableStateOf<Long?>(null) }
    var syncing by remember { mutableStateOf(false) }
    // null = nothing yet, "" = success, anything else = error text
    var exportResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { lastSync = container.dashboard.lastSyncMs() }

    fun export(uri: Uri?, produce: suspend () -> String) {
        if (uri == null) return
        scope.launch {
            exportResult = try {
                withContext(Dispatchers.IO) {
                    val text = produce()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("cannot open file")
                }
                ""
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        export(uri) { container.dashboard.exportCsv() }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        export(uri) { container.dashboard.exportJson(System.currentTimeMillis()) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.settings_section_apps))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_hidden), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_show_hidden_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showHidden, onCheckedChange = {
                    showHidden = it
                    container.prefs.showHiddenApps = it
                })
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_permissions))
            StatusRow(
                label = stringResource(R.string.settings_usage_access),
                ok = permissions.usageAccess,
                actionLabel = stringResource(R.string.banner_usage_access_action),
                onAction = { context.startActivity(container.permissions.usageAccessIntent()) },
            )
            StatusRow(
                label = stringResource(R.string.settings_battery),
                ok = permissions.batteryExempt,
                actionLabel = stringResource(R.string.settings_fix),
                onAction = { context.startActivity(container.permissions.batteryExemptionIntent()) },
            )
            OutlinedButton(onClick = { context.startActivity(container.permissions.appDetailsIntent()) }) {
                Text(stringResource(R.string.onb_battery_app_settings))
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_data))
            val stale = lastSync?.let { System.currentTimeMillis() - it > STALE_AFTER_MS } ?: true
            Text(
                stringResource(R.string.settings_last_sync, lastSync?.let(::formatDateTime) ?: stringResource(R.string.settings_never)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (stale) {
                Text(
                    stringResource(R.string.settings_stale_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = permissions.usageAccess && !syncing,
                onClick = {
                    syncing = true
                    scope.launch {
                        withContext(Dispatchers.Default) { runCatching { container.ingest.run() } }
                        lastSync = container.dashboard.lastSyncMs()
                        syncing = false
                    }
                },
            ) { Text(stringResource(if (syncing) R.string.settings_syncing else R.string.settings_sync_now)) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { csvLauncher.launch("screentime-${LocalDate.now()}.csv") }) {
                    Text(stringResource(R.string.settings_export_csv))
                }
                OutlinedButton(onClick = { jsonLauncher.launch("screentime-${LocalDate.now()}.json") }) {
                    Text(stringResource(R.string.settings_export_json))
                }
            }
            exportResult?.let {
                Text(
                    if (it.isEmpty()) stringResource(R.string.settings_export_done) else stringResource(R.string.settings_export_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_about))
            Text(stringResource(R.string.settings_version, versionName(context)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.settings_diagnostics)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StatusRow(label: String, ok: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (ok) R.string.settings_status_on else R.string.settings_status_off),
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!ok) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

private fun formatDateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(ms))

private fun versionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
