package com.personal.assistant.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.core.report.CategorySlice
import com.personal.assistant.core.report.TaskTotals
import com.personal.assistant.ui.components.CategoryDonutChart
import com.personal.assistant.ui.components.ChartSlice
import com.personal.assistant.ui.components.CompletionMeter
import com.personal.assistant.ui.components.DailyActivityChart
import com.personal.assistant.ui.components.StatusBarChart
import com.personal.assistant.ui.components.TrendLineChart
import com.personal.assistant.ui.containerViewModel
import com.personal.assistant.ui.theme.StatusColors
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ReportsScreen(container: AppContainer) {
    val viewModel = containerViewModel(container) { ReportsViewModel(it) }
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReportPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.period == period,
                    onClick = { viewModel.setPeriod(period) },
                    label = { Text(period.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.shift(-1) }) { Text("Previous") }
            Text(state.anchor.toString(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { viewModel.shift(1) }) { Text("Next") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.period) {
                ReportPeriod.DAILY -> state.daily?.let { report ->
                    item { TotalsCard("Daily report", report.totals, report.plannedMinutes, report.trackedMinutes) }
                    item { CategoryCard(report.categories) }
                    if (report.timeline.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Timeline", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    report.timeline.forEach { entry ->
                                        Text(
                                            "${entry.at.toLocalTime().withSecond(0).withNano(0)}  ${entry.label}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ReportPeriod.WEEKLY -> state.weekly?.let { report ->
                    item { TotalsCard("Weekly report", report.totals, report.plannedMinutes, report.trackedMinutes) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Daily activity", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                DailyActivityChart(
                                    values = report.perDay.map { point ->
                                        point.date.dayOfWeek
                                            .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                            .take(2) to point.totals.completed
                                    },
                                )
                            }
                        }
                    }
                    item { CategoryCard(report.categories) }
                }

                ReportPeriod.MONTHLY -> state.monthly?.let { report ->
                    item { TotalsCard("Monthly report", report.totals, report.plannedMinutes, report.trackedMinutes) }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Completion trend", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                TrendLineChart(points = report.weeklyTrend.map { it.second.toFloat() })
                            }
                        }
                    }
                    item { CategoryCard(report.categories) }
                    if (report.mostActiveDays.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Most active days", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    report.mostActiveDays.forEach { point ->
                                        Text(
                                            "${point.date} - ${point.totals.completed} completed" +
                                                if (point.trackedMinutes > 0) {
                                                    ", ${TimeTracker.format(point.trackedMinutes)} tracked"
                                                } else {
                                                    ""
                                                },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TotalsCard(title: String, totals: TaskTotals, plannedMinutes: Int, trackedMinutes: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Total tasks: ${totals.total}", style = MaterialTheme.typography.bodyMedium)
            Text("Completed: ${totals.completed}", style = MaterialTheme.typography.bodyMedium)
            if (totals.partial > 0) {
                Text("Partial: ${totals.partial}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Missed: ${totals.missed}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(
                "Completion rate ${"%.1f".format(totals.completionRate)}%",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            CompletionMeter(totals.completionRate)
            Spacer(Modifier.height(12.dp))
            Text(
                "Planned ${TimeTracker.format(plannedMinutes)} - tracked ${TimeTracker.format(trackedMinutes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            StatusBarChart(
                completed = totals.completed,
                pending = totals.pending + totals.inProgress,
                missed = totals.missed,
            )
        }
    }
}

@Composable
private fun CategoryCard(categories: List<CategorySlice>) {
    if (categories.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Where the time went", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            CategoryDonutChart(
                slices = categories.mapIndexed { index, slice ->
                    ChartSlice(
                        label = slice.label,
                        value = slice.minutes.toFloat().coerceAtLeast(1f),
                        color = StatusColors.categoryPalette[index % StatusColors.categoryPalette.size],
                    )
                },
            )
        }
    }
}
