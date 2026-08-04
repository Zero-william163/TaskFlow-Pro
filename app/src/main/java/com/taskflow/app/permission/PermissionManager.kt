package com.taskflow.app.permission

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.taskflow.app.R
import com.taskflow.app.widget.TaskWidgetProvider

enum class PermissionType {
    NOTIFICATION,
    BATTERY,
    EXACT_ALARM,
    WIDGET,
    AUTO_START,
    BACKGROUND_RUN
}

enum class PermissionStatus { GRANTED, DENIED, ADDED, NOT_ADDED, NONE }

data class PermissionItem(
    val type: PermissionType,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val status: PermissionStatus,
    val applicable: Boolean,
    @StringRes val actionRes: Int = R.string.permission_open_settings
) {
    val isOk: Boolean
        get() = status == PermissionStatus.GRANTED || status == PermissionStatus.ADDED
}

/**
 * 统一权限管理器。
 *
 * 设计原则：
 * 1. 只做检测和跳转，不做任何 UI 决策
 * 2. 每个 intentFor() 返回直达**具体权限页**的 Intent，绝不是设置首页
 * 3. 权限状态只信任系统 API，不信任任何本地缓存
 * 4. 处理厂商差异：国产 ROM 无法直达时返回 null，由上层决定显示引导文字
 */
class PermissionManager(private val context: Context) {

    companion object {
        const val RC_NOTIFICATION = 1001
    }

    private val pkgUri: Uri by lazy { Uri.parse("package:${context.packageName}") }

    fun all(): List<PermissionItem> = listOfNotNull(
        notification(),
        exactAlarm(),
        battery(),
        widget(),
        autoStart(),
        backgroundRun()
    )

    // ==================== 通知权限 ====================

    fun notification(): PermissionItem {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return PermissionItem(
            type = PermissionType.NOTIFICATION,
            titleRes = R.string.permission_notifications,
            descRes = R.string.permission_notifications_desc,
            status = if (enabled) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = true
        )
    }

    /**
     * Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
     * Android 12 及以下仅需检查通知开关。
     */
    fun isNotificationRuntimeGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun isNotificationEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // ==================== 精确闹钟权限 ====================

    fun exactAlarm(): PermissionItem {
        val applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val granted = if (applicable) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
        return PermissionItem(
            type = PermissionType.EXACT_ALARM,
            titleRes = R.string.permission_exact_alarm,
            descRes = R.string.permission_exact_alarm_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable
        )
    }

    // ==================== 电池优化权限 ====================

    @SuppressLint("BatteryLife")
    fun battery(): PermissionItem {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        return PermissionItem(
            type = PermissionType.BATTERY,
            titleRes = R.string.permission_battery,
            descRes = R.string.permission_battery_desc,
            status = if (ignoring) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = true
        )
    }

    // ==================== Widget 状态 ====================

    fun widget(): PermissionItem {
        val added = isWidgetPlaced()
        return PermissionItem(
            type = PermissionType.WIDGET,
            titleRes = R.string.permission_widget,
            descRes = R.string.permission_widget_desc,
            status = if (added) PermissionStatus.ADDED else PermissionStatus.NOT_ADDED,
            applicable = true
        )
    }

    /**
     * 只信任系统 API：AppWidgetManager.getAppWidgetIds()
     * 不信任 UserPreferences 或其他任何本地缓存。
     */
    fun isWidgetPlaced(): Boolean {
        return try {
            val mgr = context.getSystemService(Context.APPWIDGET_SERVICE) as android.appwidget.AppWidgetManager
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, TaskWidgetProvider::class.java)
            )
            ids.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    // ==================== 国产系统自启动/后台运行 ====================

    fun autoStart(): PermissionItem? {
        if (!isChineseRom()) return null
        return PermissionItem(
            type = PermissionType.AUTO_START,
            titleRes = R.string.permission_autostart,
            descRes = R.string.permission_autostart_desc,
            status = PermissionStatus.NONE,
            applicable = true
        )
    }

    fun backgroundRun(): PermissionItem? {
        if (!isChineseRom()) return null
        return PermissionItem(
            type = PermissionType.BACKGROUND_RUN,
            titleRes = R.string.permission_background_run,
            descRes = R.string.permission_background_run_desc,
            status = PermissionStatus.NONE,
            applicable = true
        )
    }

    // ==================== 权限跳转（直达具体页） ====================

    /**
     * 为每种权限类型返回直达**具体权限页**的 Intent。
     * 绝不返回设置首页。
     * 对于无法直达的国产 ROM 权限，返回 null，由上层显示引导文字。
     */
    fun intentFor(type: PermissionType): Intent? = when (type) {
        PermissionType.NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

        PermissionType.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else null

        PermissionType.BATTERY -> Intent(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            pkgUri
        )

        PermissionType.WIDGET -> null // Widget 添加走系统 Pin 流程，不是设置页跳转

        PermissionType.AUTO_START -> autoStartIntent()

        PermissionType.BACKGROUND_RUN -> backgroundRunIntent()
    }

