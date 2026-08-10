package com.taskflow.app.ui.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.model.Task
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.EmptyState
import com.taskflow.app.ui.components.Format
import com.taskflow.app.ui.components.SoftCard
import com.taskflow.app.ui.components.TaskCard
import com.taskflow.app.ui.theme.GradientEnd
import com.taskflow.app.ui.theme.GradientStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(
    onTaskClick: (Long) -> Unit
) {
    val viewModel: CalendarViewModel = viewModel(factory = AppViewModelFactory)
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
    ) {
        // ====== Gradient header with month title ======
        item {
            SoftCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(listOf(GradientStart, GradientEnd))
                        )
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "${state.month.year}年 ${state.month.monthValue}月",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "共 ${state.tasksByDate.values.sumOf { it.size }} 个任务",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Row {
                        IconButton(onClick = viewModel::previousMonth) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                contentDescription = "上一月",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = viewModel::nextMonth) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = "下一月",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // ====== Calendar grid card ======
        item {
            SoftCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Weekday header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DayOfWeek.values().forEach { dow ->
                            Text(
                                text = dow.getDisplayName(TextStyle.NARROW, Locale.CHINA),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    MonthGrid(
                        month = state.month,
                        selected = state.selectedDate,
                        tasksByDate = state.tasksByDate,
                        onSelect = viewModel::selectDate
                    )
                }
            }
        }

        // ====== Selected date header ======
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Format.fullDate(state.selectedDate.atStartOfDay()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                val cnt = state.tasksForSelected.size
                if (cnt > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$cnt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        val list = state.tasksForSelected
        if (list.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.home_no_tasks),
                    hint = "这一天没有任务",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        } else {
            items(list, key = { it.second.id }) { (task, _) ->
                val cat = state.categories[task.categoryId]
                TaskCard(
                    task = task,
                    categoryColor = Color(cat?.color ?: 0xFF4C6EF5.toInt()),
                    categoryName = cat?.name.orEmpty(),
                    onClick = { onTaskClick(task.id) },
                    onToggleComplete = { viewModel.toggleComplete(task) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    readOnly = true
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    tasksByDate: Map<LocalDate, List<Task>>,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val firstDay = month.atDay(1)
    val startOffset = firstDay.dayOfWeek.value - 1 // Monday-first grid (Mon=0)
    val daysInMonth = month.lengthOfMonth()

    Column {
        var dayCounter = 1 - startOffset
        for (week in 0..5) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (col in 0 until 7) {
                    val date = if (dayCounter in 1..daysInMonth) month.atDay(dayCounter) else null
                    Box(
                        modifier = Modifier.weight(1f).aspectRatio(0.95f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                isToday = date.isEqual(today),
                                isSelected = date.isEqual(selected),
                                taskCount = tasksByDate[date]?.size ?: 0,
                                onClick = { onSelect(date) }
                            )
                        }
                    }
                    dayCounter++
                }
            }
            if (dayCounter > daysInMonth) break
        }
    }
}

/**
 * Heatmap-style day cell with intensity based on task count.
 * - 0 tasks: transparent / subtle surface
 * - 1-2 tasks: light primary tint
 * - 3-4 tasks: medium primary tint
 * - 5+ tasks: strong primary tint
 * - Today: ring border
 * - Selected: filled gradient
 */
@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    taskCount: Int,
    onClick: () -> Unit
) {
    // Scale animation for selection
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = tween(200),
        label = "dayScale"
    )

    // Heatmap intensity: map task count to 4 opacity levels
    val intensity = when {
        taskCount == 0 -> 0f
        taskCount <= 2 -> 0.25f
        taskCount <= 4 -> 0.5f
        taskCount <= 6 -> 0.75f
        else -> 1f
    }

    val cellSize = 38.dp

    val cellBg = when {
        isSelected -> Brush.linearGradient(listOf(GradientStart, GradientEnd))
        taskCount == 0 -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        else -> Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = intensity * 0.7f),
                MaterialTheme.colorScheme.primary.copy(alpha = intensity)
            )
        )
    }

    val textColor = when {
        isSelected -> Color.White
        taskCount >= 3 -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
        isToday -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(cellBg)
            .then(
                if (isToday && !isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${date.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected || taskCount > 0) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            if (taskCount > 0 && !isSelected) {
                Spacer(Modifier.height(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dotCount = taskCount.coerceAtMost(3)
                    repeat(dotCount) {
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(
                                    if (taskCount >= 3) Color.White
                                    else MaterialTheme.colorScheme.tertiary
                                )
                        )
                    }
                }
            }
        }
    }
}
