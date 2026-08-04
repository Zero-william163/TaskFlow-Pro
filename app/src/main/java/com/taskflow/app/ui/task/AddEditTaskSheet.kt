package com.taskflow.app.ui.task

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.MusicNote
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTaskSheet(
    task: Task?,
    onSaved: (taskId: Long, isNew: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCancel: ((hasUnsavedChanges: Boolean) -> Unit)? = null,
    externalSaveTrigger: Boolean = false,
    onExternalSaveTriggered: () -> Unit = {}
) {
    val viewModel: TaskViewModel = viewModel(factory = AppViewModelFactory)
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current

    val isEdit = task != null

    // ====== Hydrate form state from the task. remember(task) ensures clicking a
    // different task correctly resets fields (bug #6 fix: taskId changed → recompose).
    var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }
    var description by remember(task) { mutableStateOf(task?.description.orEmpty()) }
    var priority by remember(task) { mutableStateOf(task?.priority ?: Priority.NONE) }
    var categoryId by remember(task) { mutableStateOf(task?.categoryId ?: 1L) }

    var startDate by remember(task) {
        mutableStateOf(task?.startDate ?: LocalDate.now())
    }
    var dueDate by remember(task) { mutableStateOf(task?.dueDate?.toLocalDate()) }
    var dueTime by remember(task) { mutableStateOf(task?.dueDate?.toLocalTime()) }
    var dueDateError by remember { mutableStateOf(false) }

    // Default reminder OFF for new tasks (#1 fix).
    var reminderEnabled by remember(task) { mutableStateOf(task?.reminderTime != null && isEdit) }
    var reminderTime by remember(task) {
        mutableStateOf(task?.reminderTime?.toLocalTime() ?: LocalTime.of(9, 0))
    }
    var reminderMode by remember(task) {
        mutableStateOf(task?.reminderMode ?: ReminderMode.ONCE)
    }
    var alarmSoundUri by remember(task) {
        mutableStateOf(task?.alarmSoundUri.takeUnless { it.isNullOrBlank() })
    }

    var frequency by remember(task) { mutableStateOf(task?.frequency ?: FrequencyType.NONE) }
    var customDates by remember(task) {
        mutableStateOf((task?.customDates.orEmpty()).toSet())
    }
    var weeklyWeekdays by remember(task) {
        mutableStateOf(task?.weeklyWeekdays ?: 0)
    }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showDueTimePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var showReminderModeDialog by remember { mutableStateOf(false) }
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showSoundPickerDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val dueDateScrollId = "dueDateSection"

    // ====== RingtonePicker ====== (#7 + #8)
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { ar ->
        val uri = ar.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        alarmSoundUri = uri?.toString()
    }
    val openRingtonePicker: () -> Unit = {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.task_reminder_sound_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            alarmSoundUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        }
        try {
            ringtoneLauncher.launch(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开铃声选择器", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(categories) {
        if (categoryId == 0L && categories.isNotEmpty()) categoryId = categories.first().id
    }

    // ====== Frequency dialog helper ======
    // When frequency selection confirms CUSTOM, open date picker immediately.
    val openFrequencyDialog: () -> Unit = { showFrequencyDialog = true }

    // Extracted so the unsaved-changes dialog in the host screen can trigger a
    // save without going through the button (see externalSaveTrigger).
    val performSave: () -> Unit = save@{
        // ====== Due date is required (Requirement #2) ======
        if (dueDate == null) {
            dueDateError = true
            coroutineScope.launch {
                scrollState.scrollTo(scrollState.maxValue)
            }
            return@save
        }
        val start = startDate ?: LocalDate.now()
        val endLocal = dueDate!!
        val end = endLocal.atTime(dueTime ?: LocalTime.of(23, 59))
        val reminder = if (reminderEnabled) start.atTime(reminderTime) else null
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
            weeklyWeekdays = weeklyWeekdays,
            alarmSoundUri = alarmSoundUri
        )
        viewModel.saveTask(built) { id, isNew -> onSaved(id, isNew) }
    }
    LaunchedEffect(externalSaveTrigger) {
        if (externalSaveTrigger) {
            performSave()
            onExternalSaveTriggered()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
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
            text = startDate?.let { Format.date(it.atStartOfDay()) }
                ?: stringResource(R.string.task_start_date_hint),
            onClick = { showStartPicker = true }
        )

        SectionTitle(stringResource(R.string.task_due_date))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (dueDateError) 2.dp else 0.dp,
                    color = if (dueDateError) MaterialTheme.colorScheme.error else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
        ) {
            SelectorRow(
                icon = Icons.Outlined.Event,
                text = dueDate?.let { Format.date(it.atStartOfDay()) } ?: "选择截止日期",
                onClick = {
                    showEndPicker = true
                    dueDateError = false
                }
            )
        }
        if (dueDateError) {
            Text(
                text = "请填写截止日期",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp)
            )
        }
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
            text = stringResource(labelForFrequency(frequency)) + when {
                frequency == FrequencyType.CUSTOM && customDates.isNotEmpty() ->
                    "（${customDates.size}天已选）"
                frequency == FrequencyType.WEEKLY -> {
                    val selected = (0 until 7)
                        .filter { (weeklyWeekdays and (1 shl it)) != 0 }
                        .map { i -> DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.CHINA) }
                    if (selected.isEmpty()) "" else "（${selected.joinToString("/")}）"
                }
                else -> ""
            },
            onClick = openFrequencyDialog
        )
        if (frequency == FrequencyType.WEEKLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 14.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val bit = 1 shl (dow.value - 1)
                    FilterChip(
                        selected = (weeklyWeekdays and bit) != 0,
                        onClick = { weeklyWeekdays = weeklyWeekdays xor bit },
                        label = { Text(dow.getDisplayName(TextStyle.NARROW, Locale.CHINA)) }
                    )
                }
            }
        }
        if (frequency == FrequencyType.CUSTOM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (customDates.isEmpty()) "尚未选择日期"
                    else "已选 ${customDates.size} 天：" +
                        customDates.sorted().take(5).joinToString(", ") { "${it.monthValue}/${it.dayOfMonth}" } +
                        if (customDates.size > 5) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    // Force-reopen the picker on re-click (fix #1: user re-enters picker)
                    showCustomDatePicker = false
                    showCustomDatePicker = true
                }) {
                    Text(stringResource(R.string.common_edit))
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
            // Reminder sound (#8)
            SelectorRow(
                icon = Icons.Outlined.MusicNote,
                text = alarmSoundUri?.let { describeRingtone(context, Uri.parse(it)) }
                    ?: stringResource(R.string.task_reminder_sound_default),
                onClick = openRingtonePicker
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    if (onCancel != null) onCancel(title.isNotBlank()) else onDismiss()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.task_cancel)) }
            Button(
                onClick = performSave,
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.task_save)) }
        }
    }

    /* ------------------ pickers ------------------ */
    if (showStartPicker) {
        val minToday = LocalDate.now()
        val endDay = dueDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = startDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
                ?: minToday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    // Clamp: past dates → today, future beyond dueDate → dueDate
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
        val minDay = (startDate ?: LocalDate.now()).coerceAtLeast(LocalDate.now())
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dueDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
                ?: minDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    // dueDate must be >= startDate. If user picks earlier, show error.
                    if (picked.isBefore(minDay)) {
                        dueDate = minDay // auto-correct
                        dueDateError = true
                    } else {
                        dueDate = picked
                        dueDateError = false
                    }
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
                    showFrequencyDialog = false
                    if (frequency == FrequencyType.CUSTOM) {
                        // Requirement: custom dates require dueDate to be set first.
                        // Do NOT auto-fill dueDate — show error and scroll to it instead.
                        if (dueDate == null) {
                            dueDateError = true
                            coroutineScope.launch {
                                scrollState.scrollTo(scrollState.maxValue)
                            }
                        } else {
                            showCustomDatePicker = true
                        }
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            }
        )
    }

    // ====== Custom date picker with range [startDate, dueDate] ======
    // dueDate is guaranteed non-null here (checked before opening).
    if (showCustomDatePicker && dueDate != null) {
        val pickerStart: LocalDate = (startDate ?: LocalDate.now()).coerceAtLeast(LocalDate.now())
        val pickerEnd: LocalDate = dueDate!!
        CustomDatePickerDialog(
            initialSelected = customDates,
            startDate = pickerStart,
            endDate = pickerEnd,
            onConfirm = { selected ->
                customDates = selected.toSet()
                showCustomDatePicker = false
            },
            onDismiss = { showCustomDatePicker = false }
        )
    }
}

