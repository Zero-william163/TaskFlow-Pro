package com.taskflow.app.ui.stats

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
