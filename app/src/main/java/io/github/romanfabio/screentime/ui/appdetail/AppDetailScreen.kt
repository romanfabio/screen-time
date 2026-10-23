package io.github.romanfabio.screentime.ui.appdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.romanfabio.screentime.R
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.domain.AppDetailData
import io.github.romanfabio.screentime.domain.DashboardRange
import io.github.romanfabio.screentime.ui.components.AppIcon
import io.github.romanfabio.screentime.ui.components.StackedBarChart
import io.github.romanfabio.screentime.ui.components.rememberChartColors
import io.github.romanfabio.screentime.ui.dashboard.DeltaText
import io.github.romanfabio.screentime.ui.dashboard.PeriodStepper
import io.github.romanfabio.screentime.ui.dashboard.RangeSelector
import io.github.romanfabio.screentime.ui.dashboard.Tile
import io.github.romanfabio.screentime.ui.formatDuration
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: AppDetailViewModel = viewModel(
        key = packageName,
        factory = viewModelFactory { initializer { AppDetailViewModel(context.container.dashboard, packageName) } },
    )
    val params by vm.params.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(data?.label ?: packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(packageName, size = 48.dp)
                    Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { RangeSelector(params.range, vm::setRange) }
            data?.let { d ->
                item {
                    PeriodStepper(d.range, d.startDate, d.endDate, { vm.shift(-1) }, { vm.shift(1) }, canGoNext = params.endDate != null)
                }
                item { Summary(d) }
                item { ChartCard(d) }
                item { HourOfDayCard(d) }
            }
        }
    }
}

@Composable
private fun Summary(d: AppDetailData) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.detail_time_in_app), style = MaterialTheme.typography.labelLarge)
            Text(formatDuration(d.foreground.value), style = MaterialTheme.typography.displayMedium)
            DeltaText(d.foreground, d.range)
        }
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.detail_opens), d.opens.value.toString(), Modifier.weight(1f)) { DeltaText(d.opens, d.range) }
            Tile(stringResource(R.string.tile_notifications), d.notifications.value.toString(), Modifier.weight(1f)) {
                DeltaText(d.notifications, d.range)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.detail_avg_session), formatDuration(d.avgSessionMs), Modifier.weight(1f))
            Tile(
                stringResource(R.string.tile_daily_average),
                formatDuration(d.foreground.value / d.range.days),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChartCard(d: AppDetailData) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            StackedBarChart(
                bars = d.bars,
                colors = rememberChartColors(),
                labelFor = { index, bar ->
                    if (d.range == DashboardRange.TODAY) {
                        bar.hour?.takeIf { it % 6 == 0 }?.toString()
                    } else {
                        val date = bar.date ?: return@StackedBarChart null
                        if (d.range == DashboardRange.WEEK) date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                        else if (index % 5 == 0) date.dayOfMonth.toString() else null
                    }
                },
            )
        }
    }
}

/** 24 cells, one per hour of day, shaded by how much the app is used then (summed over the period). */
@Composable
private fun HourOfDayCard(d: AppDetailData) {
    val max = d.hourOfDayMs.max().coerceAtLeast(1L)
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.detail_when), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                d.hourOfDayMs.forEach { ms ->
                    val color: Color = if (ms == 0L) empty else base.copy(alpha = 0.2f + 0.8f * ms / max)
                    Column(Modifier.weight(1f)) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(3.dp)).background(color),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "6", "12", "18", "24").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
