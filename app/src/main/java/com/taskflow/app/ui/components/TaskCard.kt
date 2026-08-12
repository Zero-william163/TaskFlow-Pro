package com.taskflow.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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

/**
 * Premium "scenic" gradient palettes used as card backgrounds. Each palette is a
 * multi-stop vertical brush evoking a sky/horizon (蓝天 / 夕阳 / 云海 / 暮色),
 * giving the card a high-end editorial look without bundling image assets.
 *
 * Picked deterministically from [Task.id] so the same task always shows the same
 * scenery; a semi-transparent black overlay is layered on top to guarantee text
 * legibility regardless of palette brightness.
 */
private val ScenicPalettes: List<List<Color>> = listOf(
    // 暮色蓝 (Dusk Blue)
    listOf(Color(0xFF1F2A44), Color(0xFF355C7D), Color(0xFF6C7A89)),
    // 暖夕 (Warm Sunset)
    listOf(Color(0xFF355C7D), Color(0xFFC06C84), Color(0xFFF67280)),
    // 云海 (Cloud Sea)
    listOf(Color(0xFF2C3E50), Color(0xFF4A6572), Color(0xFF7B8FA1)),
    // 深紫 (Deep Violet)
    listOf(Color(0xFF2C1A47), Color(0xFF5B2C83), Color(0xFF8E44AD)),
    // 森林 (Forest)
    listOf(Color(0xFF0F2027), Color(0xFF1B4332), Color(0xFF2D6A4F)),
    // 海洋 (Ocean)
    listOf(Color(0xFF1A2980), Color(0xFF274472), Color(0xFF41729F)),
    // 黎明 (Dawn)
    listOf(Color(0xFF3A1C2E), Color(0xFF7A4171), Color(0xFFC56183)),
    // 夜空 (Night Sky)
    listOf(Color(0xFF0B0F19), Color(0xFF1B2333), Color(0xFF2E3A59))
)

private fun pickPalette(seed: Long): List<Color> =
    ScenicPalettes[(seed.hashCode().toLong() and Long.MAX_VALUE).toInt() % ScenicPalettes.size]

/**
 * High-end task card with a scenic gradient background + dark overlay.
 *
 * Layout (spec §2 任务卡片视觉全美化):
 *  - Left column:  title (strikethrough when completed) + focusDurationMinutes
 *  - Right column: [开始] pill (top) + 今日已专注 X 次 (bottom)
 *  - Top-right:    grey edit icon (Icons.Outlined.Edit)
 *
 * Touch isolation (spec §2 交互隔离):
 *  - Edit icon (onEditClick) → opens the edit task sheet (caller plays sound)
 *  - Left circle checkbox (onToggleComplete) → toggles completion only
 *  - Card body (onClick) → navigates to PomodoroScreen with taskId (caller plays sound)
 */
@Composable
fun TaskCard(
    task: Task,
    categoryColor: Color,
    categoryName: String,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onEditClick: (() -> Unit)? = null,
    todayFocusCount: Int = 0
) {
    val completedAlpha by animateFloatAsState(
        targetValue = if (task.isCompleted || task.isCompletedToday) 0.6f else 1f,
        animationSpec = tween(280),
        label = "completedAlpha"
    )
    val accent: Color = when (task.priority) {
        Priority.HIGH -> PriorityHigh
        Priority.MEDIUM -> PriorityMedium
        Priority.LOW -> PriorityLow
        Priority.NONE -> PriorityNone
    }

    val showCompletedTodayBadge = task.isCompletedToday
    val checkboxChecked = task.isCompleted || task.isCompletedToday
    val isOverdue = task.isOverdue

    // Effective focus duration: per-task value, falling back to the 25-min app default.
    val focusMinutes = if (task.focusDurationMinutes > 0) task.focusDurationMinutes else 25

    val palette = remember(task.id) { pickPalette(task.id) }
    val scenicBrush = remember(palette) {
        Brush.verticalGradient(
            colors = palette,
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
        )
    }
    // Semi-transparent black gradient (top faint → bottom strong) so white text
    // stays legible over any palette (spec: 叠加半透明黑色渐变).
    val overlayBrush = Brush.verticalGradient(
        colors = listOf(
            Color.Black.copy(alpha = 0.18f),
            Color.Black.copy(alpha = 0.32f),
            Color.Black.copy(alpha = 0.55f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clip(RoundedCornerShape(18.dp))
            .background(scenicBrush)
            .then(if (!readOnly) Modifier.clickable { onClick() } else Modifier)
    ) {
        // Dark legibility overlay
        Box(Modifier.matchParentSize().background(overlayBrush))

        // Priority accent strip on the leading edge
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .height(72.dp)
                .background(
                    if (isOverdue) PriorityHigh.copy(alpha = 0.85f)
                    else accent.copy(alpha = 0.9f)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // ===== Left column: checkbox + title (top), focus duration (bottom) =====
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!readOnly) {
                        TaskCheckbox(
                            checked = checkboxChecked,
                            onCheckedChange = { onToggleComplete() }
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough
                        else TextDecoration.None,
                        modifier = Modifier.alpha(completedAlpha)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$focusMinutes 分钟",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium
                    )
                    if (categoryName.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        // Inline tag chip adapted to the dark scenic background
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.18f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                PriorityDot(color = categoryColor, size = 6)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = categoryName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    if (showCompletedTodayBadge) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF4CAF50).copy(alpha = 0.85f)
                        ) {
                            Text(
                                text = "今日已完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (isOverdue) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFFFF4D4F).copy(alpha = 0.9f)
                        ) {
                            Text(
                                text = "已逾期",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ===== Right column: [开始 pill + ✏️编辑图标] (top), 今日已专注 X 次 (bottom) =====
            // Fix: 编辑图标与开始按钮水平并排，12dp 间距，绝不重叠。
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.height(72.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ▶ 开始 pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.22f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "开始",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // ✏️ 编辑图标 — 与开始按钮水平并排，独立点击区域
                    if (!readOnly && onEditClick != null) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "编辑任务",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "今日已专注 $todayFocusCount 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        } // end Row
    } // end Box
}
