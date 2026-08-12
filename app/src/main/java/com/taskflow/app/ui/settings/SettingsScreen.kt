package com.taskflow.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.R
import com.taskflow.app.data.preferences.SoundType
import com.taskflow.app.data.preferences.ThemeMode
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

    // ====== 自定义音效文件选择器 (spec: 允许选择本地短音效文件作为按键反馈音) ======
    val soundFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable read permission so playback survives process death.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setSoundCustomUri(uri.toString())
            viewModel.setSoundType(SoundType.CUSTOM)
            viewModel.previewSound(SoundType.CUSTOM)
            Toast.makeText(context, "已设置自定义音效", Toast.LENGTH_SHORT).show()
        }
    }

    // 只信任系统 API 的 Widget 状态（不依赖任何本地缓存）
    var widgetStatus by remember { mutableStateOf(WidgetStatus()) }
    val refreshKey = remember { mutableStateOf(0) }

    fun refreshWidget() {
        val report = WidgetCapability.report(context)
        widgetStatus = WidgetStatus(
            placed = report.alreadyPlaced,
            launcherSupported = report.launcherSupported,
            vendorRestricted = report.vendorRestricted,
            vendorName = report.vendorName,
            canAttemptAutoPin = report.canAttemptAutoPin
        )
    }

    LaunchedEffect(Unit) { refreshWidget() }
    // 权限页/系统弹窗返回时重新检测
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshWidget() }
    LaunchedEffect(refreshKey.value) {
        if (refreshKey.value > 0) delay(1500)
        refreshWidget()
    }

    var showWidgetGuideDialog by remember { mutableStateOf(false) }
    var widgetGuideMessage by remember { mutableStateOf("") }
    // 音效类型选择弹窗
    var showSoundTypeDialog by remember { mutableStateOf(false) }

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

        // ====== 交互音效模块 (SoundEffectManager) ======
        // 总开关 + 类型选择 (木鱼/轴体/气泡/滴答) + 音量滑块 + 试听。
        // 关键交互触发点 (点击编辑图标、进入专注页) 时由各页面调用
        // SoundEffectManager.playClick() 实际播放。
        SectionTitle(stringResource(R.string.settings_sound_section))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                // 总开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_sound_enabled),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.settings_sound_enabled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.soundEnabled, onCheckedChange = viewModel::setSoundEnabled)
                }

                if (state.soundEnabled) {
                    Spacer(Modifier.height(16.dp))

                    // 音效类型 (FilterChip 选择 + 试听) — 横向可滑动，避免右侧被遮挡
                    Text(
                        text = stringResource(R.string.settings_sound_type),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        SoundType.entries.forEach { st ->
                            FilterChip(
                                selected = state.soundType == st,
                                onClick = {
                                    if (st == SoundType.CUSTOM && state.soundCustomUri == null) {
                                        // 未选择自定义音效文件 → 触发文件选择器
                                        soundFileLauncher.launch(arrayOf("audio/*"))
                                    } else {
                                        viewModel.setSoundType(st)
                                        // 点击即试听该音效
                                        viewModel.previewSound(st)
                                    }
                                },
                                label = { Text(st.label) }
                            )
                        }
                    }

                    // 自定义音效文件选择入口 (仅当选中 CUSTOM 时显示)
                    if (state.soundType == SoundType.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (state.soundCustomUri != null) "✓ 已选择自定义音效"
                                else "未选择音效文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { soundFileLauncher.launch(arrayOf("audio/*")) }) {
                                Text(if (state.soundCustomUri != null) "重新选择" else "选择文件")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 音量滑块
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${state.soundVolume}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp)
                        )
                        Slider(
                            value = state.soundVolume.toFloat(),
                            onValueChange = { viewModel.setSoundVolume(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { viewModel.previewSound(state.soundType) }
                        ) {
                            Text(stringResource(R.string.settings_sound_preview))
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sound_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                report.alreadyPlaced -> {
                                    // 已添加：禁止重复创建
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
                                    // 无法自动添加：显示厂商特定引导文字，而不是跳转设置首页
                                    widgetGuideMessage = report.blockingReason(context)
                                    showWidgetGuideDialog = true
                                }
                            }
                            refreshKey.value++
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !widgetStatus.placed,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (widgetStatus.placed) {
                                stringResource(R.string.settings_widget_status_added)
                            } else {
                                stringResource(R.string.settings_widget_add)
                            }
                        )
                    }
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
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/${com.taskflow.app.update.UpdateConfig.GITHUB_OWNER}/${com.taskflow.app.update.UpdateConfig.GITHUB_REPO}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    // 打开应用详情页，供用户开启「允许创建小组件」等权限
                    WidgetCapability.openAppPermissionSettings(context)
                }) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showWidgetGuideDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }
}

private data class WidgetStatus(
    val placed: Boolean = false,
    val launcherSupported: Boolean = true,
    val vendorRestricted: Boolean = false,
    val vendorName: String? = null,
    val canAttemptAutoPin: Boolean = false
) {
    fun statusLabel(): String = when {
        placed -> "状态：已添加"
        vendorRestricted -> "${vendorName ?: "当前"} 设备：需按引导手动添加"
        !launcherSupported -> "当前启动器不支持自动添加，请手动拖拽"
        else -> "状态：未添加（点击下方按钮添加）"
    }
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
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
