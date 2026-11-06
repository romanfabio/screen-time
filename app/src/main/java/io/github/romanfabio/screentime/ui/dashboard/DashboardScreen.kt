package io.github.romanfabio.screentime.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.romanfabio.screentime.R
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.domain.AppRow
import io.github.romanfabio.screentime.domain.ComparisonPeriod
import io.github.romanfabio.screentime.domain.comparisonPeriod
import io.github.romanfabio.screentime.domain.DashboardBuilder
import io.github.romanfabio.screentime.domain.DashboardData
import io.github.romanfabio.screentime.domain.DashboardRange
import io.github.romanfabio.screentime.domain.Metric
import io.github.romanfabio.screentime.ui.components.AppIcon
import io.github.romanfabio.screentime.ui.components.ChartColors
import io.github.romanfabio.screentime.ui.components.StackedBarChart
import io.github.romanfabio.screentime.ui.components.UsageAccessBanner
import io.github.romanfabio.screentime.ui.components.rememberChartColors
import io.github.romanfabio.screentime.ui.components.rememberPermissionState
import io.github.romanfabio.screentime.ui.formatClock
import io.github.romanfabio.screentime.ui.formatDuration
import io.github.romanfabio.screentime.ui.formatMediumDate
import io.github.romanfabio.screentime.ui.formatShortDate
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private enum class SortBy { TIME, OPENS, NOTIFICATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpenSettings: () -> Unit, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val permissions by rememberPermissionState()
    val vm: DashboardViewModel = viewModel(
        factory = viewModelFactory { initializer { DashboardViewModel(context.container.dashboard) } },
    )
    val params by vm.params.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var sortName by rememberSaveable { mutableStateOf(SortBy.TIME.name) }
    val sort = SortBy.valueOf(sortName)
    val colors = rememberChartColors()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.title_dashboard)) },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissions.usageAccess) {
                item {
                    UsageAccessBanner(onFix = { context.startActivity(context.container.permissions.usageAccessIntent()) })
                }
            }
            item { RangeSelector(params.range, vm::setRange) }
            data?.let { d ->
                item { PeriodStepper(d.range, d.startDate, d.endDate, onPrevious = { vm.shift(-1) }, onNext = { vm.shift(1) }, canGoNext = params.endDate != null) }
                item { HeroCard(d) }
                item { Tiles(d) }
                if (!d.hasData) {
                    item { EmptyCard() }
                } else {
                    item { ChartCard(d, colors) }
                    item { SortRow(sort) { sortName = it.name } }
                    val sorted = when (sort) {
                        SortBy.TIME -> d.apps
                        SortBy.OPENS -> d.apps.sortedByDescending { it.opens }
                        SortBy.NOTIFICATIONS -> d.apps.sortedByDescending { it.notifications }
                    }
                    val maxMs = d.apps.maxOfOrNull { it.foregroundMs }?.coerceAtLeast(1L) ?: 1L
                    items(sorted, key = { it.packageName }) { app ->
                        AppListRow(app, maxMs, d.topApps.indexOf(app.packageName), colors) {
                            if (app.packageName != DashboardBuilder.HOME_PACKAGE) onOpenApp(app.packageName)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeSelector(selected: DashboardRange, onSelect: (DashboardRange) -> Unit) {
    val options = listOf(
        DashboardRange.TODAY to R.string.range_today,
        DashboardRange.WEEK to R.string.range_week,
        DashboardRange.MONTH to R.string.range_month,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (range, label) ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
internal fun PeriodStepper(
    range: DashboardRange,
    startDate: LocalDate,
    endDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean,
) {
    val today = LocalDate.now()
    val title = when {
        range == DashboardRange.TODAY && endDate == today -> stringResource(R.string.date_today)
        range == DashboardRange.TODAY && endDate == today.minusDays(1) -> stringResource(R.string.date_yesterday)
        range == DashboardRange.TODAY -> formatMediumDate(endDate)
        else -> "${formatShortDate(startDate)} – ${formatShortDate(endDate)}"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = onNext, enabled = canGoNext) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
internal fun comparisonLabel(range: DashboardRange, endDate: LocalDate): String = stringResource(
    when (comparisonPeriod(range, endDate, LocalDate.now())) {
        ComparisonPeriod.YESTERDAY -> R.string.vs_yesterday
        ComparisonPeriod.PREVIOUS_DAY -> R.string.vs_previous_day
        ComparisonPeriod.PREVIOUS_WEEK -> R.string.vs_previous_week
        ComparisonPeriod.PREVIOUS_MONTH -> R.string.vs_previous_month
    },
)

@Composable
internal fun DeltaText(metric: Metric, range: DashboardRange, endDate: LocalDate, modifier: Modifier = Modifier) {
    val pct = metric.changePercent ?: return
    val arrow = when {
        pct > 0 -> "▲"
        pct < 0 -> "▼"
        else -> "●"
    }
    Text(
        "$arrow ${kotlin.math.abs(pct)}% ${comparisonLabel(range, endDate)}",
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HeroCard(d: DashboardData) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.screen_time), style = MaterialTheme.typography.labelLarge)
            Text(formatDuration(d.screenTime.value), style = MaterialTheme.typography.displayMedium)
            DeltaText(d.screenTime, d.range, d.endDate)
            Text(
                stringResource(R.string.unlocked_line, formatDuration(d.unlockedMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tiles(d: DashboardData) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.tile_unlocks), d.unlocks.value.toString(), Modifier.weight(1f)) { DeltaText(d.unlocks, d.range, d.endDate) }
            Tile(stringResource(R.string.tile_notifications), d.notifications.value.toString(), Modifier.weight(1f)) {
                DeltaText(d.notifications, d.range, d.endDate)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.range == DashboardRange.TODAY) {
                Tile(stringResource(R.string.tile_first_pickup), d.firstPickupMs?.let { formatClock(it) } ?: "–", Modifier.weight(1f))
                Tile(stringResource(R.string.tile_last_use), d.lastUseMs?.let { formatClock(it) } ?: "–", Modifier.weight(1f))
            } else {
                Tile(stringResource(R.string.tile_daily_average), formatDuration(d.averageScreenTimeMs), Modifier.weight(1f))
                Tile(stringResource(R.string.tile_unlocks_per_day), d.averageUnlocks.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun Tile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyleSmall: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = if (valueStyleSmall) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            footer()
        }
    }
}

@Composable
private fun ChartCard(d: DashboardData, colors: ChartColors) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StackedBarChart(
                bars = d.bars,
                colors = colors,
                labelFor = { index, bar ->
                    if (d.range == DashboardRange.TODAY) {
                        bar.hour?.takeIf { it % 6 == 0 }?.toString()
                    } else {
                        val date = bar.date ?: return@StackedBarChart null
                        if (d.range == DashboardRange.WEEK) {
                            date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                        } else if (index % 5 == 0) {
                            date.dayOfMonth.toString()
                        } else {
                            null
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SortRow(selected: SortBy, onSelect: (SortBy) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        listOf(
            SortBy.TIME to R.string.sort_time,
            SortBy.OPENS to R.string.sort_opens,
            SortBy.NOTIFICATIONS to R.string.sort_notifications,
        ).forEach { (sort, label) ->
            FilterChip(selected = sort == selected, onClick = { onSelect(sort) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun AppListRow(app: AppRow, maxMs: Long, rank: Int, colors: ChartColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rank >= 0) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.forRank(rank)))
                }
                Text(if (app.packageName == DashboardBuilder.HOME_PACKAGE) stringResource(R.string.home_screen) else app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatDuration(app.foregroundMs), style = MaterialTheme.typography.bodyLarge)
            }
            LinearProgressIndicator(
                progress = { app.foregroundMs.toFloat() / maxMs },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (rank >= 0) colors.forRank(rank) else colors.other,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(
                pluralStringResource(R.plurals.opens_count, app.opens, app.opens) + " · " +
                    pluralStringResource(R.plurals.notifications_count, app.notifications, app.notifications),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
