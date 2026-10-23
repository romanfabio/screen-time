package io.github.romanfabio.screentime.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.romanfabio.screentime.R
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.data.PermissionState

/** Permission state that is re-read every time the app returns to the foreground (e.g. from Settings). */
@Composable
fun rememberPermissionState(): State<PermissionState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(context.container.permissions.current()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.value = context.container.permissions.current()
    }
    return state
}

@Composable
fun UsageAccessBanner(onFix: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.banner_usage_access_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.banner_usage_access_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onFix) { Text(stringResource(R.string.banner_usage_access_action)) }
        }
    }
}
