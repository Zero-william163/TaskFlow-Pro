package com.taskflow.app.ui.permission

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Close
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.permission.PermissionItem
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

    LaunchedEffect(Unit) { viewModel.refresh() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showPermissionGuideDialog by remember { mutableStateOf(false) }
    var permissionGuideMessage by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        showPermissionGuideDialog = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.type }) { item ->
                PermissionRow(item) {
                    if (item.type == PermissionType.WIDGET) {
                        val report = WidgetCapability.report(context)
                        when {
                            report.widgetAlreadyPlaced -> {
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
                                        "请在系统弹窗中确认添加小组件",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    permissionGuideMessage =
                                        "系统未弹出添加面板，可能需要先开启创建小组件的权限。"
                                    showPermissionGuideDialog = true
                                }
                            }
                            else -> {
                                permissionGuideMessage = report.blockingReason
                                showPermissionGuideDialog = true
                            }
                        }
                    } else {
                        viewModel.intentFor(item.type)?.let { intent ->
                            runCatching { context.startActivity(intent) }
                        }
                    }
                }
            }
        }
    }

    if (showPermissionGuideDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionGuideDialog = false },
            title = { Text("需要开启权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        permissionGuideMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "请前往系统设置开启「允许创建小组件」权限，然后返回本应用重试。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionGuideDialog = false
                    WidgetCapability.openAppPermissionSettings(context)
                }) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionGuideDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PermissionRow(item: PermissionItem, onClick: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconFor(item.type), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(item.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(item)
        }
    }
}

@Composable
private fun StatusBadge(item: PermissionItem) {
    val ok = item.isOk
    val color = if (ok) Color(0xFF15D0AB) else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (ok) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (ok) R.string.permission_status_granted else R.string.permission_status_denied
            ),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun iconFor(type: PermissionType): ImageVector = when (type) {
    PermissionType.NOTIFICATION -> Icons.Outlined.Notifications
    PermissionType.INSTALL -> Icons.Outlined.Security
    PermissionType.BATTERY -> Icons.Outlined.BatteryFull
    PermissionType.EXACT_ALARM -> Icons.Outlined.Schedule
    PermissionType.WIDGET -> Icons.Outlined.Apps
}
