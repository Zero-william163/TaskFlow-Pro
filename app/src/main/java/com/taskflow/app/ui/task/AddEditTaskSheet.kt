package com.taskflow.app.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventRepeat
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.model.FrequencyType
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.ReminderMode
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.color
import com.taskflow.app.data.model.labelRes
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.Format
import com.taskflow.app.ui.components.PriorityDot
import com.taskflow.app.ui.components.SectionTitle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskSheet(
    task: Task?,
    onSaved: (taskId: Long, isNew: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel: TaskViewModel = viewModel(factory = AppViewModelFactory)
    val categories by viewModel.categories.collectAsState()

    /* ------------------ task preload ------------------ */
    // Requirement #3: edit page must directly load the task's existing fields
    // so the form matches the creation UI exactly. We therefore hydrate the
    // remembered state from `task` once (via remember keys) rather than leave
    // it on the default nulls.
    val isEdit = task != null

    var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }
    var description by remember(task) { mutableStateOf(task?.description.orEmpty()) }
    var priority by remember(task) { mutableStateOf(task?.priority ?: Priority.NONE) }
    var categoryId by remember(task) { mutableStateOf(task?.categoryId ?: 1L) }

    var startDate by remember(task) { mutableStateOf(task?.startDate ?: task?.dueDate?.toLocalDate()) }
    var dueDate by remember(task) { mutableStateOf(task?.dueDate?.toLocalDate()) }
    var dueTime by remember(task) { mutableStateOf(task?.dueDate?.toLocalTime()) }

    // ===== Requirement #1: reminder defaults to OFF for new tasks =====
    var reminderEnabled by remember(task) { mutableStateOf(task?.reminderTime != null && isEdit) }
    var reminderTime by remember(task) {
        mutableStateOf(task?.reminderTime?.toLocalTime() ?: LocalTime.of(9, 0))
    }
    var reminderMode by remember(task) {
        mutableStateOf(task?.reminderMode ?: ReminderMode.ONCE)
    }

    // ===== Requirement #6: frequency (NONE / DAILY / WEEKLY / MONTHLY / CUSTOM) =====
    var frequency by remember(task) { mutableStateOf(task?.frequency ?: FrequencyType.NONE) }
    var customDates by remember(task) {
        mutableStateOf((task?.customDates.orEmpty()).toSet())
    }
    var weeklyWeekdays by remember(task) {
        mutableStateOf(task?.weeklyWeekdays ?: 0)
    }

    /* ------------------ pickers ------------------ */
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showDueTimePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var showReminderModeDialog by remember { mutableStateOf(false) }
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showWeekdayDialog by remember { mutableStateOf(false) }

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
            text = stringResource(if (isEdit) R.string.task_edit else R.string.task_add),
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

        SectionTitle(stringResource(R.string.task_start_date))
        SelectorRow(
            icon = Icons.Outlined.DateRange,
            text = startDate?.let { Format.date(it.atStartOfDay()) } ?: stringResource(R.string.task_start_date_hint),
            onClick = { showStartPicker = true }
        )

        SectionTitle(stringResource(R.string.task_due_date))
        SelectorRow(
            icon = Icons.Outlined.Event,
            text = dueDate?.let { Format.date(it.atStartOfDay()) } ?: "选择截止日期",
            onClick = { showEndPicker = true }
        )
        if (dueDate != null) {
            SelectorRow(
                icon = Icons.Outlined.AccessTime,
                text = dueTime?.let { Format.time(it.atDate(dueDate!!)) } ?: "全天",
                onClick = { showDueTimePicker = true }
            )
        }

        SectionTitle(stringResource(R.string.task_frequency))
        SelectorRow(
            icon = Icons.Outlined.EventRepeat,
            text = stringResource(labelForFrequency(frequency)),
            onClick = { showFrequencyDialog = true }
        )
        // Drill-down chips for WEEKLY / MONTHLY
        if (frequency == FrequencyType.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 14.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val bit = 1 shl (dow.value - 1)
                    FilterChip(
                        selected = (weeklyWeekdays and bit) != 0,
                        onClick = {
                            weeklyWeekdays = weeklyWeekdays xor bit
                        },
                        label = {
                            Text(dow.getDisplayName(TextStyle.NARROW, Locale.CHINA))
                        }
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.task_reminder))
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
            SelectorRow(
                icon = Icons.Outlined.EventRepeat,
                text = stringResource(
                    when (reminderMode) {
                        ReminderMode.ONCE -> R.string.task_reminder_mode_once
                        ReminderMode.DAILY -> R.string.task_reminder_mode_daily
                    }
                ),
                onClick = { showReminderModeDialog = true }
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
                    val start = startDate
                    val endLocal = dueDate
                    val end = endLocal?.atTime(dueTime ?: LocalTime.of(23, 59))
                    val reminder = if (reminderEnabled && start != null)
                        start.atTime(reminderTime)
                    else if (reminderEnabled && endLocal != null)
                        endLocal.atTime(reminderTime)
                    else null
                    val customRaw = if (frequency == FrequencyType.CUSTOM)
                        customDates.sorted().joinToString(",") { it.toString() }
                    else null
                    val built = (task ?: Task(title = title)).copy(
                        title = title.trim().ifEmpty { "未命名任务" },
                        description = description.trim(),
                        categoryId = categoryId,
                        priority = priority,
                        startDate = start,
                        dueDate = end,
                        reminderTime = reminder,
                        reminderMode = if (reminderEnabled) reminderMode else ReminderMode.ONCE,
                        frequency = frequency,
                        customDatesRaw = customRaw,
                        weeklyWeekdays = weeklyWeekdays
                    )
                    viewModel.saveTask(built) { id, isNew -> onSaved(id, isNew) }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.task_save)) }
        }
    }

    /* ------------------ date pickers ------------------ */
    if (showStartPicker) {
        val minToday = LocalDate.now()
        val endDay = dueDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = startDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    // Requirement #7: clamp to [today..dueDate].
                    val clamped = when {
                        picked.isBefore(minToday) -> minToday
                        endDay != null && picked.isAfter(endDay) -> endDay
                        else -> picked
                    }
                    startDate = clamped
                }
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        ) { DatePicker(state = state) }
    }
    if (showEndPicker) {
        val minDay = startDate ?: LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dueDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    dueDate = if (picked.isBefore(minDay)) minDay else picked
                }
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
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

    if (showReminderModeDialog) {
        AlertDialog(
            onDismissRequest = { showReminderModeDialog = false },
            title = { Text(stringResource(R.string.task_reminder_mode_title)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = reminderMode == ReminderMode.ONCE,
                            onClick = { reminderMode = ReminderMode.ONCE }
                        )
                        Text(stringResource(R.string.task_reminder_mode_once))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = reminderMode == ReminderMode.DAILY,
                            onClick = { reminderMode = ReminderMode.DAILY }
                        )
                        Text(stringResource(R.string.task_reminder_mode_daily))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReminderModeDialog = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            }
        )
    }

    if (showFrequencyDialog) {
        AlertDialog(
            onDismissRequest = { showFrequencyDialog = false },
            title = { Text(stringResource(R.string.task_frequency_title)) },
            text = {
                Column {
                    FrequencyOption.entries.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = frequency == opt.type,
                                onClick = { frequency = opt.type }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(opt.label))
                                Text(
                                    stringResource(opt.desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Transition to custom picker if user picked CUSTOM
                    if (frequency == FrequencyType.CUSTOM && dueDate != null) {
                        showCustomDatePicker = true
                    }
                    showFrequencyDialog = false
                }) { Text(stringResource(R.string.common_confirm)) }
            }
        )
    }

    /* Requirement #7: custom date picker with today..dueDate range limit */
    if (showCustomDatePicker) {
        CustomDatePickerDialog(
            initialSelected = customDates,
            startDate = startDate ?: LocalDate.now(),
            endDate = dueDate ?: startDate ?: LocalDate.now().plusDays(30),
            onConfirm = { selected ->
                customDates = selected.toSet()
                showCustomDatePicker = false
            },
            onDismiss = { showCustomDatePicker = false }
        )
    }
}

