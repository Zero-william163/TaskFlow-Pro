package com.taskflow.app.ui.task

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.color
import com.taskflow.app.data.model.labelRes
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.Format
import com.taskflow.app.ui.components.PriorityDot
import com.taskflow.app.ui.components.SoftCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onBack: () -> Unit
) {
    val viewModel: TaskViewModel = viewModel(factory = AppViewModelFactory)
    val task by viewModel.observeTask(taskId).collectAsState(initial = null)
    val categories by viewModel.categories.collectAsState()

    var showEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.common_edit))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete))
                    }
                }
            )
        }
    ) { padding ->
        val t = task
        if (t == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("…", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val cat = categories.firstOrNull { it.id == t.categoryId }
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            text = t.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (t.description.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = t.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                DetailRow(label = stringResource(R.string.task_priority)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriorityDot(color = t.priority.color, size = 9)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(t.priority.labelRes))
                    }
                }
                cat?.let {
                    DetailRow(label = stringResource(R.string.task_category)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PriorityDot(color = Color(it.color), size = 9)
                            Spacer(Modifier.width(6.dp))
                            Text(it.name)
                        }
                    }
                }
                t.dueDate?.let {
                    DetailRow(label = stringResource(R.string.task_due_date)) {
                        Text(Format.describeDueDate(it))
                    }
                }
                t.reminderTime?.let {
                    DetailRow(label = stringResource(R.string.task_reminder)) {
                        Text(Format.describeDueDate(it))
                    }
                }
                DetailRow(label = "创建时间") {
                    Text(Format.fullDate(t.createdAt))
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { viewModel.setCompleted(t, !t.isCompleted) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            if (t.isCompleted) stringResource(R.string.task_mark_incomplete)
                            else stringResource(R.string.task_mark_complete)
                        )
                    }
                    OutlinedButton(
                        onClick = { showEdit = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(stringResource(R.string.common_edit)) }
                }
            }
        }
    }

    if (showEdit && task != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEdit = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            AddEditTaskSheet(
                task = task,
                onSaved = { showEdit = false },
                onDismiss = { showEdit = false }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.task_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    task?.let { viewModel.deleteTask(it) { onBack() } }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp)
            )
            content()
        }
    }
}
