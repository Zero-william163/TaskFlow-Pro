package com.taskflow.app.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.local.DailyFocusMinutes
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.labelRes
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.PriorityDot
import com.taskflow.app.ui.components.SectionTitle
import com.taskflow.app.ui.components.SoftCard
import com.taskflow.app.ui.theme.GradientEnd
import com.taskflow.app.ui.theme.GradientStart
import com.taskflow.app.ui.theme.PriorityHigh
import com.taskflow.app.ui.theme.PriorityLow
import com.taskflow.app.ui.theme.PriorityMedium
import com.taskflow.app.ui.theme.PriorityNone
import java.time.LocalDate

@Composable
fun StatsScreen() {
    val viewModel: StatsViewModel = viewModel(factory = AppViewModelFactory)
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        // ====== Hero: circular ring progress + gradient card ======
        HeroCompletionCard(
            completionRate = state.completionRate,
            completed = state.completed,
            total = state.total
        )

        Spacer(Modifier.height(16.dp))

        // ====== Mini stat cards with icons + gradient accent ======
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatMini(
                label = stringResource(R.string.stats_total),
                value = state.total,
                icon = Icons.Rounded.ListAlt,
                accentColor = GradientStart,
                modifier = Modifier.weight(1f)
            )
            StatMini(
                label = stringResource(R.string.stats_completed),
                value = state.completed,
                icon = Icons.Rounded.CheckCircle,
                accentColor = PriorityLow,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatMini(
                label = stringResource(R.string.stats_pending),
                value = state.pending,
                icon = Icons.Rounded.Bolt,
                accentColor = PriorityMedium,
                modifier = Modifier.weight(1f)
            )
            StatMini(
                label = stringResource(R.string.stats_today),
                value = state.completedToday,
                icon = Icons.Rounded.Today,
                accentColor = GradientEnd,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // ====== Line chart: 7-day rolling completion trend ======
        TrendCard(
            title = state.trendTitle,
            points = state.trend,
            onPrevWindow = { viewModel.selectPreviousWindow() },
            onNextWindow = { viewModel.selectNextWindow() }
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.stats_by_priority))
        val priorityColors = mapOf(
            Priority.HIGH to PriorityHigh,
            Priority.MEDIUM to PriorityMedium,
            Priority.LOW to PriorityLow,
            Priority.NONE to PriorityNone
        )
        state.byPriority.forEach { pc ->
            val p = Priority.fromName(pc.priority)
            BarRow(
                label = stringResource(p.labelRes),
                value = pc.count,
                max = state.total.coerceAtLeast(1),
                color = priorityColors[p] ?: PriorityNone
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.stats_by_category))
        state.byCategory.forEach { cc ->
            val cat = state.categories[cc.categoryId]
            BarRow(
                label = cat?.name ?: "—",
                value = cc.count,
                max = state.total.coerceAtLeast(1),
                color = Color(cat?.color ?: 0xFF4C6EF5.toInt())
            )
        }

        Spacer(Modifier.height(20.dp))
        // ====== Pomodoro focus stats (spec: 同步更新至 StatisticsScreen 统计图表) ======
        SectionTitle("番茄专注")
        FocusStatsCard(
            totalMinutes = state.totalFocusMinutes,
            totalSessions = state.totalFocusSessions,
            focusByDay = state.focusByDay,
            weekStart = state.weekStart
        )
    }
}

// ====== Hero: Circular Ring Progress Card ======

