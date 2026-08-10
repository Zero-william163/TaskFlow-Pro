package com.taskflow.app.ui.permission

import android.Manifest
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
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
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
import com.taskflow.app.permission.PermissionLevel
import com.taskflow.app.permission.PermissionLogger
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

    // Widget 放置状态——唯一可信源：AppWidgetManager，不信任任何本地缓存
    var widgetPlaced by remember { mutableStateOf(WidgetHelper.isWidgetPlaced(context)) }

    // 进入页面 + 从系统返回时都刷新一次，确保状态实时
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        PermissionLogger.logRefresh("PermissionScreen ON_RESUME")
        viewModel.refresh()
        widgetPlaced = WidgetHelper.isWidgetPlaced(context)
    }

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

            PermissionType.CHANNEL_ALARM -> {
                // 直达 reminders 通知渠道详情页（最精准，带 channelId）
                if (!pm.startIntent(PermissionType.CHANNEL_ALARM)) {
                    Toast.makeText(context, "无法跳转闹钟渠道设置页", Toast.LENGTH_SHORT).show()
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
            // —— 已删除：OVERLAY / AUTO_START / BACKGROUND_RUN / LOCK_SCREEN
            //    这些厂商专项/B级权限令人困惑，用户反馈"不必要的没用权限"。
            // ——
            else -> Unit
        }
    }

    fun addWidget() {
        android.util.Log.d("PermissionScreen", "addWidget clicked")
        // 禁止重复创建：先通过系统 API 确认桌面是否已有 Widget
        if (WidgetHelper.isWidgetPlaced(context)) {
            android.util.Log.d("PermissionScreen", "addWidget: widget already placed, skip")
            Toast.makeText(
                context,
                context.getString(R.string.permission_widget_already_placed),
                Toast.LENGTH_SHORT
            ).show()
            widgetPlaced = true
            return
        }
        val report = WidgetCapability.report(context)
        android.util.Log.d(
            "PermissionScreen",
            "addWidget: canAutoPin=${report.canAutoPin}, " +
                "launcherSupported=${report.launcherSupported}, " +
                "vendor=${report.vendorName}, vendorRestricted=${report.vendorRestricted}"
        )

        // 先尝试自动添加（即使是厂商设备也尝试，让系统决定是否支持）
        if (report.canAutoPin) {
            val pinned = WidgetHelper.requestPinWidget(context)
            android.util.Log.d("PermissionScreen", "addWidget: requestPinWidget returned $pinned")
            if (pinned) {
                val err = WidgetHelper.lastPinError
                guideDialogMessage = if (err != null) {
                    "系统接受了添加请求，但发生异常：${err.javaClass.simpleName}: ${err.message}\n\n" +
                        "如果桌面没有弹出确认框，请手动添加：\n" +
                        "长按桌面 → 小组件 → TaskFlow → 拖到桌面"
                } else {
                    "系统正在请求添加小组件。\n\n" +
                        "请在桌面弹出的系统确认框中点击「添加」。\n\n" +
                        "如果未弹出确认框（部分华为/小米 ROM 会拦截自动添加），" +
                        "请手动添加：\n长按桌面 → 小组件 → TaskFlow → 拖到桌面"
                }
                showGuideDialog = true
                return
            }
            val err = WidgetHelper.lastPinError
            val diag = WidgetHelper.lastPinDiagnostic
            android.util.Log.w(
                "PermissionScreen",
                "addWidget: requestPinWidget failed, error=$err, diagnostic=$diag"
            )
            guideDialogMessage = if (err != null) {
                "添加失败：${err.javaClass.simpleName}\n原因：${err.message}\n\n" +
                    "诊断：$diag\n\n" +
                    "请手动添加：\n长按桌面 → 小组件 → TaskFlow → 拖到桌面"
            } else if (!report.launcherSupported) {
                "当前桌面启动器不支持应用内自动添加小组件。\n\n" +
                    "请手动添加：\n长按桌面 → 小组件 → TaskFlow → 拖到桌面\n\n" +
                    "诊断：$diag"
            } else {
                report.blockingReason(context) + "\n\n诊断：$diag"
            }
            showGuideDialog = true
            return
        }

        // 降级：显示引导文案
        guideDialogMessage = report.blockingReason(context)
        showGuideDialog = true
    }

    // —— 精简后：只保留「必需权限」一组，不再分 A/B/厂商 三级 ——
    val requiredItems = items.filter { it.level == PermissionLevel.REQUIRED }

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
            // —— 必需权限 ——
            item("req_header") {
                SectionHeader(
                    title = stringResource(R.string.permission_section_required),
                    desc = stringResource(R.string.permission_section_required_desc)
                )
            }
            items(requiredItems, key = { "req_${it.type}" }) { item ->
                PermissionRow(item, onAction = { handleItemClick(item) })
            }

            // —— 桌面小组件（独立管理） ——
            item("widget_header") {
                SectionHeader(
                    title = stringResource(R.string.permission_section_widget),
                    desc = stringResource(R.string.permission_section_widget_desc)
                )
            }
            item("widget_row") {
                WidgetRow(
                    placed = widgetPlaced,
                    onAction = { addWidget() }
                )
            }
        }
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text(stringResource(R.string.permission_guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        guideDialogMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("知道了")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, desc: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.permission_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(placed: Boolean, onAction: () -> Unit) {
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
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.permission_widget),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.permission_widget_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (placed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = Color(0xFF15D0AB)
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
                        text = stringResource(R.string.permission_widget_status_placed),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            } else {
                Button(
                    onClick = onAction,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.permission_widget_add))
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
        PermissionStatus.MANUAL -> R.string.permission_status_manual
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
    PermissionType.CHANNEL_ALARM -> Icons.Outlined.NotificationsActive
    PermissionType.BATTERY -> Icons.Outlined.BatteryFull
    PermissionType.EXACT_ALARM -> Icons.Outlined.Schedule
    // —— 已删除：OVERLAY / AUTO_START / BACKGROUND_RUN / LOCK_SCREEN
    else -> Icons.Outlined.Notifications
}