    /**
     * 通知运行时权限请求（Android 13+）。
     */
    fun notificationRuntimeRequestIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else null
    }

    /**
     * 精确闹钟直达 Intent（带包名）。
     */
    fun exactAlarmIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = pkgUri
            }
        } else null
    }

    /**
     * 电池优化直达 Intent（带包名）。
     */
    fun batteryIntent(): Intent = Intent(
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        pkgUri
    )

    /**
     * 应用详情设置页（通用，包含「允许创建小组件」等权限）。
     */
    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        pkgUri
    )

    // ==================== 厂商检测 ====================

    private fun isChineseRom(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        return mfr in setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "vivo", "meizu", "samsung")
    }

    private fun autoStartIntent(): Intent? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        val candidates = listOfNotNull(
            // 华为
            if (mfr == "huawei" || mfr == "honor")
                Intent("com.huawei.systemmanager.optimize.process.ProtectActivity").apply {
                    component = android.content.ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
            else null,
            // 小米/红米
            if (mfr == "xiaomi" || mfr == "redmi")
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    component = android.content.ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
            else null,
            // OPPO/realme
            if (mfr == "oppo" || mfr == "realme")
                Intent("com.oppo.safe.permission.startup.StartupSettingActivity")
                    .setComponent(android.content.ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupSettingActivity"))
            else null,
            // VIVO/iQOO
            if (mfr == "vivo" || mfr == "iqoo")
                Intent("com.vivo.permissionmanager.activity.BgStartUpManagerActivity").apply {
                    component = android.content.ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
            else null,
            // 默认：应用详情页
            appDetailsIntent()
        )
        return candidates.firstOrNull { intent ->
            runCatching { context.packageManager.resolveActivity(intent, 0) != null }
                .getOrDefault(false)
        }
    }

    private fun backgroundRunIntent(): Intent? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        val candidates = listOfNotNull(
            // 华为：应用启动管理
            if (mfr == "huawei" || mfr == "honor")
                Intent("com.huawei.systemmanager.optimize.process.AppStartSettingsActivity")
                    .setComponent(android.content.ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.AppStartSettingsActivity"))
            else null,
            // 小米：省电模式
            if (mfr == "xiaomi" || mfr == "redmi")
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else null,
            // OPPO：后台冻结
            if (mfr == "oppo" || mfr == "realme")
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else null,
            // VIVO：后台耗电管理
            if (mfr == "vivo" || mfr == "iqoo")
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else null,
            appDetailsIntent()
        )
        return candidates.firstOrNull { intent ->
            runCatching { context.packageManager.resolveActivity(intent, 0) != null }
                .getOrDefault(false)
        }
    }

    /**
     * 国产 ROM 自启动+后台运行引导文案。
     * 当无法直接跳转时，返回文字教程。
     */
    fun vendorGuideFor(type: PermissionType): String? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        return when {
            type == PermissionType.AUTO_START && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：设置 → 应用和服务 → 应用启动管理 → 找到 TaskFlow → 关闭自动管理 → 开启自动启动"

            type == PermissionType.AUTO_START && (mfr == "xiaomi" || mfr == "redmi") ->
                "小米/红米：安全中心 → 应用权限 → 自启动 → 找到 TaskFlow → 允许自启动"

            type == PermissionType.AUTO_START && (mfr == "oppo" || mfr == "realme") ->
                "OPPO/realme：设置 → 电池 → 应用耗电管理 → TaskFlow → 允许后台活动"

            type == PermissionType.AUTO_START && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：i管家 → 应用管理 → 自启动 → 开启 TaskFlow"

            type == PermissionType.BACKGROUND_RUN && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：设置 → 应用和服务 → 应用启动管理 → TaskFlow → 关闭自动管理 → 开启后台活动"

            type == PermissionType.BACKGROUND_RUN && (mfr == "xiaomi" || mfr == "redmi") ->
                "小米/红米：安全中心 → 省电策略 → TaskFlow → 无限后台"

            type == PermissionType.BACKGROUND_RUN && (mfr == "oppo" || mfr == "realme") ->
                "OPPO/realme：设置 → 电池 → 应用耗电管理 → TaskFlow → 允许后台活动"

            type == PermissionType.BACKGROUND_RUN && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：i管家 → 应用管理 → 后台管理 → 允许 TaskFlow 后台运行"

            type == PermissionType.WIDGET && isChineseRom() ->
                "国产 ROM 可能需要手动开启创建小组件权限：设置 → 应用和服务 → 权限管理 → 找到 TaskFlow → 开启「允许创建小组件」"

            else -> null
        }
    }

    fun vendorName(): String? =
        Build.MANUFACTURER?.lowercase()?.replaceFirstChar { it.uppercase() }
}