@Composable
private fun HeroCompletionCard(
    completionRate: Int,
    completed: Int,
    total: Int
) {
    val animatedRate by animateFloatAsState(
        targetValue = completionRate / 100f,
        animationSpec = tween(1000),
        label = "ringProgress"
    )

    SoftCard(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(GradientStart, GradientEnd)))
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: text
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.stats_completion_rate),
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${completed} / ${total}",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "$completionRate%",
                        color = Color.White,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Right: circular ring
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(100.dp)) {
                        val strokeW = 10.dp.toPx()
                        val diameter = size.minDimension - strokeW
                        val topLeft = Offset(
                            (size.width - diameter) / 2f,
                            (size.height - diameter) / 2f
                        )
                        val arcSize = Size(diameter, diameter)
                        // Background ring
                        drawArc(
                            color = Color.White.copy(alpha = 0.2f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                        // Progress ring
                        drawArc(
                            color = Color.White,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedRate,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        "${(animatedRate * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ====== Mini Stat Card with Icon + Gradient Accent Bar ======

@Composable
private fun StatMini(
    label: String,
    value: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    SoftCard(modifier) {
        Column(Modifier.fillMaxWidth()) {
            // Top gradient accent bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accentColor, accentColor.copy(alpha = 0.4f))
                        )
                    )
            )
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ====== Animated Horizontal Bar Row ======

@Composable
private fun BarRow(label: String, value: Int, max: Int, color: Color) {
    val ratio = (value.toFloat() / max).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(800),
        label = "barAnim"
    )
    val percent = (ratio * 100).toInt()

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityDot(color = color, size = 8)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$value",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animatedRatio)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.7f), color)
                        )
                    )
            )
        }
    }
}

// ====== Trend Card ======

@Composable
private fun TrendCard(
    title: String,
    points: List<DailyPoint>,
    onPrevWindow: () -> Unit,
    onNextWindow: () -> Unit
) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onPrevWindow) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周")
                    }
                    IconButton(onClick = onNextWindow) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            CompletionLineChart(points = points)
        }
    }
}

// ====== Smooth Bezier Line Chart ======

@Composable
private fun CompletionLineChart(points: List<DailyPoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val todayColor = MaterialTheme.colorScheme.tertiary
    val today = remember { LocalDate.now() }

    val dataMax = remember(points) { points.maxOfOrNull { it.count } ?: 0 }
    val (maxCount, yStep) = remember(dataMax) { computeYAxisMax(dataMax) }
    val yTicks = remember(maxCount, yStep) {
        (0..maxCount step yStep).toList()
    }

    if (points.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无数据",
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor
            )
        }
        return
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val leftPad = 38f
        val rightPad = 14f
        val topPad = 16f
        val bottomPad = 32f
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        // ====== Y-axis grid lines (dashed) + labels ======
        val yLabelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9f.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        yTicks.forEach { tick ->
            val y = if (maxCount == 0) plotBottom
            else plotBottom - (plotH * tick / maxCount)
            // Dashed grid
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
            drawContext.canvas.nativeCanvas.drawText(
                tick.toString(),
                plotLeft - 8f,
                y + 3f,
                yLabelPaint
            )
        }

        // ====== X-axis labels ======
        val xLabelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9f.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val n = points.size
        points.forEachIndexed { i, pt ->
            val x = if (n == 1) plotLeft + plotW / 2
            else plotLeft + plotW * i / (n - 1)
            val label = "${pt.date.monthValue}/${pt.date.dayOfMonth}"
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                plotBottom + 16f,
                xLabelPaint
            )
        }

        // ====== Today vertical guide line ======
        val todayIndex = points.indexOfFirst { it.date == today }
        if (todayIndex >= 0 && n >= 2) {
            val todayX = plotLeft + plotW * todayIndex / (n - 1)
            drawLine(
                color = todayColor.copy(alpha = 0.3f),
                start = Offset(todayX, plotTop),
                end = Offset(todayX, plotBottom),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }

        // ====== Smooth Bezier curve + gradient fill ======
        if (n >= 2) {
            val coords = points.mapIndexed { i, pt ->
                val x = plotLeft + plotW * i / (n - 1)
                val y = if (maxCount == 0) plotBottom
                else plotBottom - (plotH * pt.count / maxCount)
                Offset(x, y)
            }

            // Build smooth Bezier path
            val linePath = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                for (i in 0 until coords.size - 1) {
                    val curr = coords[i]
                    val next = coords[i + 1]
                    val midX = (curr.x + next.x) / 2f
                    cubicTo(
                        midX, curr.y,
                        midX, next.y,
                        next.x, next.y
                    )
                }
            }

            // Filled gradient area under the curve
            val areaPath = Path().apply {
                moveTo(coords.first().x, plotBottom)
                lineTo(coords.first().x, coords.first().y)
                for (i in 0 until coords.size - 1) {
                    val curr = coords[i]
                    val next = coords[i + 1]
                    val midX = (curr.x + next.x) / 2f
                    cubicTo(
                        midX, curr.y,
                        midX, next.y,
                        next.x, next.y
                    )
                }
                lineTo(coords.last().x, plotBottom)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.3f), lineColor.copy(alpha = 0.02f))
                )
            )

            // Glow shadow under line
            drawPath(
                path = linePath,
                color = lineColor.copy(alpha = 0.15f),
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
            // Main line
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // Data point dots — outer ring + inner fill
            coords.forEachIndexed { i, c ->
                val isToday = i == todayIndex
                val r = if (isToday) 6f else 4f
                drawCircle(
                    color = if (isToday) todayColor else lineColor,
                    radius = r + 2f,
                    center = c,
                    style = Stroke(width = 2f)
                )
                drawCircle(
                    color = if (isToday) todayColor else lineColor,
                    radius = r,
                    center = c
                )
                if (isToday) {
                    drawCircle(color = Color.White, radius = 2.5f, center = c)
                }
            }
        } else if (n == 1) {
            val c = Offset(plotLeft + plotW / 2, plotBottom - plotH * points[0].count / maxCount.coerceAtLeast(1))
            drawCircle(color = lineColor, radius = 6f, center = c, style = Stroke(width = 2f))
            drawCircle(color = lineColor, radius = 4f, center = c)
        }
    }
}

