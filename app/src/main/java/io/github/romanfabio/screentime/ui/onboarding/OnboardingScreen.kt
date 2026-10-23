package io.github.romanfabio.screentime.ui.onboarding

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.romanfabio.screentime.R
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.ui.components.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STEP_WELCOME = 0
private const val STEP_USAGE = 1
private const val STEP_BATTERY = 2
private const val STEP_IMPORT = 3

private sealed interface ImportState {
    data object Working : ImportState
    data class Done(val sessions: Int, val unlocks: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    val permissions by rememberPermissionState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                STEP_WELCOME -> {
                    Heading(R.string.onb_welcome_title)
                    Text(stringResource(R.string.onb_welcome_body))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { step = if (permissions.usageAccess) STEP_BATTERY else STEP_USAGE }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_continue))
                    }
                }
                STEP_USAGE -> UsageStep(
                    granted = permissions.usageAccess,
                    onNext = { step = STEP_BATTERY },
                )
                STEP_BATTERY -> BatteryStep(
                    exempt = permissions.batteryExempt,
                    onNext = { step = STEP_IMPORT },
                )
                else -> ImportStep(onFinished = onFinished)
            }
        }
    }
}

@Composable
private fun Heading(resId: Int) {
    Text(stringResource(resId), style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun UsageStep(granted: Boolean, onNext: () -> Unit) {
    val context = LocalContext.current
    Heading(R.string.onb_usage_title)
    Text(stringResource(R.string.onb_usage_body))
    if (granted) {
        Text(stringResource(R.string.onb_usage_granted), color = MaterialTheme.colorScheme.primary)
        Button(onClick = onNext, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_continue)) }
    } else {
        Button(
            onClick = { context.startActivity(context.container.permissions.usageAccessIntent()) },
            Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onb_usage_action)) }
    }
}

@Composable
private fun BatteryStep(exempt: Boolean, onNext: () -> Unit) {
    val context = LocalContext.current
    val permissions = context.container.permissions
    Heading(R.string.onb_battery_title)
    Text(stringResource(R.string.onb_battery_body))
    Text(
        stringResource(if (exempt) R.string.onb_battery_status_on else R.string.onb_battery_status_off),
        color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (!exempt) {
        Button(onClick = { context.startActivity(permissions.batteryExemptionIntent()) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_battery_action))
        }
    }
    OutlinedButton(onClick = { context.startActivity(permissions.appDetailsIntent()) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_battery_app_settings))
    }
    Text(stringResource(brandTips()), style = MaterialTheme.typography.bodyMedium)
    if (exempt) {
        Button(onClick = onNext, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_continue)) }
    } else {
        TextButton(onClick = onNext, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_skip)) }
    }
}

private fun brandTips(): Int = when (Build.MANUFACTURER.lowercase()) {
    "realme", "oppo", "oneplus" -> R.string.onb_battery_tips_realme
    "xiaomi", "redmi", "poco" -> R.string.onb_battery_tips_xiaomi
    "samsung" -> R.string.onb_battery_tips_samsung
    else -> R.string.onb_battery_tips_generic
}

@Composable
private fun ImportStep(onFinished: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ImportState>(ImportState.Working) }

    LaunchedEffect(attempt) {
        state = ImportState.Working
        state = try {
            withContext(Dispatchers.Default) {
                context.container.ingest.run()
                // Totals, not this run's delta: an earlier background run may already have imported most of it.
                val dao = context.container.db.usageDao()
                ImportState.Done(dao.sessionCount(), dao.unlockCount())
            }
        } catch (e: Exception) {
            ImportState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Heading(R.string.onb_import_title)
    when (val s = state) {
        ImportState.Working -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.onb_import_working))
        }
        is ImportState.Done -> {
            Text(stringResource(R.string.onb_import_done, s.sessions, s.unlocks))
            Button(onClick = {
                context.container.prefs.onboardingDone = true
                onFinished()
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_finish)) }
        }
        is ImportState.Failed -> {
            Text(stringResource(R.string.onb_import_error, s.message), color = MaterialTheme.colorScheme.error)
            Button(onClick = { attempt++ }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_import_retry)) }
        }
    }
}
