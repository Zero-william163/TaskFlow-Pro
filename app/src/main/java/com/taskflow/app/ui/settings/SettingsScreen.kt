package com.taskflow.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.components.SectionTitle
import com.taskflow.app.ui.components.SoftCard
import com.taskflow.app.widget.WidgetCapability
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    val viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var widgetStatus by remember { mutableStateOf(WidgetStatus()) }
    LaunchedEffect(Unit) { widgetStatus = computeWidgetStatus(context) }
    val refreshKey = remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey.value) {
        if (refreshKey.value > 0) {
            delay(2000)
        }
        widgetStatus = computeWidgetStatus(context)
    }
    val widgetAddedPrefs by UserPreferences.get(context).widgetAdded.collectAsState(initial = false)
    LaunchedEffect(widgetAddedPrefs) {
        if (widgetAddedPrefs) {
            widgetStatus = computeWidgetStatus(context)
        }
    }

    var showPermissionGuideDialog by remember { mutableStateOf(false) }
    var permissionGuideMessage by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        showPermissionGuideDialog = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle(stringResource(R.string.settings_appearance))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_dynamic_color), modifier = Modifier.weight(1f))
                    Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Brightness6, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_theme), modifier = Modifier.weight(1f))
                }
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(state.themeMode == mode) { viewModel.setThemeMode(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.settings_widget_section))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_widget_add),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = widgetStatus.statusLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button(
                        onClick = {
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
                            refreshKey.value++
                        },
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.settings_widget_add)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.settings_general))
        SettingsRow(
            icon = Icons.Outlined.Security,
            title = stringResource(R.string.permission_title),
            onClick = onOpenPermissions
        )
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.settings_check_update),
            onClick = onOpenUpdate
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.settings_about))
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.app_name),
            subtitle = "${stringResource(R.string.settings_version)} ${state.versionName}"
        )
        SettingsRow(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.settings_github),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${com.taskflow.app.update.UpdateConfig.GITHUB_OWNER}/${com.taskflow.app.update.UpdateConfig.GITHUB_REPO}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        )
        SettingsRow(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.settings_license),
            subtitle = "Apache License 2.0"
        )
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

private data class WidgetStatus(
    val placed: Boolean = false,
    val launcherSupported: Boolean = true,
    val vendorRestricted: Boolean = false,
    val vendorName: String? = null
) {
    fun statusLabel(): String = when {
        placed -> "状态：已添加"
        vendorRestricted -> "$vendorName 设备：可能需要开启权限"
        !launcherSupported -> "当前启动器不支持自动添加"
        else -> "状态：未添加（点击下方按钮添加）"
    }
}

private fun computeWidgetStatus(context: Context): WidgetStatus {
    val report = WidgetCapability.report(context)
    return WidgetStatus(
        placed = report.widgetAlreadyPlaced,
        launcherSupported = report.launcherSupported,
        vendorRestricted = report.vendorRestricted,
        vendorName = report.vendorName
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    SoftCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
