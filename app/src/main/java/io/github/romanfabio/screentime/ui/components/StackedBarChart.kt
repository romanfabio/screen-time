package io.github.romanfabio.screentime.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.romanfabio.screentime.domain.Bar

/** Chart colours for the top apps, in rank order; rank -1 ("other") uses [other]. */
data class ChartColors(val series: List<Color>, val other: Color) {
    fun forRank(rank: Int): Color = if (rank in series.indices) series[rank] else other
}

@Composable
fun rememberChartColors(): ChartColors {
    val c = MaterialTheme.colorScheme
    return ChartColors(
        series = listOf(c.primary, c.tertiary, c.secondary, c.primary.copy(alpha = 0.55f), c.tertiary.copy(alpha = 0.55f)),
        other = c.outlineVariant,
    )
}

/**
 * Stacked columns, one per [Bar]. [labelFor] returns the text under a bar or null to leave it blank, which lets
 * the caller thin the labels out (every 6th hour, every 5th day…).
 */
@Composable
fun StackedBarChart(
    bars: List<Bar>,
    colors: ChartColors,
    labelFor: (index: Int, bar: Bar) -> String?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
    val baseline = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val labelArea = 20.dp.toPx()
        val chartHeight = size.height - labelArea
        val slot = size.width / bars.size
        val barWidth = slot * 0.62f
        val max = bars.maxOfOrNull { it.totalMs }?.coerceAtLeast(1L) ?: 1L

        drawLine(baseline, Offset(0f, chartHeight), Offset(size.width, chartHeight), strokeWidth = 1.dp.toPx())

        bars.forEachIndexed { i, bar ->
            val x = i * slot + (slot - barWidth) / 2
            var bottom = chartHeight
            bar.segments.forEach { seg ->
                val h = chartHeight * (seg.ms.toFloat() / max)
                if (h > 0f) {
                    drawRoundRect(
                        color = colors.forRank(seg.rank),
                        topLeft = Offset(x, bottom - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    bottom -= h
                }
            }
            labelFor(i, bar)?.let { text ->
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(x + barWidth / 2 - layout.size.width / 2f, chartHeight + 4.dp.toPx()))
            }
        }
    }
}
