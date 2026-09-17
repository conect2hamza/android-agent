package com.personal.assistant.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.ui.components.TaskCard
import com.personal.assistant.ui.containerViewModel
import com.personal.assistant.ui.theme.StatusColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(container: AppContainer, use24HourClock: Boolean, onOpenTask: (Long) -> Unit) {
    val viewModel = containerViewModel(container) { CalendarViewModel(it) }
    val state by viewModel.state.collectAsState()
    val today = container.now().toLocalDate()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalendarView.entries.forEach { view ->
                FilterChip(
                    selected = state.view == view,
                    onClick = { viewModel.setView(view) },
                    label = { Text(view.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { viewModel.previous() }) { Text("Previous") }
            Text(
                text = when (state.view) {
                    CalendarView.MONTH ->
                        "${state.anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${state.anchor.year}"
                    CalendarView.WEEK -> "Week of ${state.anchor}"
                    CalendarView.DAY -> state.anchor.toString()
                },
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { viewModel.next() }) { Text("Next") }
        }

        if (state.view == CalendarView.MONTH) {
            MonthGrid(
                anchor = state.anchor,
                selected = state.selected,
                today = today,
                markers = state.markers,
                firstDayOfWeek = state.firstDayOfWeek,
                onSelect = { viewModel.select(it) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tasks on ${state.selected}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (state.selectedTasks.isEmpty()) {
                item {
                    Text(
                        "Nothing on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.selectedTasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    today = today,
                    use24HourClock = use24HourClock,
                    onClick = { onOpenTask(task.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * A six-week grid built from plain rows rather than a lazy grid: it is always 42 cells, so the
 * bookkeeping a lazy container brings would cost more than it saves.
 */
@Composable
private fun MonthGrid(
    anchor: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    markers: Map<LocalDate, DayMarker>,
    firstDayOfWeek: java.time.DayOfWeek,
    onSelect: (LocalDate) -> Unit,
) {
    val firstOfMonth = anchor.withDayOfMonth(1)
    val offset = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val gridStart = firstOfMonth.minusDays(offset.toLong())

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(7) { index ->
                val day = firstDayOfWeek.plus(index.toLong())
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(6) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { dayIndex ->
                    val date = gridStart.plusDays((week * 7 + dayIndex).toLong())
                    DayCell(
                        date = date,
                        inMonth = date.month == anchor.month,
                        isToday = date == today,
                        isSelected = date == selected,
                        marker = markers[date],
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    marker: DayMarker?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .aspectRatio(0.9f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        // Up to three dots: what was done, what was missed, what is still open. More detail than that
        // does not survive being drawn at this size.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            marker?.let {
                if (it.completed > 0) Dot(StatusColors.completed)
                if (it.missed > 0) Dot(StatusColors.missed)
                val open = it.total - it.completed - it.missed - it.cancelled
                if (open > 0) Dot(StatusColors.pending)
            }
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(color))
}
