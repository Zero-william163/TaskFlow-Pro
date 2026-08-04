package com.taskflow.app.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.permission.PermissionItem
import com.taskflow.app.permission.PermissionManager
import com.taskflow.app.permission.PermissionStatus
import com.taskflow.app.permission.PermissionType
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.SoftCard
import com.taskflow.app.widget.WidgetCapability
import com.taskflow.app.widget.WidgetHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val viewModel: PermissionViewModel = viewModel(factory = AppViewModelFactory)
    val items by viewModel.items.collectAsState()
    val context = LocalContext.current
    val pm = remember { PermissionManager(context) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // 进入页面 + 从系统返回时都刷新一次，确保状态实时
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    var showWidgetGuideDialog by remember { mutableStateOf(false) }
    var widgetGuideMessage by remember { mutableStateOf("") }
    var showGuideDialog by remember { mutableStateOf(false) }
    var guideDialogMessage by remember { mutableStateOf("") }

    // Android 13+ 请求 POST_NOTIFICATIONS 运行时权限
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 用户拒绝运行时权限 -> 直达通知设置页，让用户手动开启
            if (!pm.startIntent(PermissionType.NOTIFICATION)) {
                Toast.makeText(context, "无法跳转通知设置页", Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.refresh()
        }
    }

    fun handleItemClick(item: PermissionItem) {
        when (item.type) {
            PermissionType.WIDGET -> {
                val report = WidgetCapability.report(context)
                when {
                    report.alreadyPlaced -> {
                        Toast.makeText(
                            context,
                            R.string.settings_widget_status_added,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    report.canAttemptAutoPin -> {
                        val pinned = WidgetHelper.requestPinWidget(context)
                        if (pinned) {
                            Toast.makeText(
                                context,
                                R.string.settings_widget_prompt_success,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            widgetGuideMessage = report.blockingReason(context)
                            showWidgetGuideDialog = true
                        }
                    }
                    else -> {
                        widgetGuideMessage = report.blockingReason(context)
                        showWidgetGuideDialog = true
                    }
                }
            }

            PermissionType.NOTIFICATION -> {
                // Android 13+ 先走运行时权限请求；其他版本直接跳通知设置页
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // 直达本应用通知设置页（带 package + uid）
                    if (!pm.startIntent(PermissionType.NOTIFICATION)) {
                        Toast.makeText(context, "无法跳转通知设置页", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            PermissionType.EXACT_ALARM -> {
                // 直达本应用精确闹钟权限页（带包名 data）
                if (!pm.startIntent(PermissionType.EXACT_ALARM)) {
                    Toast.makeText(context, "无法跳转精确闹钟设置页", Toast.LENGTH_SHORT).show()
                }
            }

            PermissionType.BATTERY -> {
                // 直达电池优化请求页（直接弹出系统对话框）
                if (!pm.startIntent(PermissionType.BATTERY)) {
                    Toast.makeText(context, "无法跳转电池优化设置页", Toast.LENGTH_SHORT).show()
                }
            }

            PermissionType.AUTO_START,
            PermissionType.BACKGROUND_RUN -> {
                // 国产 ROM：优先直达厂商特定页面，失败则显示文字教程
                if (!pm.startIntent(item.type)) {
                    // 无法直达 -> 显示厂商文字引导
                    val guide = pm.vendorGuideFor(item.type)
                    if (guide != null) {
                        guideDialogMessage = guide
                        showGuideDialog = true
                    } else {
                        // 兜底：应用详情页
                        runCatching { context.startActivity(pm.appDetailsIntent()) }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.type }) { item ->
                PermissionRow(item, onAction = { handleItemClick(item) })
            }
        }
    }

    if (showWidgetGuideDialog) {
        AlertDialog(
            onDismissRequest = { showWidgetGuideDialog = false },
            title = { Text("添加桌面小组件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        widgetGuideMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "请长按桌面空白处 → 选择「小组件」→ 找到 TaskFlow → 拖拽到桌面。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWidgetGuideDialog = false
                    WidgetCapability.openAppDetails(context)
                }) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showWidgetGuideDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("操作指引") },
            text = {
                Text(
                    guideDialogMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun PermissionRow(item: PermissionItem, onAction: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), onClick = onAction) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    iconFor(item.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(item.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(item.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                item.isOk -> StatusBadge(item)
                item.status == PermissionStatus.NONE -> {
                    Text(
                        text = stringResource(R.string.permission_request_now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                else -> {
                    Button(
                        onClick = onAction,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.permission_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(item: PermissionItem) {
    val color = Color(0xFF15D0AB)
    val labelRes = when (item.status) {
        PermissionStatus.ADDED -> R.string.permission_status_added
        else -> R.string.permission_status_granted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun iconFor(type: PermissionType): ImageVector = when (type) {
    PermissionType.NOTIFICATION -> Icons.Outlined.Notifications
    PermissionType.BATTERY -> Icons.Outlined.BatteryFull
    PermissionType.EXACT_ALARM -> Icons.Outlined.Schedule
    PermissionType.WIDGET -> Icons.Outlined.Apps
    PermissionType.AUTO_START -> Icons.Outlined.Security
    PermissionType.BACKGROUND_RUN -> Icons.Outlined.BatteryFull
}

private fun Intent.start(context: android.content.Context) {
    runCatching { context.startActivity(this) }
}