private fun describeRingtone(ctx: Context, uri: Uri): String {
    return runCatching {
        val r = RingtoneManager.getRingtone(ctx, uri)
        r?.getTitle(ctx) ?: uri.toString()
    }.getOrElse { uri.toString() }
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
 * Custom multi-date picker constrained to [startDate]..[endDate] per requirement #2.
 * Past dates / post-deadline dates are drawn greyed out and never toggle the
 * selection set.
 *
 * BUG #1 FIX: Previously this component was rendered inside AlertDialog.text = {},
 * which runs in a sub-composition that couldn't re-compose the custom Box click-
 * able modifier correctly. We still use AlertDialog, but now the component uses
 * standard Material3 semantics and is guaranteed to be visible whenever
 * showCustomDatePicker is true.
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
    var selected by remember(startDate, endDate, initialSelected) {
        mutableStateOf(initialSelected.filter { it in startDate..endDate }.toSet())
    }
    var month by remember(startDate, endDate) {
        mutableStateOf(YearMonth.from(
            initialSelected.firstOrNull() ?: startDate
        ))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = {
            Column {
                Text(stringResource(R.string.task_frequency_custom))
                Text(
                    text = "可选范围：${startDate.monthValue}/${startDate.dayOfMonth} 至 ${endDate.monthValue}/${endDate.dayOfMonth}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Box(Modifier.fillMaxSize()) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀") }
                        Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { month = month.plusMonths(1) }) { Text("▶") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        DayOfWeek.values().forEach { dow ->
                            Text(
                                text = dow.getDisplayName(TextStyle.NARROW, Locale.CHINA),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val firstDay = month.atDay(1)
                    val startOffset = firstDay.dayOfWeek.value - 1
                    val daysInMonth = month.lengthOfMonth()
                    val today = LocalDate.now()
                    val lower = startDate.let { if (it.isBefore(today)) today else it }
                    val upper = endDate
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
                                        // ====== BUG #2 FIX: exact (today..dueDate) range ======
                                        val outside = date.isBefore(lower) || date.isAfter(upper)
                                        val isSel = selected.contains(date)
                                        val bg = when {
                                            isSel -> MaterialTheme.colorScheme.primary
                                            else -> Color.Transparent
                                        }
                                        val stroke = when {
                                            !outside && !isSel -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                            else -> Color.Transparent
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(bg)
                                                .then(
                                                    if (stroke != Color.Transparent) Modifier.border(
                                                        width = 1.dp,
                                                        color = stroke,
                                                        shape = CircleShape
                                                    )
                                                    else Modifier
                                                )
                                                .then(
                                                    if (outside) Modifier
                                                    else Modifier.clickable {
                                                        selected = if (selected.contains(date))
                                                            selected - date
                                                        else
                                                            selected + date
                                                    }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${date.dayOfMonth}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (today.isEqual(date) && !isSel) FontWeight.SemiBold else null,
                                                color = when {
                                                    outside -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                                    isSel -> MaterialTheme.colorScheme.onPrimary
                                                    today.isEqual(date) -> MaterialTheme.colorScheme.primary
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已选择 ${selected.size} 天",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}
