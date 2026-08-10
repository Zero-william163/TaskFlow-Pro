package com.taskflow.app.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskflow.app.ui.theme.GradientEnd
import com.taskflow.app.ui.theme.GradientStart

/**
 * First-launch onboarding screen: walks the user through each required permission
 * with a clear explanation of why it's needed, before entering the main app.
 *
 * Design reference: Google Calendar / Microsoft To Do / Notion first-run flow.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val steps = remember {
        mutableStateListOf(
            OnboardingStep(
                icon = Icons.Outlined.Notifications,
                title = "通知权限",
                description = "为了在任务到期时准时发送提醒通知，需要开启通知权限。",
                isGranted = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
                action = { /* Request via permission launcher */ }
            ),
            OnboardingStep(
                icon = Icons.Outlined.Alarm,
                title = "精确闹钟权限",
                description = "为了准时提醒任务，需要开启精确闹钟权限，确保提醒像系统闹钟一样可靠。",
                isGranted = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        am.canScheduleExactAlarms()
                    } else true
                },
                action = { /* Opens settings */ }
            ),
            OnboardingStep(
                icon = Icons.Outlined.BatteryFull,
                title = "电池优化白名单",
                description = "为了防止系统在后台杀掉APP导致提醒不触发，需要加入电池优化白名单。",
                isGranted = {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                },
                action = { /* Opens settings */ }
            ),
            OnboardingStep(
                icon = Icons.Outlined.Apps,
                title = "桌面小组件（可选）",
                description = "小组件需要桌面添加权限，用于在桌面显示任务。如不支持自动添加，将引导开启相关系统权限。",
                isGranted = {
                    // Real capability check: a widget is already placed on the
                    // home screen. This is the only reliable signal that the
                    // user actually has the widget — isRequestPinAppWidgetSupported
                    // returns true on many ROMs that then silently no-op.
                    com.taskflow.app.widget.WidgetHelper.isAnyWidgetPlaced(context)
                },
                action = { /* Opens widget pin — handled in click handler */ },
                optional = true
            )
        )
    }

    // Refresh permission states when screen resumes (user returns from settings)
    var refreshTrigger by remember { mutableStateOf(0) }
    // Widget manual-guide dialog state. Shown when auto-pin is not possible
    // or fails, per spec: "如果失败：不要静默。必须显示原因。"
    var showWidgetGuideDialog by remember { mutableStateOf(false) }
    var widgetGuideMessage by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Notification permission launcher
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(GradientStart, GradientEnd)),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        text = "欢迎使用 TaskFlow",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "为了确保任务提醒准时到达，请完成以下权限设置。每个权限都有明确的用途说明。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        items(steps.indices.toList()) { index ->
            val step = steps[index]
            val granted = step.isGranted()
            PermissionStepCard(
                step = step,
                isGranted = granted,
                onRequest = {
                    when (index) {
                        0 -> {
                            // Notification permission
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        1 -> {
                            // Exact alarm settings
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    )
                                }
                            }
                        }
                        2 -> {
                            // Battery optimization
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }
                        3 -> {
                            val report = com.taskflow.app.widget.WidgetCapability.report(context)
                            when {
                                report.widgetAlreadyPlaced -> {
                                    android.widget.Toast.makeText(
                                        context,
                                        "桌面已存在 TaskFlow 小组件，无需重复添加。",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                report.canAttemptAutoPin -> {
                                    val pinned = com.taskflow.app.widget.WidgetHelper.requestPinWidget(context)
                                    if (!pinned) {
                                        val err = com.taskflow.app.widget.WidgetHelper.lastPinError
                                        val diag = com.taskflow.app.widget.WidgetHelper.lastPinDiagnostic
                                        widgetGuideMessage = if (err != null) {
                                            "自动添加失败：${err.javaClass.simpleName}\n原因：${err.message}\n\n诊断：$diag"
                                        } else {
                                            "系统未弹出添加面板（当前 Launcher 可能不支持自动添加）。\n\n诊断：$diag"
                                        }
                                        showWidgetGuideDialog = true
                                    }
                                }
                                else -> {
                                    widgetGuideMessage = report.blockingReason(context)
                                    showWidgetGuideDialog = true
                                }
                            }
                            refreshTrigger++
                        }
                    }
                }
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("跳过") }

                Button(
                    onClick = {
                        // Mark onboarding as completed
                        onComplete()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("完成设置") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showWidgetGuideDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWidgetGuideDialog = false },
            title = { Text("需要开启权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (widgetGuideMessage.isNotBlank()) {
                        Text(
                            widgetGuideMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Android 没有专门的「小组件权限」。如系统未弹出添加面板，请手动添加：\n" +
                            "长按桌面空白处 → 选择「小组件」→ 找到 TaskFlow → 拖拽到桌面。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showWidgetGuideDialog = false
                    com.taskflow.app.widget.WidgetCapability.openAppPermissionSettings(context)
                }) { Text("前往设置") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showWidgetGuideDialog = false
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PermissionStepCard(
    step: OnboardingStep,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) Color(0xFF15D0AB).copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    step.icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF15D0AB)
                           else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (step.optional) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(可选)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // Status / Action button
            if (isGranted) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "已开启",
                    tint = Color(0xFF15D0AB),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onRequest,
                    shape = RoundedCornerShape(12.dp),
                    content = { Text("开启", style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val isGranted: () -> Boolean,
    val action: () -> Unit,
    val optional: Boolean = false
)
