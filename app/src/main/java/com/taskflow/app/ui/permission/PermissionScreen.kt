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
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
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

    var showGuideDialog by remember { mutableStateOf(false) }
    var guideDialogMessage by remember { mutableStateOf("") }
    var guideDialogPermissionType by remember { mutableStateOf<PermissionType?>(null) }

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

            PermissionType.OVERLAY -> {
                // 直达悬浮窗权限页（带 pkgUri，直接弹允许对话框）
                if (!pm.startIntent(PermissionType.OVERLAY)) {
                    Toast.makeText(context, "无法跳转悬浮窗设置页", Toast.LENGTH_SHORT).show()
                }
            }

            PermissionType.FOREGROUND_SERVICE -> {
                // AppOps 无公开直达 action，跳应用详情页
                if (!pm.startIntent(PermissionType.FOREGROUND_SERVICE)) {
                    Toast.makeText(context, "无法跳转前台服务设置页", Toast.LENGTH_SHORT).show()
                }
            }

            PermissionType.AUTO_START,
            PermissionType.BACKGROUND_RUN,
            PermissionType.LOCK_SCREEN -> {
                // 国产 ROM：尝试直达厂商特定页面
                // 如果 startIntent 返回 false（全部厂商 Intent 无法 resolveActivity），
                // 则显示详细文字引导，绝不回退到"看起来像主设置"的应用详情页
                if (!pm.startIntent(item.type)) {
                    val guide = pm.vendorGuideFor(item.type)
                    if (guide != null) {
                        guideDialogMessage = guide
                        guideDialogPermissionType = item.type
                        showGuideDialog = true
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

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text(stringResource(R.string.permission_guide_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.permission_cannot_jump),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        guideDialogMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                val t = guideDialogPermissionType
                if (t != null) {
                    TextButton(onClick = {
                        pm.setVendorConfirmed(t, true)
                        showGuideDialog = false
                        viewModel.refresh()
                    }) { Text(stringResource(R.string.permission_confirm_done)) }
                } else {
                    TextButton(onClick = { showGuideDialog = false }) {
                        Text("知道了")
                    }
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
    PermissionType.OVERLAY -> Icons.Outlined.Layers
    PermissionType.FOREGROUND_SERVICE -> Icons.Outlined.FlashOn
    PermissionType.AUTO_START -> Icons.Outlined.Security
    PermissionType.BACKGROUND_RUN -> Icons.Outlined.BatteryFull
    PermissionType.LOCK_SCREEN -> Icons.Outlined.Lock
}

private fun Intent.start(context: android.content.Context) {
    runCatching { context.startActivity(this) }
}
