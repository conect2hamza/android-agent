package com.personal.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.personal.assistant.ui.theme.StatusColors

/**
 * Charts drawn directly on a Compose canvas.
 *
 * No charting library. The four shapes this app needs are a hundred lines of drawing code between
 * them, while a library would add a dependency to audit for licensing, weight in the APK, and in most
 * cases a WebView or a rendering thread -- all for an offline screen showing at most 31 data points.
 */

data class ChartSlice(val label: String, val value: Float, val color: Color)

@Composable
fun StatusBarChart(
    completed: Int,
    pending: Int,
    missed: Int,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        Triple("Completed", completed, StatusColors.completed),
        Triple("Pending", pending, StatusColors.pending),
        Triple("Missed", missed, StatusColors.missed),
    )
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, value, color) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(88.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            // A zero-width bar is invisible, so an empty bucket keeps a hairline.
                            .fillMaxWidth(if (value == 0) 0.02f else value.toFloat() / max)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(color),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(value.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            }
        }
    }
}

/** Activity across a week or month: one column per day. */
@Composable
fun DailyActivityChart(
    values: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    barColor: Color = StatusColors.inProgress,
) {
    if (values.isEmpty()) return
    val max = values.maxOf { it.second }.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for ((_, value) in values) {
                val fraction = (value.toFloat() / max).coerceIn(0.02f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (value == 0) MaterialTheme.colorScheme.surfaceVariant else barColor),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((label, _) in values) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Completion rate over time. */
@Composable
fun TrendLineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = StatusColors.inProgress,
) {
    if (points.size < 2) return
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        val stepX = size.width / (points.size - 1)
        // The axis is always 0..100 so two charts side by side are comparable.
        fun yFor(value: Float) = size.height - (value.coerceIn(0f, 100f) / 100f) * size.height

        listOf(0f, 50f, 100f).forEach { level ->
            val y = yFor(level)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val path = Path().apply {
            moveTo(0f, yFor(points.first()))
            points.forEachIndexed { index, value ->
                if (index > 0) lineTo(index * stepX, yFor(value))
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 4f))
        points.forEachIndexed { index, value ->
            drawCircle(lineColor, radius = 5f, center = Offset(index * stepX, yFor(value)))
        }
    }
}

/** Category distribution. */
@Composable
fun CategoryDonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (total <= 0f) return

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(120.dp)) {
            var startAngle = -90f
            val thickness = size.minDimension * 0.22f
            slices.forEach { slice ->
                val sweep = (slice.value / total) * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = thickness),
                    topLeft = Offset(thickness / 2, thickness / 2),
                    size = Size(size.width - thickness, size.height - thickness),
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.take(6).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(slice.color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${slice.label} (${(slice.value / total * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** A single completion meter, used on the dashboard and at the top of each report. */
@Composable
fun CompletionMeter(
    rate: Double,
    modifier: Modifier = Modifier,
) {
    val fraction = (rate / 100.0).coerceIn(0.0, 1.0).toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            fraction >= 0.75f -> StatusColors.completed
                            fraction >= 0.4f -> StatusColors.pending
                            else -> StatusColors.missed
                        },
                    ),
            )
        }
    }
}