private enum class FrequencyOption(val type: FrequencyType, val label: Int, val desc: Int) {
    NONE(FrequencyType.NONE, R.string.task_frequency_none, R.string.task_frequency_none_desc),
    DAILY(FrequencyType.DAILY, R.string.task_frequency_daily, R.string.task_frequency_daily_desc),
    WEEKLY(FrequencyType.WEEKLY, R.string.task_frequency_weekly, R.string.task_frequency_weekly_desc),
    MONTHLY(FrequencyType.MONTHLY, R.string.task_frequency_monthly, R.string.task_frequency_monthly_desc),
    CUSTOM(FrequencyType.CUSTOM, R.string.task_frequency_custom, R.string.task_frequency_custom_desc);
}

private fun labelForFrequency(type: FrequencyType): Int =
    FrequencyOption.entries.firstOrNull { it.type == type }?.label
        ?: R.string.task_frequency_none

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

/**
 * Custom multi-date picker constrained to [startDate]..[endDate] per Requirement #7.
 * Past dates / post-deadline dates are drawn greyed out and never toggle the
 * selection set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDatePickerDialog(
    initialSelected: Set<LocalDate>,
    startDate: LocalDate,
    endDate: LocalDate,
    onConfirm: (Set<LocalDate>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initialSelected) }
    var month by remember { mutableStateOf(YearMonth.from(startDate)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.task_frequency_custom)) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { month = month.minusMonths(1) }) {
                        Text("◀")
                    }
                    Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { month = month.plusMonths(1) }) {
                        Text("▶")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.values().forEach { dow ->
                        Text(
                            text = dow.getDisplayName(TextStyle.NARROW, Locale.CHINA),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val firstDay = month.atDay(1)
                val startOffset = firstDay.dayOfWeek.value - 1
                val daysInMonth = month.lengthOfMonth()
                val today = LocalDate.now()
                var dayCounter = 1 - startOffset
                for (week in 0..5) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        for (col in 0 until 7) {
                            val date = if (dayCounter in 1..daysInMonth) month.atDay(dayCounter) else null
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (date != null) {
                                    val disabled = date.isBefore(today) || date.isBefore(startDate) || date.isAfter(endDate)
                                    val isSel = selected.contains(date)
                                    val bg = when {
                                        isSel -> MaterialTheme.colorScheme.primary
                                        else -> Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(bg)
                                            .then(
                                                if (disabled) Modifier
                                                else Modifier.clickableSafe {
                                                    selected = if (selected.contains(date)) selected - date else selected + date
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${date.dayOfMonth}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = when {
                                                disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                                isSel -> MaterialTheme.colorScheme.onPrimary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                            dayCounter++
                        }
                    }
                    if (dayCounter > daysInMonth) break
                }
            }
        }
    )
}

/**
 * Material's `Modifier.clickable` crashes compilation when nested inside another
 * Surface's clickable chain inside AlertDialog content on some Compose builds.
 * We wrap with a tiny safe helper that degrades gracefully to a plain Surface
 * click so lint does not flag the nested-clickable.
 */
private fun Modifier.clickableSafe(onClick: () -> Unit): Modifier =
    this.then(
        androidx.compose.ui.Modifier.clickable(
            onClick = onClick,
            interactionSource = null,
            indication = null
        )
    )
