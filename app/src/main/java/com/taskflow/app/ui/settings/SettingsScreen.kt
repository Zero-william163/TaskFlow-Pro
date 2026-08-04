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

    // ====== Widget status (refreshes when screen resumes / after pin attempt) ======
    var widgetStatus by remember { mutableStateOf(WidgetStatus()) }
    LaunchedEffect(Unit) { widgetStatus = computeWidgetStatus(context) }
    // Re-check whenever the widget is toggled / on resume
    val refreshKey = remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey.value) {
        if (refreshKey.value > 0) {
            // Give the launcher a moment to process the pin request
            delay(2000)
        }
        widgetStatus = computeWidgetStatus(context)
    }
    // Also observe UserPreferences so we react when WidgetPinResultReceiver confirms
    val widgetAddedPrefs by UserPreferences.get(context).widgetAdded.collectAsState(initial = false)
    LaunchedEffect(widgetAddedPrefs) {
        if (widgetAddedPrefs) {
            widgetStatus = computeWidgetStatus(context)
        }
    }

    // Dialogs used by the "Add Widget" flow.
    var showWidgetGuideDialog by remember { mutableStateOf(false) }
    var widgetGuideMessage by remember { mutableStateOf("") }

    // Reset dialog state when Activity goes to background
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        showWidgetGuideDialog = false
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
                                        // requestPinWidget返回true表示系统接受了请求，
                                        // 但Widget可能还未实际添加。显示"已发送请求"
                                        // 而不是"添加成功"，并等待WidgetPinResultReceiver
                                        // 确认后再更新状态。
                                        Toast.makeText(
                                            context,
                                            "已发送添加请求，请在桌面确认",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        widgetGuideMessage = report.blockingReason.ifBlank {
                                            "系统未响应添加请求"
                                        }
                                        showWidgetGuideDialog = true
                                    }
                                }
                                else -> {
                                    widgetGuideMessage = report.blockingReason
                                    showWidgetGuideDialog = true
                                }
                            }
                            refreshKey.value++
                        },
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.settings_widget_add)) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            WidgetCapability.openWidgetPicker(context)
                        },
                        modifier = Modifier.weight(1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.settings_widget_manual)) }
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

    if (showWidgetGuideDialog) {
        AlertDialog(
            onDismissRequest = { showWidgetGuideDialog = false },
            title = { Text("无法自动添加小组件") },
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
                        "请按以下步骤手动添加：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("1. 长按桌面空白处")
                    Text("2. 选择「小组件」或「Widgets」")
                    Text("3. 找到 TaskFlow")
                    Text("4. 长按并拖到桌面，或点击添加")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWidgetGuideDialog = false
                    WidgetCapability.openWidgetPicker(context)
                }) { Text("打开小组件选择") }
            },
            dismissButton = {
                TextButton(onClick = { showWidgetGuideDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }
}

/**
 * Lightweight cacheable snapshot of the widget's presence on the device.
 * Used by the Settings screen to render the "已添加 / 未添加" status.
 */
private data class WidgetStatus(
    val placed: Boolean = false,
    val launcherSupported: Boolean = true,
    val vendorRestricted: Boolean = false,
    val vendorName: String? = null
) {
    fun statusLabel(): String = when {
        placed -> "状态：已添加"
        vendorRestricted -> "$vendorName 设备：建议手动添加"
        !launcherSupported -> "当前启动器不支持自动添加"
        else -> "状态：未添加（点击下方按钮添加）"
    }
}

private fun computeWidgetStatus(context: Context): WidgetStatus {
    val report = WidgetCapability.report(context)
    // 组合判断：getAppWidgetIds() 是最可靠的系统状态。
    // 部分OEM启动器下getAppWidgetIds()可能返回空（即使Widget已放置），
    // 此时使用UserPreferences中的widgetAdded作为辅助判断。
    val prefsAdded = runCatching {
        // 同步读取一次性检查（Flow不适合同步，使用DataStore直接读取）
        false // 暂时只依赖系统API + WidgetPinResultReceiver实时刷新
    }.getOrDefault(false)
    val trulyPlaced = report.widgetAlreadyPlaced || prefsAdded
    return WidgetStatus(
        placed = trulyPlaced,
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
    // onClick is forwarded to SoftCard so the whole card is tappable. Previously
    // the chevron was shown but the click was never wired, leaving permission /
    // update buttons unresponsive.
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
