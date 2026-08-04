package com.taskflow.app.ui.stats

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
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

        // Hero completion card
        SoftCard(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(GradientStart, GradientEnd)))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        stringResource(R.string.stats_completion_rate),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.completionRate}%",
                        color = Color.White,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(state.completionRate / 100f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatMini(stringResource(R.string.stats_total), state.total, Modifier.weight(1f))
            StatMini(stringResource(R.string.stats_completed), state.completed, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatMini(stringResource(R.string.stats_pending), state.pending, Modifier.weight(1f))
            StatMini(stringResource(R.string.stats_today), state.completedToday, Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        // ====== Line chart: monthly completion trend (spec §2) ======
        TrendCard(
            title = state.trendTitle,
            points = state.monthlyTrend,
            onPrevMonth = { viewModel.selectPreviousMonth() },
            onNextMonth = { viewModel.selectNextMonth() }
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
    }
}

@Composable
private fun StatMini(label: String, value: Int, modifier: Modifier = Modifier) {
    SoftCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarRow(label: String, value: Int, max: Int, color: Color) {
    val ratio = (value.toFloat() / max).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityDot(color = color, size = 8)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            Text("$value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

/**
 * Card containing the monthly trend line chart with prev/next month buttons.
 * Spec §2: title auto-generated "YYYY年MM月任务完成趋势", data from Room,
 * real-time via Flow, vertical axis = count, horizontal axis = date.
 */
@Composable
private fun TrendCard(
    title: String,
    points: List<DailyPoint>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Title row with month navigation.
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
                IconButton(onClick = onPrevMonth) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一月")
                }
                IconButton(onClick = onNextMonth) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "下一月")
                }
            }
            Spacer(Modifier.height(8.dp))
            CompletionLineChart(points = points)
        }
    }
}

/**
 * Self-drawn line chart. No external chart library — uses Canvas so the APK
 * stays small and the chart style matches Material Design 3.
 *
 * Layout: Y-axis labels on the left, X-axis labels (dates) on the bottom,
 * the polyline + filled gradient area in the plot region. Empty data shows
 * a friendly placeholder instead of a broken/empty axis.
 */
@Composable
private fun CompletionLineChart(points: List<DailyPoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val todayColor = MaterialTheme.colorScheme.tertiary

    val today = remember { LocalDate.now() }
    val maxCount = remember(points) {
        (points.maxOfOrNull { it.count } ?: 0).coerceAtLeast(5)
    }
    // Y-axis tick steps: 0, 5, 10, 15, ... up to maxCount (rounded up).
    val yTicks = remember(maxCount) {
        val step = when {
            maxCount <= 5 -> 1
            maxCount <= 20 -> 5
            else -> 10
        }
        (0..maxCount step step).toList()
    }

    if (points.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
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
            .height(200.dp)
    ) {
        val leftPad = 36f
        val rightPad = 12f
        val topPad = 12f
        val bottomPad = 28f
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        // ====== Y-axis grid lines + labels ======
        val yLabelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9f.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        yTicks.forEach { tick ->
            val y = if (maxCount == 0) plotBottom
            else plotBottom - (plotH * tick / maxCount)
            drawLine(
                color = axisColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                tick.toString(),
                plotLeft - 6f,
                y + 3f,
                yLabelPaint
            )
        }

        // ====== X-axis labels (dates) — sample to avoid crowding ======
        val xLabelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9f.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val n = points.size
        // Show ~6 labels evenly spaced.
        val labelStep = (n / 6).coerceAtLeast(1)
        points.forEachIndexed { i, pt ->
            if (i % labelStep == 0 || i == n - 1) {
                val x = if (n == 1) plotLeft + plotW / 2
                else plotLeft + plotW * i / (n - 1)
                val label = "${pt.date.monthValue}/${pt.date.dayOfMonth}"
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    plotBottom + 14f,
                    xLabelPaint
                )
            }
        }

        // ====== Polyline + filled area ======
        if (n >= 2) {
            val coords = points.mapIndexed { i, pt ->
                val x = plotLeft + plotW * i / (n - 1)
                val y = if (maxCount == 0) plotBottom
                else plotBottom - (plotH * pt.count / maxCount)
                Offset(x, y)
            }
            // Filled area under the line.
            val areaPath = Path().apply {
                moveTo(coords.first().x, plotBottom)
                coords.forEach { lineTo(it.x, it.y) }
                lineTo(coords.last().x, plotBottom)
                close()
            }
            drawPath(path = areaPath, color = fillColor)
            // The line itself.
            val linePath = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                coords.forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f)
            )
            // Dots on each data point.
            coords.forEach { c ->
                drawCircle(color = lineColor, radius = 3f, center = c)
            }
            // Highlight today.
            val todayIndex = points.indexOfFirst { it.date == today }
            if (todayIndex >= 0) {
                val c = coords[todayIndex]
                drawCircle(color = todayColor, radius = 5f, center = c)
                drawCircle(color = lineColor, radius = 2f, center = c)
            }
        } else if (n == 1) {
            // Single day — just draw a dot.
            val c = Offset(plotLeft + plotW / 2, plotBottom - plotH * points[0].count / maxCount.coerceAtLeast(1))
            drawCircle(color = lineColor, radius = 5f, center = c)
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
