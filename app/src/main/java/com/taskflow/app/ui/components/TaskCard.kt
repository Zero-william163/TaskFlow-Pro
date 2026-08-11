package com.taskflow.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.Task
import com.taskflow.app.ui.theme.PriorityHigh
import com.taskflow.app.ui.theme.PriorityLow
import com.taskflow.app.ui.theme.PriorityMedium
import com.taskflow.app.ui.theme.PriorityNone

@Composable
fun TaskCard(
    task: Task,
    categoryColor: Color,
    categoryName: String,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    val completedAlpha by animateFloatAsState(
        targetValue = if (task.isCompleted || task.isCompletedToday) 0.55f else 1f,
        animationSpec = tween(260),
        label = "completedAlpha"
    )
    val accent: Color = when (task.priority) {
        Priority.HIGH -> PriorityHigh
        Priority.MEDIUM -> PriorityMedium
        Priority.LOW -> PriorityLow
        Priority.NONE -> PriorityNone
    }

    // Recurring tasks checked off today show a "今日已完成" badge and a checked
    // checkbox, but the task itself is NOT isCompleted — it stays alive.
    val showCompletedTodayBadge = task.isCompletedToday
    val checkboxChecked = task.isCompleted || task.isCompletedToday

    // Overdue tasks (dueDate < today, not completed) get a muted grey card body
    // + a striking red "已逾期" capsule badge next to the due time. This lowers
    // the visual weight of stale tasks while making the overdue state unmistakable.
    val isOverdue = task.isOverdue

    SoftCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .then(if (isOverdue) Modifier.background(Color(0xFFF2F2F4)) else Modifier),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isOverdue) Color(0xFFB0B3BC) else accent)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverdue) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.alpha(completedAlpha)
                )
                if (task.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(completedAlpha)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (categoryName.isNotBlank()) {
                        TagChip(text = categoryName, color = categoryColor)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (showCompletedTodayBadge) {
                        // Green capsule badge for recurring tasks checked off today
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "今日已完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (isOverdue) {
                        // Striking red capsule badge for overdue tasks.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFFF4D4F).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "已逾期",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF4D4F),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    task.dueDate?.let { due ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = if (isOverdue) PriorityHigh
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = Format.describeDueShort(due),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isOverdue) PriorityHigh
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (!readOnly) {
                TaskCheckbox(
                    checked = checkboxChecked,
                    onCheckedChange = { onToggleComplete() }
                )
            }
        }
    }
}
