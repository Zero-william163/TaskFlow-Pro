package com.taskflow.app.ui.task

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.color
import com.taskflow.app.data.model.labelRes
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.Format
import com.taskflow.app.ui.components.PriorityDot
import com.taskflow.app.ui.components.SectionTitle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskSheet(
    task: Task?,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: TaskViewModel = viewModel(factory = AppViewModelFactory)
    val categories by viewModel.categories.collectAsState()

    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var description by remember { mutableStateOf(task?.description.orEmpty()) }
    var priority by remember { mutableStateOf(task?.priority ?: Priority.NONE) }
    var categoryId by remember { mutableStateOf(task?.categoryId ?: 1L) }
    var dueDate by remember { mutableStateOf(task?.dueDate?.toLocalDate()) }
    var dueTime by remember { mutableStateOf(task?.dueDate?.toLocalTime()) }
    var reminderEnabled by remember { mutableStateOf(task?.reminderTime != null) }
    var reminderTime by remember {
        mutableStateOf(task?.reminderTime?.toLocalTime() ?: LocalTime.of(9, 0))
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showDueTimePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(categories) {
        if (categoryId == 0L && categories.isNotEmpty()) categoryId = categories.first().id
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(if (task == null) R.string.task_add else R.string.task_edit),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.task_title)) },
            placeholder = { Text(stringResource(R.string.task_title_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.task_description)) },
            placeholder = { Text(stringResource(R.string.task_description_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            shape = RoundedCornerShape(14.dp)
        )

        SectionTitle(stringResource(R.string.task_priority))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { p ->
                FilterChip(
                    selected = priority == p,
                    onClick = { priority = p },
                    label = { Text(stringResource(p.labelRes)) },
                    leadingIcon = { PriorityDot(color = p.color, size = 8) }
                )
            }
        }

        SectionTitle(stringResource(R.string.task_category))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { cat ->
                FilterChip(
                    selected = categoryId == cat.id,
                    onClick = { categoryId = cat.id },
                    label = { Text(cat.name) },
                    leadingIcon = { PriorityDot(color = Color(cat.color), size = 8) }
                )
            }
        }

        SectionTitle(stringResource(R.string.task_due_date))
        SelectorRow(
            icon = Icons.Outlined.Event,
            text = dueDate?.let { Format.date(it.atStartOfDay()) } ?: "选择截止日期",
            onClick = { showDatePicker = true }
        )
        if (dueDate != null) {
            SelectorRow(
                icon = Icons.Outlined.AccessTime,
                text = dueTime?.let { Format.time(it.atDate(dueDate!!)) }
                    ?: "全天",
                onClick = { showDueTimePicker = true }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.task_reminder), modifier = Modifier.weight(1f))
            Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
        }
        if (reminderEnabled) {
            SelectorRow(
                icon = Icons.Outlined.AccessTime,
                text = "${reminderTime.hour.toString().padStart(2, '0')}:${reminderTime.minute.toString().padStart(2, '0')}",
                onClick = { showReminderTimePicker = true }
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.task_cancel)) }
            Button(
                onClick = {
                    val due = dueDate?.atTime(dueTime ?: LocalTime.of(23, 59))
                    val dueLocal = dueDate
                    val reminder = if (reminderEnabled && dueLocal != null)
                        dueLocal.atTime(reminderTime) else null
                    val built = (task ?: Task(title = title)).copy(
                        title = title.trim().ifEmpty { "未命名任务" },
                        description = description.trim(),
                        categoryId = categoryId,
                        priority = priority,
                        dueDate = due,
                        reminderTime = reminder
                    )
                    viewModel.saveTask(built) { onSaved() }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.task_save)) }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dueDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    dueDate = Instant.ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        ) { DatePicker(state = state) }
    }
    if (showDueTimePicker) {
        TimePickerDialog(
            initial = dueTime ?: LocalTime.of(9, 0),
            onConfirm = { dueTime = it; showDueTimePicker = false },
            onDismiss = { showDueTimePicker = false }
        )
    }
    if (showReminderTimePicker) {
        TimePickerDialog(
            initial = reminderTime,
            onConfirm = { reminderTime = it; showReminderTimePicker = false },
            onDismiss = { showReminderTimePicker = false }
        )
    }
}

@Composable
private fun SelectorRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = { content() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = { TimePicker(state = state) }
    )
}