/** Helper: Compose Color → Android int color for the native Canvas paint. */
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)

/**
 * Compute a "nice" Y-axis ceiling and step size from the observed max daily count.
 */
private fun computeYAxisMax(dataMax: Int): Pair<Int, Int> {
    require(dataMax >= 0) { "dataMax must be non-negative, got $dataMax" }
    return when {
        dataMax <= 5 -> 5 to 1
        dataMax <= 10 -> {
            val max = (dataMax + 1).coerceAtLeast(5)
            max to if (max <= 6) 1 else 2
        }
        dataMax <= 20 -> {
            val max = ((dataMax + 3) / 5 * 5).coerceAtLeast(10)
            max to 5
        }
        else -> {
            val rawMax = (dataMax * 1.2f).toInt().coerceAtLeast(dataMax + 1)
            val max = ((rawMax + 9) / 10 * 10)
            max to 10
        }
    }
}

// ====== Pomodoro Focus Stats Card ======
// Spec: 倒计时完成时自动写入 focus_history 表，并同步更新至 StatisticsScreen 统计图表.
// This card renders the cumulative focus minutes + a 7-day bar chart of daily
// focus minutes, fed live by the focus_history table.

@Composable
private fun FocusStatsCard(
    totalMinutes: Int,
    totalSessions: Int,
    focusByDay: List<DailyFocusMinutes>,
    weekStart: LocalDate
) {
    val accentColor = Color(0xFF15D0AB)

    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "累计专注时长",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFocusMinutes(totalMinutes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "完成轮数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$totalSessions",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "近 7 天专注分布",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            FocusBarChart(focusByDay = focusByDay, weekStart = weekStart, accentColor = accentColor)
        }
    }
}

@Composable
private fun FocusBarChart(
    focusByDay: List<DailyFocusMinutes>,
    weekStart: LocalDate,
    accentColor: Color
) {
    val byDay = focusByDay.associate { LocalDate.parse(it.day) to it.minutes }
    val window = (0 until 7).map { offset ->
        val date = weekStart.plusDays(offset.toLong())
        date to (byDay[date] ?: 0)
    }
    val maxMinutes = (window.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        window.forEach { (date, minutes) ->
            val ratio = (minutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
            val animatedRatio by animateFloatAsState(
                targetValue = ratio,
                animationSpec = tween(600),
                label = "focusBar"
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (minutes > 0) minutes.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height((animatedRatio * 80f + 2f).dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.verticalGradient(
                                listOf(accentColor.copy(alpha = 0.55f), accentColor)
                            )
                        )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFocusMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
