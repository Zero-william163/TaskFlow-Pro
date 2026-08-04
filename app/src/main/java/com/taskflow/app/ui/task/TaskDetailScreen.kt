package com.taskflow.app.ui.task

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.ui.AppViewModelFactory

/**
 * Task "detail" page — intentionally shows the editor form directly, per the
 * project requirement that clicking a task card shows the same page as
 * creating a task, pre-populated with the task's current data.
 *
 * This page is a thin shell that loads [taskId] and delegates the editable
 * content to [AddEditTaskSheet] (the exact same composable used for adds).
 * A top bar provides back-nav and delete controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onBack: () -> Unit
) {
    val viewModel: TaskViewModel = viewModel(factory = AppViewModelFactory)
    val task by viewModel.observeTask(taskId).collectAsState(initial = null)
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
            // Requirement #3: edit and create UI must be one and the same,
            // with every field prefilled from the stored task.
            AddEditTaskSheet(
                task = t,
                onSaved = { _, _ -> onBack() },
                onDismiss = onBack
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
