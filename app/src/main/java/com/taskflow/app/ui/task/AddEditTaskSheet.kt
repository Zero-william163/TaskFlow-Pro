package com.taskflow.app.ui.task

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.model.Category
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
    onExternalSaveTriggered: () -> Unit = {},
    showTitle: Boolean = true
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
    // Due date is now OPTIONAL (spec: 默认关闭，从开始日期起无限期执行).
    // hasDueDate is the source of truth for the form toggle; dueDate holds the
    // picked value but is only persisted when hasDueDate is true.
    var hasDueDate by remember(task) { mutableStateOf(task?.hasDueDate ?: false) }
    var dueDate by remember(task) { mutableStateOf(task?.dueDate?.toLocalDate() ?: LocalDate.now()) }
    var dueDateError by remember { mutableStateOf(false) }
    // Distinguish the two due-date error cases so the inline message can match
    // the actual problem (spec: empty → "请填写截止日期"; earlier-than-start →
    // "截止日期不能早于开始日期"). Null = no error.
    var dueDateErrorMessage by remember { mutableStateOf<String?>(null) }

    // Default reminder ON for new tasks (spec change: 提醒时间默认开关 = 开启).
    // For edits, mirror the persisted state.
    var reminderEnabled by remember(task) {
        mutableStateOf(task?.reminderTime != null || !isEdit)
    }
    var reminderTime by remember(task) {
        mutableStateOf(task?.reminderTime?.toLocalTime() ?: LocalTime.of(9, 0))
    }
    // Default reminder frequency = DAILY (spec: 提醒频率默认「每日」RepeatMode.DAILY).
    var reminderMode by remember(task) {
        mutableStateOf(task?.reminderMode ?: ReminderMode.DAILY)
    }
    var alarmSoundUri by remember(task) {
        mutableStateOf(task?.alarmSoundUri.takeUnless { it.isNullOrBlank() })
    }

    // Pomodoro focus duration (spec: 默认 25 分). 0 on existing tasks falls
    // back to 25 in the UI/Pomodoro screen; the form offers 10/25/35/自定义 chips.
    var focusDurationMinutes by remember(task) {
        mutableStateOf(task?.focusDurationMinutes?.takeIf { it > 0 } ?: 25)
    }
    var showFocusDurationDialog by remember { mutableStateOf(false) }

    // Pomodoro pause-limit duration (spec: 默认 2 分, 暂停限制时长).
    // 0 on existing tasks falls back to 2 in the UI/Pomodoro screen; the form
    // offers 1/2/5/自定义 chips. When the user hits "暂停" on the focus screen,
    // a countdown dialog enforces this limit to avoid breaking focus flow.
    var pauseLimitMinutes by remember(task) {
        mutableStateOf(task?.pauseLimitMinutes?.takeIf { it > 0 } ?: 2)
    }
    var showPauseLimitDialog by remember { mutableStateOf(false) }

    var frequency by remember(task) { mutableStateOf(task?.frequency ?: FrequencyType.DAILY) }
    var customDates by remember(task) {
        mutableStateOf((task?.customDates.orEmpty()).toSet())
    }
    var weeklyWeekdays by remember(task) {
        mutableStateOf(task?.weeklyWeekdays ?: 0)
    }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var showReminderModeDialog by remember { mutableStateOf(false) }
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showSoundPickerDialog by remember { mutableStateOf(false) }

    // ====== Custom category dialog state (feature: 自定义分类 + 自定义颜色) ======
    var showCustomCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteCategoryConfirm by remember { mutableStateOf<Category?>(null) }

    // ====== Lifecycle safety: reset all dialog states when Activity goes to
    // background. Prevents frozen state where a dialog scrim blocks touches. ======
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        showStartPicker = false
        showEndPicker = false
        showReminderTimePicker = false
        showReminderModeDialog = false
        showFrequencyDialog = false
        showCustomDatePicker = false
        showSoundPickerDialog = false
        showCustomCategoryDialog = false
        showDeleteCategoryConfirm = null
        showFocusDurationDialog = false
        showPauseLimitDialog = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        showStartPicker = false
        showEndPicker = false
        showReminderTimePicker = false
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val dueDateScrollId = "dueDateSection"

    // ====== RingtonePicker ====== (#7 + #8)
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { ar ->
        val uri = ar.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            // Persist any read permission granted by the ringtone picker so we can
            // play this custom sound later (even after process restart / alarm fire).
            runCatching {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            }.recoverCatching { _ ->
                // If persistable grant isn't supported (non-document provider URI),
                // fall back to an explicit non-persisted grant. Still works until
                // the app's process is killed, and covers the overwhelmingly-common
                // RingtoneManager/MediaStore ringtone URIs.
                try {
                    context.grantUriPermission(context.packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) {}
            }
            alarmSoundUri = uri.toString()
        } else {
            alarmSoundUri = null
        }
    }
    // ====== 通知权限 (POST_NOTIFICATIONS)：按需触发 (Just-In-Time) ======
    // 仅当用户主动打开"提醒"开关时，才请求通知权限。移除首次启动强制弹窗。
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            reminderEnabled = true
            Toast.makeText(context, "通知权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context,
                "未开启通知权限，任务到期将无法通过系统通知提醒，请在设置中手动开启。",
                Toast.LENGTH_LONG
            ).show()
        }
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
        // ====== Due date is now OPTIONAL (spec: 默认关闭，无限期执行).
        // Only validate when the user has explicitly enabled a due date.
        if (hasDueDate) {
            if (dueDate == null) {
                dueDateError = true
                dueDateErrorMessage = "请填写截止日期"
                coroutineScope.launch {
                    scrollState.scrollTo(scrollState.maxValue)
                }
                return@save
            }
            val start = startDate ?: LocalDate.now()
            if (dueDate!!.isBefore(start)) {
                dueDateError = true
                dueDateErrorMessage = "截止日期不能早于开始日期"
                coroutineScope.launch {
                    scrollState.scrollTo(scrollState.maxValue)
                }
                return@save
            }
        }
        val start = startDate ?: LocalDate.now()
        // Persist dueDate only when hasDueDate is true; otherwise null so the
        // task is treated as having no deadline.
        val end = if (hasDueDate) dueDate!!.atStartOfDay() else null
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
            hasDueDate = hasDueDate,
            reminderTime = reminder,
            reminderMode = if (reminderEnabled) reminderMode else ReminderMode.ONCE,
            frequency = frequency,
            customDatesRaw = customRaw,
            weeklyWeekdays = weeklyWeekdays,
            alarmSoundUri = alarmSoundUri,
            focusDurationMinutes = focusDurationMinutes,
            pauseLimitMinutes = pauseLimitMinutes
        )
        viewModel.saveTask(built) { id, isNew ->
            onSaved(id, isNew)
            // 新建任务后，若桌面还没有 Widget，提示用户创建快捷小组件
            if (isNew) {
                (context as? android.app.Activity)?.let { activity ->
                    com.taskflow.app.widget.WidgetAndPermissionHelper.maybePromptPinWidget(activity)
                }
            }
        }
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
            .padding(bottom = 32.dp, top = if (showTitle) 8.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showTitle) {
            Text(
                text = stringResource(if (isEdit) R.string.task_edit else R.string.task_add),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
        }

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
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 4.dp)
        ) {
            items(categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = categoryId == cat.id,
                    onClick = { categoryId = cat.id },
                    label = { Text(cat.name) },
                    leadingIcon = { PriorityDot(color = Color(cat.color), size = 8) },
                    // Long-press a custom category to delete it (spec: 管理与删除/配套).
                    // Built-in categories are not deletable. Use pointerInput so it
                    // does not conflict with FilterChip's own click handling.
                    modifier = Modifier.pointerInput(cat.id) {
                        detectTapGestures(
                            onLongPress = {
                                if (cat.isCustom) showDeleteCategoryConfirm = cat
                            }
                        )
                    }
                )
            }
            item(key = "add_custom") {
                // "+ 自定义" chip — opens the create-custom-category dialog.
                FilterChip(
                    selected = false,
                    onClick = { showCustomCategoryDialog = true },
                    label = { Text("＋ 自定义") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "自定义",
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
        // Due date is OPTIONAL (spec: 默认关闭，从开始日期起无限期执行).
        // A toggle controls whether a deadline is set; the date picker only
        // shows when the toggle is on.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasDueDate) "设置截止日期" else "无截止日期（无限期执行）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = if (hasDueDate) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(
                checked = hasDueDate,
                onCheckedChange = { enabled ->
                    hasDueDate = enabled
                    if (!enabled) {
                        dueDateError = false
                        dueDateErrorMessage = null
                    }
                }
            )
        }
        if (hasDueDate) {
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
                        dueDateErrorMessage = null
                    }
                )
            }
            if (dueDateError) {
                Text(
                    text = dueDateErrorMessage ?: "请填写截止日期",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                )
            }
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

        // ====== 专注时长 (Pomodoro focus duration) ======
        // FilterChips: 10 / 25(默认) / 35 / ⚙️自定义. Drives the initial ring
        // timer value on the Pomodoro focus screen.
        SectionTitle("专注时长")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 25, 35).forEach { mins ->
                FilterChip(
                    selected = focusDurationMinutes == mins,
                    onClick = { focusDurationMinutes = mins },
                    label = { Text("${mins}分") }
                )
            }
            FilterChip(
                selected = focusDurationMinutes !in listOf(10, 25, 35),
                onClick = { showFocusDurationDialog = true },
                label = { Text("⚙️自定义") }
            )
        }

        // ====== 暂停限制时长 (Pomodoro pause-limit) ======
        // FilterChips: 1 / 2(默认) / 5 / ⚙️自定义. Drives the pause countdown
        // dialog on the Pomodoro focus screen when the user hits "暂停".
        SectionTitle("暂停限制时长")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 5).forEach { mins ->
                FilterChip(
                    selected = pauseLimitMinutes == mins,
                    onClick = { pauseLimitMinutes = mins },
                    label = { Text("${mins}分") }
                )
            }
            FilterChip(
                selected = pauseLimitMinutes !in listOf(1, 2, 5),
                onClick = { showPauseLimitDialog = true },
                label = { Text("⚙️自定义") }
            )
        }

        SectionTitle(stringResource(R.string.task_reminder))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.task_reminder), modifier = Modifier.weight(1f))
            // 通知权限按需触发（Just-In-Time）：仅在用户尝试打开提醒时才申请 POST_NOTIFICATIONS
            Switch(checked = reminderEnabled, onCheckedChange = { wantOn ->
                if (!wantOn) {
                    // 用户主动关闭：直接关
                    reminderEnabled = false
                } else {
                    // 用户尝试开启：先检查通知权限（Android 13+ 动态权限）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val has = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (has) {
                            reminderEnabled = true
                        } else {
                            // 首次且未授权：发起运行时权限请求，回调后再置 true
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        // Android 12 及以下：无需运行时申请，直接打开
                        reminderEnabled = true
                    }
                }
            })
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
        // Material3 DatePicker interprets millis as UTC. Using ZoneId.systemDefault()
        // (e.g. UTC+8) shifts the date backward by ~8 hours → previous day.
        // Fix: use UTC so the day boundary aligns with the calendar day shown.
        val todayMillis = minToday.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val endMillis = endDay?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli()
        val startSelectableDates = remember(endMillis) {
            object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayMillis &&
                        (endMillis == null || utcTimeMillis <= endMillis)
            }
        }
        val initialStartMillis = (startDate?.atStartOfDay(ZoneId.of("UTC"))
            ?.toInstant()?.toEpochMilli()
            ?: minToday.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
            .coerceAtLeast(todayMillis)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialStartMillis,
            initialDisplayedMonthMillis = initialStartMillis,
            selectableDates = startSelectableDates
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    // Material3 DatePicker stores UTC midnight millis. Convert
                    // back with UTC (NOT systemDefault) so the calendar day
                    // matches what the user tapped — otherwise negative timezones
                    // (e.g. UTC-8) shift the picked date back one day ("昨天" bug).
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    // Clamp: past dates → today, future beyond dueDate → dueDate
                    // (safety net for timezone edge cases).
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
        val minMillis = minDay.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val endSelectableDates = remember(minMillis) {
            object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= minMillis
            }
        }
        // 新建任务默认选中今天，确保"今日"和"选中"标记在同一天
        val initialDueMillis = (if (isEdit) {
            dueDate?.atStartOfDay(ZoneId.of("UTC"))
                ?.toInstant()?.toEpochMilli()
                ?: LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        } else {
            LocalDate.now().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        }).coerceAtLeast(minMillis)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialDueMillis,
            initialDisplayedMonthMillis = initialDueMillis,
            selectableDates = endSelectableDates
        )
        DatePickerDialog(
            onConfirm = {
                state.selectedDateMillis?.let {
                    // DatePicker stores UTC midnight; convert back with UTC to
                    // avoid the timezone-induced "昨天" off-by-one bug.
                    val picked = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    // dueDate must be >= startDate. If user picks earlier (shouldn't
                    // happen with SelectableDates, but kept as a safety net for
                    // timezone edge cases), show the spec-exact error and scroll
                    // the form so the error is visible.
                    if (picked.isBefore(minDay)) {
                        dueDate = minDay // auto-correct
                        dueDateError = true
                        dueDateErrorMessage = "截止日期不能早于开始日期"
                        coroutineScope.launch {
                            scrollState.scrollTo(scrollState.maxValue)
                        }
                    } else {
                        dueDate = picked
                        dueDateError = false
                        dueDateErrorMessage = null
                    }
                }
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        ) { DatePicker(state = state) }
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
                    // 过滤掉 NONE 选项，频率只有每日/每周/每月/自定义
                    FrequencyOption.entries.filter { it.type != FrequencyType.NONE }.forEach { opt ->
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

    // ====== 创建自定义分类弹窗 (feature: 自定义分类 + 自定义颜色) ======
    // 含分类名称输入框 (限 6 字) + Material/莫兰迪色调色盘 (8~12 种预设颜色)。
    // 保存后该新分类自动加入列表并默认选中，Chip 圆点颜色即用户所选颜色。
    if (showCustomCategoryDialog) {
        CustomCategoryDialog(
            onDismiss = { showCustomCategoryDialog = false },
            onSave = { name, color ->
                viewModel.addCustomCategory(name, color) { newId ->
                    // 自动选中新建的分类 (spec: 保存后的实时选中状态)。
                    categoryId = newId
                    showCustomCategoryDialog = false
                }
            }
        )
    }

    // ====== 删除自定义分类确认弹窗 (长按自定义 Chip 触发) ======
    showDeleteCategoryConfirm?.let { cat ->
        AlertDialog(
            onDismissRequest = { showDeleteCategoryConfirm = null },
            title = { Text("删除分类") },
            text = { Text("确定要删除自定义分类「${cat.name}」吗？\n已有任务将保留，但其分类会变为未匹配。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 若被删分类正被选中，回退到第一个分类。
                        if (categoryId == cat.id) {
                            categories.firstOrNull()?.let { categoryId = it.id }
                        }
                        viewModel.deleteCategory(cat.id)
                        showDeleteCategoryConfirm = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCategoryConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // ====== 专注时长自定义弹窗 (⚙️自定义 Chip 触发) ======
    if (showFocusDurationDialog) {
        var customMinutes by remember { mutableStateOf(focusDurationMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showFocusDurationDialog = false },
            title = { Text("自定义专注时长") },
            text = {
                Column {
                    Text(
                        text = "请输入专注时长（分钟，1~180）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { input ->
                            // 只保留数字
                            customMinutes = input.filter { it.isDigit() }.take(3)
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mins = customMinutes.toIntOrNull() ?: 0
                    if (mins in 1..180) {
                        focusDurationMinutes = mins
                        showFocusDurationDialog = false
                    } else {
                        Toast.makeText(context, "请输入 1~180 之间的数字", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showFocusDurationDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // ====== 暂停限制时长自定义弹窗 (⚙️自定义 Chip 触发) ======
    if (showPauseLimitDialog) {
        var customMinutes by remember { mutableStateOf(pauseLimitMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showPauseLimitDialog = false },
            title = { Text("自定义暂停限制时长") },
            text = {
                Column {
                    Text(
                        text = "请输入暂停限制时长（分钟，1~30）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { input ->
                            customMinutes = input.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mins = customMinutes.toIntOrNull() ?: 0
                    if (mins in 1..30) {
                        pauseLimitMinutes = mins
                        showPauseLimitDialog = false
                    } else {
                        Toast.makeText(context, "请输入 1~30 之间的数字", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPauseLimitDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * "创建自定义分类"弹窗：分类名称输入框 (限 6 字以内) + 莫兰迪/Material 调色盘
 * (8~12 种预设颜色 circle，选中显示 Check 图标)。底部【取消】+【保存并使用】。
 * 保存后调用 [onSave]，由上层持久化并自动选中新建分类。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomCategoryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(Category.COLOR_PALETTE.first()) }
    val canSave = name.isNotBlank() && name.length <= 6

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建自定义分类") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 分类名称输入框 (限 6 字以内)
                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        // 限制 6 个字符 (中英文均按字符计)。
                        name = if (input.length <= 6) input else input.take(6)
                    },
                    label = { Text("分类名称") },
                    placeholder = { Text("如：健身、财务、旅行") },
                    singleLine = true,
                    isError = name.isNotBlank() && name.length > 6,
                    supportingText = {
                        Text(
                            text = "最多 6 个字 (${name.length}/6)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.height(16.dp))

                // 颜色选择器 (调色盘) — 莫兰迪/Material 预设颜色 circle。
                Text(
                    text = "主题色",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // 5 列流式布局展示颜色圆点。
                val rows = Category.COLOR_PALETTE.chunked(5)
                rows.forEach { rowColors ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        rowColors.forEach { c ->
                            val isSelected = selectedColor == c
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onSurface
                                        else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = c },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "已选中",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 实时预览：展示当前 Chip 样式 (圆点 + 名称)。
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = "预览：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    PriorityDot(color = Color(selectedColor), size = 10)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = name.ifBlank { "分类名称" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, selectedColor) },
                enabled = canSave,
                shape = RoundedCornerShape(14.dp)
            ) { Text("保存并使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
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
    // BUG FIX (数字裁剪): AlertDialog constrains its content to a narrow max
    // width, which clips the rightmost grid day numbers (14/21/28) in the M3
    // DatePicker. Switching to a plain Dialog with usePlatformDefaultWidth =
    // false lets the DatePicker take its full design-spec width so every day
    // cell renders completely.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            }
        }
    }
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
                    val todayColor = MaterialTheme.colorScheme.tertiary
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
                                        val isToday = today.isEqual(date)
                                        val bg = when {
                                            isSel -> MaterialTheme.colorScheme.primary
                                            else -> Color.Transparent
                                        }
                                        val stroke = when {
                                            isToday -> todayColor
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
                                                        width = 1.5.dp,
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
                                                fontWeight = when {
                                                    isToday && !isSel -> FontWeight.Bold
                                                    else -> null
                                                },
                                                color = when {
                                                    outside -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                                    isSel -> MaterialTheme.colorScheme.onPrimary
                                                    isToday -> todayColor
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
