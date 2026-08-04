package com.taskflow.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.ServiceLocator
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.EmptyState
import com.taskflow.app.ui.components.Format
import com.taskflow.app.ui.components.TaskCard
import com.taskflow.app.ui.theme.GradientEnd
import com.taskflow.app.ui.theme.GradientStart
import com.taskflow.app.ui.task.AddEditTaskSheet
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onTaskClick: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bottom sheet for add/edit.
    var showAddSheet by remember { mutableStateOf(false) }

    // Widget flow state — only resolved AFTER a task is actually saved.
    var pendingTaskId by remember { mutableStateOf<Long?>(null) }
    var showFirstGuide by remember { mutableStateOf(false) }
    var showAddToWidgetPrompt by remember { mutableStateOf(false) }

    val widgetAdded by ServiceLocator.userPreferences.widgetAdded.collectAsState(initial = false)
    val guideShown by ServiceLocator.userPreferences.widgetGuideShown.collectAsState(initial = false)

    /**
     * Runs only after a NEW task is persisted. Decides whether to show the widget
     * guide (first ever task) or the "add to widget?" prompt (subsequent tasks),
     * keyed off the user's widget state. Declared before the add-sheet block so it
     * is in scope for the onSaved callback.
     */
    fun onNewTaskSaved(id: Long) {
        pendingTaskId = id
        when {
            // First time ever — guide the user to add the widget itself.
            !widgetAdded && !guideShown -> showFirstGuide = true
            // Widget already placed — ask whether to surface this new task on it.
            widgetAdded -> showAddToWidgetPrompt = true
            // User previously declined the guide; stay quiet.
            else -> pendingTaskId = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // Requirement: first tap on "添加任务" only opens the create page.
                // No widget logic is triggered here — the widget prompt is decided
                // after the task is actually persisted.
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.task_add), fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item { HomeHeader(remaining = state.remaining) }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChipRow(state.filter, viewModel::setFilter)
                }
            }

            val list = state.filtered
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_no_tasks),
                        hint = stringResource(R.string.home_no_tasks_hint)
                    )
                }
            } else {
                items(list, key = { it.id }) { task ->
                    val cat = state.categories[task.categoryId]
                    TaskCard(
                        task = task,
                        categoryColor = Color(cat?.color ?: 0xFF4C6EF5.toInt()),
                        categoryName = cat?.name.orEmpty(),
                        onClick = { onTaskClick(task.id) },
                        onToggleComplete = { viewModel.toggleComplete(task) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            AddEditTaskSheet(
                task = null,
                onSaved = { id, isNew ->
                    showAddSheet = false
                    if (isNew) onNewTaskSaved(id)
                },
                onDismiss = { showAddSheet = false }
            )
        }
    }

    if (showFirstGuide) {
        FirstWidgetGuideDialog(
            onAddNow = {
                val id = pendingTaskId
                if (id != null) {
                    viewModel.pinToWidget(id)
                    WidgetHelper.requestPinWidget(context)
                }
                scope.launch { ServiceLocator.userPreferences.setWidgetGuideShown(true) }
                pendingTaskId = null
                showFirstGuide = false
            },
            onLater = {
                scope.launch { ServiceLocator.userPreferences.setWidgetGuideShown(true) }
                pendingTaskId = null
                showFirstGuide = false
            }
        )
    }

    if (showAddToWidgetPrompt) {
        AddToWidgetPromptDialog(
            onAdd = {
                pendingTaskId?.let { viewModel.pinToWidget(it) }
                WidgetHelper.refresh(context)
                pendingTaskId = null
                showAddToWidgetPrompt = false
            },
            onSkip = {
                pendingTaskId = null
                showAddToWidgetPrompt = false
            }
        )
    }
}

@Composable
private fun HomeHeader(remaining: Int) {
    val now = remember { LocalDateTime.now() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientEnd)))
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 28.dp)
    ) {
        Column {
            Text(
                text = Format.greetingPrefix(now.hour),
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Format.fullDate(now),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (remaining > 0) stringResource(R.string.home_remaining_tasks, remaining)
                else stringResource(R.string.home_all_done),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FilterChipRow(current: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    val options = listOf(
        HomeFilter.ALL to R.string.home_filter_all,
        HomeFilter.TODAY to R.string.home_filter_today,
        HomeFilter.UPCOMING to R.string.home_filter_upcoming,
        HomeFilter.COMPLETED to R.string.home_filter_completed
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (filter, label) ->
            FilterChip(
                selected = current == filter,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(label)) }
            )
        }
    }
}

/** Shown once, after the first task is saved, to add the widget itself. */
@Composable
private fun FirstWidgetGuideDialog(onAddNow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.widget_guide_title)) },
        text = {
            Column {
                Text(stringResource(R.string.widget_guide_message))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.widget_guide_manual_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.widget_guide_manual_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAddNow) {
                Text(stringResource(R.string.widget_guide_add_now), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.widget_guide_later)) }
        }
    )
}

/** Shown for every subsequent new task, asking whether to surface it on the widget. */
@Composable
private fun AddToWidgetPromptDialog(onAdd: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.widget_add_to_widget_title)) },
        text = { Text(stringResource(R.string.widget_add_to_widget_message)) },
        confirmButton = {
            TextButton(onClick = onAdd) {
                Text(stringResource(R.string.widget_add_to_widget_yes), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.widget_add_to_widget_no)) }
        }
    )
}
