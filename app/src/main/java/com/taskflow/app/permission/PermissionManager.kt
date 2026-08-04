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
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.taskflow.app.R
import com.taskflow.app.widget.TaskWidgetProvider

private const val TAG = "PermissionManager"

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

    /** 应用 UID，部分国产 ROM 通知设置页需要此参数才能定位到本应用 */
    private val appUid: Int by lazy {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        }.getOrDefault(-1)
    }

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
     *
     * 关键修复：
     * - 通知权限：同时传 EXTRA_APP_PACKAGE + EXTRA_APP_UID，确保国产 ROM 能定位到本应用
     * - 精确闹钟：设置 data = pkgUri，直接定位到本应用而非全局列表
     * - 电池优化：使用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接请求
     * - 所有 Intent 都加 FLAG_ACTIVITY_NEW_TASK
     *
     * 对于无法直达的国产 ROM 权限（自启动/后台运行），返回 null，由上层显示引导文字。
     */
    fun intentFor(type: PermissionType): Intent? = when (type) {
        PermissionType.NOTIFICATION -> buildNotificationSettingsIntent()

        PermissionType.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = pkgUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else null

        PermissionType.BATTERY -> buildBatterySettingsIntent()

        PermissionType.WIDGET -> null

        PermissionType.AUTO_START -> autoStartIntent()

        PermissionType.BACKGROUND_RUN -> backgroundRunIntent()
    }

    /**
     * 通知设置页 Intent。
     * 同时传 package name 和 uid，最大程度兼容国产 ROM。
     */
    private fun buildNotificationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (appUid != -1) {
                // EXTRA_APP_UID 是隐藏 API，直接使用字符串值
                putExtra("android.app.extra.APP_UID", appUid)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 电池优化 Intent。
     * 优先使用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（直接弹出请求对话框），
     * 如果无法 resolve 则回退到 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS（列表页）。
     */
    @SuppressLint("BatteryLife")
    private fun buildBatterySettingsIntent(): Intent {
        // 方式1：直接请求忽略电池优化（弹出系统对话框，最精准）
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = pkgUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (canResolve(directRequest)) return directRequest

        // 方式2：电池优化设置列表页（带包名）
        val listPage = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (canResolve(listPage)) return listPage

        // 方式3：高优先级忽略电池优化设置
        val highPriority = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return highPriority
    }

    /**
     * 尝试启动 Intent，返回是否成功。
     * 如果 Intent 为 null 或无法 resolve，返回 false。
     */
    fun startIntent(type: PermissionType): Boolean {
        val intent = intentFor(type) ?: run {
            Log.w(TAG, "startIntent: intentFor($type) returned null")
            return false
        }
        if (!canResolve(intent)) {
            Log.w(TAG, "startIntent: cannot resolve intent for $type")
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startIntent: failed for $type", e)
            false
        }
    }

    /**
     * 检查 Intent 是否可以被系统处理。
     */
    fun canResolve(intent: Intent): Boolean {
        return try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 通知运行时权限请求（Android 13+）。
     */
    fun notificationRuntimeRequestIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            buildNotificationSettingsIntent()
        } else null
    }

    /**
     * 精确闹钟直达 Intent（带包名）。
     */
    fun exactAlarmIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = pkgUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else null
    }

    /**
     * 电池优化直达 Intent（带包名）。
     */
    fun batteryIntent(): Intent = buildBatterySettingsIntent()

    /**
     * 应用详情设置页（通用，包含「允许创建小组件」等权限）。
     */
    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        pkgUri
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    // ==================== 厂商检测 ====================

    private fun isChineseRom(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        return mfr in setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "vivo", "meizu", "samsung")
    }

    /**
     * 国产 ROM 自启动权限 Intent。
     * 只返回厂商专属页面的 Intent；如果无法直达，返回 null（不回退到应用详情页）。
     * 由上层显示文字引导。
     */
    private fun autoStartIntent(): Intent? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        val candidates = listOfNotNull(
            // 华为/荣耀：应用启动管理
            if (mfr == "huawei" || mfr == "honor")
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // 小米/红米：自启动管理
            if (mfr == "xiaomi" || mfr == "redmi")
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // OPPO/realme：启动管理
            if (mfr == "oppo" || mfr == "realme")
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // VIVO/iQOO：后台弹出活动管理
            if (mfr == "vivo" || mfr == "iqoo")
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null
        )
        // 只返回能 resolve 的厂商专属 Intent，不回退到应用详情页
        return candidates.firstOrNull { canResolve(it) }
    }

    /**
     * 国产 ROM 后台运行权限 Intent。
     * 只返回厂商专属页面的 Intent；如果无法直达，返回 null。
     */
    private fun backgroundRunIntent(): Intent? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        val candidates = listOfNotNull(
            // 华为/荣耀：应用启动管理（与自启动同一页面）
            if (mfr == "huawei" || mfr == "honor")
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // 小米/红米：电池优化（带包名）
            if (mfr == "xiaomi" || mfr == "redmi")
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkgUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // OPPO/realme：电池优化
            if (mfr == "oppo" || mfr == "realme")
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkgUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null,
            // VIVO/iQOO：后台耗电管理
            if (mfr == "vivo" || mfr == "iqoo")
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.abe",
                        "com.vivo.abe.FakeActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            else null
        )
        return candidates.firstOrNull { canResolve(it) }
    }

    /**
     * 国产 ROM 自启动+后台运行引导文案。
     * 当无法直接跳转时，返回文字教程。
     */
    fun vendorGuideFor(type: PermissionType): String? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        return when {
            type == PermissionType.AUTO_START && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：\n设置 → 应用和服务 → 应用启动管理\n找到 TaskFlow → 关闭自动管理\n→ 开启「自动启动」「关联启动」「后台活动」三个开关"

            type == PermissionType.AUTO_START && (mfr == "xiaomi" || mfr == "redmi") ->
                "小米/红米：\n安全中心（或手机管家）→ 应用权限 → 自启动\n找到 TaskFlow → 开启「允许自启动」"

            type == PermissionType.AUTO_START && (mfr == "oppo" || mfr == "realme") ->
                "OPPO/realme：\n设置 → 电池 → 更多电池设置 → 应用耗电管理\n找到 TaskFlow → 开启「允许自启动」和「允许后台运行」"

            type == PermissionType.AUTO_START && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：\ni管家 → 应用管理 → 权限管理 → 自启动\n找到 TaskFlow → 开启「允许自启动」"

            type == PermissionType.BACKGROUND_RUN && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：\n设置 → 应用和服务 → 应用启动管理\n找到 TaskFlow → 关闭自动管理\n→ 开启「后台活动」开关"

            type == PermissionType.BACKGROUND_RUN && (mfr == "xiaomi" || mfr == "redmi") ->
                "小米/红米：\n安全中心 → 电池 → 应用智能省电\n找到 TaskFlow → 设为「无限制」"

            type == PermissionType.BACKGROUND_RUN && (mfr == "oppo" || mfr == "realme") ->
                "OPPO/realme：\n设置 → 电池 → 更多 → 应用耗电管理\n找到 TaskFlow → 开启「允许后台运行」"

            type == PermissionType.BACKGROUND_RUN && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：\ni管家 → 应用管理 → 后台管理\n找到 TaskFlow → 允许后台运行"

            type == PermissionType.WIDGET && isChineseRom() ->
                "国产 ROM 可能需要手动开启创建小组件权限：\n设置 → 应用和服务 → 权限管理\n找到 TaskFlow → 开启「允许创建小组件」"

            else -> null
        }
    }

    fun vendorName(): String? =
        Build.MANUFACTURER?.lowercase()?.replaceFirstChar { it.uppercase() }
}
