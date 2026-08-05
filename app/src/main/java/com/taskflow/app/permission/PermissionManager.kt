package com.taskflow.app.permission

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.taskflow.app.R
import com.taskflow.app.notification.NotificationHelper
import com.taskflow.app.widget.TaskWidgetProvider

private const val TAG = "PermissionManager"

/**
 * 权限类型标识符。稳定的枚举，用于 UI 列表与持久化。
 *
 * 移植自 Countdown PermissionChecker v4：
 * - 关键权限：通知 / 通知渠道 / 精确闹钟 / 忽略电池优化
 * - 建议权限：悬浮窗 / 前台服务 AppOps
 * - 厂商专项：自启动 / 电池手动管理 / 锁屏清理白名单
 * - Widget 放置状态
 */
enum class PermissionType {
    NOTIFICATION,           // 通知总开关
    CHANNEL_ALARM,          // 闹钟通知渠道（reminders 渠道单独检测）
    EXACT_ALARM,            // 精确闹钟
    BATTERY,                // 忽略电池优化
    OVERLAY,                // 悬浮窗
    FOREGROUND_SERVICE,     // 前台服务 AppOps
    WIDGET,                 // Widget 放置
    AUTO_START,             // 厂商自启动
    BACKGROUND_RUN,         // 厂商电池手动管理
    LOCK_SCREEN             // 厂商锁屏清理白名单
}

enum class PermissionStatus { GRANTED, DENIED, ADDED, NOT_ADDED, MANUAL, NONE }

data class PermissionItem(
    val type: PermissionType,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val status: PermissionStatus,
    val applicable: Boolean,
    val badge: String? = null,
    @StringRes val actionRes: Int = R.string.permission_open_settings
) {
    val isOk: Boolean
        get() = status == PermissionStatus.GRANTED ||
            status == PermissionStatus.ADDED ||
            status == PermissionStatus.MANUAL // 厂商专项由用户手动确认
}

/**
 * 统一权限管理器（v5 — 完整移植自 Countdown PermissionChecker v4）。
 *
 * ==================== 核心承诺 ====================
 * 每项权限的跳转严格按"多级降级链"执行，**绝不允许最后落在系统设置首页**：
 *
 *   优先级 1  →  本应用对应权限的具体页面（带 package + uid + channelId）
 *   优先级 2  →  本应用详情页 ACTION_APPLICATION_DETAILS_SETTINGS（带 package）
 *   优先级 3  →  权限分类页（如 ACTION_MANAGE_OVERLAY_PERMISSION 全局页）
 *   终止条件：全部 resolveActivity 失败 → 返回 false，由 UI 层显示【操作路径文字引导】
 *
 * 相比 v4 的增强：
 * - 新增 CHANNEL_ALARM：直达具体通知渠道设置页（带 channelId）
 * - 新增 OVERLAY：直达 ACTION_MANAGE_OVERLAY_PERMISSION（带 pkgUri）
 * - 新增 FOREGROUND_SERVICE：检测 OPSTR_RUN_ANY_IN_BACKGROUND
 * - 新增 LOCK_SCREEN：锁屏清理白名单厂商专项
 * - vendorGuideFor 全面改用 PermissionGuideData.forCurrent()（12 品牌 × 4 类）
 * ==================================================
 */
class PermissionManager(private val context: Context) {

    companion object {
        const val RC_NOTIFICATION = 1001
        private const val PREFS = "permission_confirmations_v5"
        private const val KEY_PREFIX = "confirmed_"
    }

    private val pkgUri: Uri by lazy { Uri.parse("package:${context.packageName}") }

    /** 应用 UID，部分国产 ROM 通知设置页需要此参数才能定位到本应用 */
    private val appUid: Int by lazy {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        }.getOrDefault(-1)
    }

    /** 闹钟提醒渠道 ID（与 NotificationHelper 保持一致） */
    private val alarmChannelId: String get() = NotificationHelper.CHANNEL_REMINDER

    fun all(): List<PermissionItem> = listOfNotNull(
        notification(),
        channelAlarm(),
        exactAlarm(),
        battery(),
        overlay(),
        foregroundService(),
        autoStart(),
        backgroundRun(),
        lockScreen()
    )

    // ==================== 通知权限 ====================

    fun notification(): PermissionItem {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return PermissionItem(
            type = PermissionType.NOTIFICATION,
            titleRes = R.string.permission_notifications,
            descRes = R.string.permission_notifications_desc,
            status = if (enabled) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = true,
            badge = "必需"
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

    // ==================== 闹钟通知渠道（关键改进：单独检测 reminders 渠道） ====================

    fun channelAlarm(): PermissionItem {
        val applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val granted = if (applicable) isAlarmChannelEnabled() else isNotificationEnabled()
        return PermissionItem(
            type = PermissionType.CHANNEL_ALARM,
            titleRes = R.string.permission_channel_alarm,
            descRes = R.string.permission_channel_alarm_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable,
            badge = "必需"
        )
    }

    fun isAlarmChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return isNotificationEnabled()
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch: NotificationChannel? = nm.getNotificationChannel(alarmChannelId)
            ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
        } catch (_: Throwable) {
            isNotificationEnabled()
        }
    }

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
            applicable = applicable,
            badge = "必需"
        )
    }

    fun isExactAlarmGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
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
            applicable = true,
            badge = "必需"
        )
    }

    fun isBatteryIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ==================== 悬浮窗权限（建议） ====================

    fun overlay(): PermissionItem {
        val applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val granted = if (applicable) Settings.canDrawOverlays(context) else true
        return PermissionItem(
            type = PermissionType.OVERLAY,
            titleRes = R.string.permission_overlay,
            descRes = R.string.permission_overlay_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable
        )
    }

    fun isOverlayGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    // ==================== 前台服务 AppOps（建议） ====================

    fun foregroundService(): PermissionItem {
        val applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val granted = if (applicable) isForegroundServiceAllowed() else true
        return PermissionItem(
            type = PermissionType.FOREGROUND_SERVICE,
            titleRes = R.string.permission_foreground_service,
            descRes = R.string.permission_foreground_service_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable
        )
    }

    fun isForegroundServiceAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = aom.unsafeCheckOpNoThrow(
                // OPSTR_RUN_ANY_IN_BACKGROUND 在 SDK 34 android.jar 中未暴露（@SystemApi），
                // 直接使用其字符串值 "android:run_any_in_background"
                "android:run_any_in_background",
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            true // AppOps 检测出错时，视为已允许（避免误报）
        }
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
            val ids = mgr.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java))
            ids.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== 国产系统厂商专项 ====================

    fun autoStart(): PermissionItem? {
        if (!isChineseRom()) return null
        return PermissionItem(
            type = PermissionType.AUTO_START,
            titleRes = R.string.permission_autostart,
            descRes = R.string.permission_autostart_desc,
            status = if (isVendorConfirmed(PermissionType.AUTO_START))
                PermissionStatus.MANUAL else PermissionStatus.NONE,
            applicable = true,
            badge = vendorBadge()
        )
    }

    fun backgroundRun(): PermissionItem? {
        if (!isChineseRom()) return null
        return PermissionItem(
            type = PermissionType.BACKGROUND_RUN,
            titleRes = R.string.permission_background_run,
            descRes = R.string.permission_background_run_desc,
            status = if (isVendorConfirmed(PermissionType.BACKGROUND_RUN))
                PermissionStatus.MANUAL else PermissionStatus.NONE,
            applicable = true,
            badge = vendorBadge()
        )
    }

    fun lockScreen(): PermissionItem? {
        if (!isChineseRom()) return null
        return PermissionItem(
            type = PermissionType.LOCK_SCREEN,
            titleRes = R.string.permission_lock_screen,
            descRes = R.string.permission_lock_screen_desc,
            status = if (isVendorConfirmed(PermissionType.LOCK_SCREEN))
                PermissionStatus.MANUAL else PermissionStatus.NONE,
            applicable = true,
            badge = vendorBadge()
        )
    }

    // ==================== 厂商确认存储（让用户可标记已开启） ====================

    fun isVendorConfirmed(type: PermissionType): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX + type.name, false)

    fun setVendorConfirmed(type: PermissionType, confirmed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFIX + type.name, confirmed).apply()
    }

    // ==================== 多级降级跳转链（核心） ====================

    /**
     * 按优先级尝试跳转，返回是否成功启动了某个页面。
     * 若所有候选都失败，返回 false——UI 层应显示 [vendorGuideFor] 文字引导。
     *
     * **绝不跳转系统设置首页。**
     */
    fun startIntent(type: PermissionType): Boolean {
        val candidates = buildJumpChain(type)
        for (intent in candidates) {
            if (!canResolve(intent)) continue
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "startIntent[$type]: jumped via ${intent.action ?: intent.component}")
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "startIntent[$type]: candidate ${intent.action} failed", e)
            }
        }
        Log.w(TAG, "startIntent[$type]: all ${candidates.size} candidates exhausted, fallback to text guide")
        return false
    }

    /**
     * 为每种权限类型构建按优先级排序的 Intent 候选列表。
     *
     * 候选列表末尾**不允许**是系统设置首页（ACTION_SETTINGS），
     * 最差降级到应用详情页（带 package URI）。
     */
    @SuppressLint("BatteryLife")
    private fun buildJumpChain(type: PermissionType): List<Intent> {
        val pkg = context.packageName
        return when (type) {

            // —— 通知总开关 ——
            PermissionType.NOTIFICATION -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    if (appUid != -1) putExtra("android.app.extra.APP_UID", appUid)
                },
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 闹钟通知渠道（最精准：直达 reminders 渠道详情页） ——
            PermissionType.CHANNEL_ALARM -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        putExtra(Settings.EXTRA_CHANNEL_ID, alarmChannelId)
                    } else null,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    if (appUid != -1) putExtra("android.app.extra.APP_UID", appUid)
                },
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 精确闹钟 ——
            PermissionType.EXACT_ALARM -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = pkgUri
                    } else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) else null,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 电池优化 ——
            PermissionType.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = pkgUri
                },
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkgUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 悬浮窗（直接弹允许悬浮窗对话框，最精准） ——
            PermissionType.OVERLAY -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri) else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) else null,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 前台服务 AppOps（无公开直达 action，应用详情页为最优入口） ——
            PermissionType.FOREGROUND_SERVICE -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— Widget ——
            PermissionType.WIDGET -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 国产 ROM 自启动 ——
            PermissionType.AUTO_START -> buildVendorJumpChain(VendorAction.AUTO_START)

            // —— 国产 ROM 电池手动管理 ——
            PermissionType.BACKGROUND_RUN -> buildVendorJumpChain(VendorAction.BATTERY_MANUAL)

            // —— 国产 ROM 锁屏清理白名单 ——
            PermissionType.LOCK_SCREEN -> buildVendorJumpChain(VendorAction.LOCKSCREEN)
        }
    }

    // ==================== 厂商专属 Intent 候选链 ====================

    private enum class VendorAction { AUTO_START, BATTERY_MANUAL, LOCKSCREEN }

    /**
     * 厂商专属权限的 Intent 候选链。
     *
     * 每个厂商提供多个候选 Component，逐个 resolveActivity 检查。
     *
     * 关键设计：**不添加应用详情页 fallback**。
     * 如果厂商 Intent 全部失败，startIntent 返回 false，
     * 由 UI 层显示 PermissionGuideData 中的文字引导，
     * 而不是跳回一个"看起来像主设置"的应用详情页。
     */
    private fun buildVendorJumpChain(action: VendorAction): List<Intent> {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return emptyList()
        val list = mutableListOf<Intent>()

        when {
            // —— 华为/荣耀 ——
            mfr == "huawei" || mfr == "honor" -> {
                list += Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
                list += Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                    )
                }
            }

            // —— 小米/红米/POCO ——
            mfr == "xiaomi" || mfr == "redmi" || mfr == "poco" -> {
                if (action == VendorAction.AUTO_START) {
                    list += Intent("miui.intent.action.OP_AUTO_START").apply {
                        component = ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }
                }
                list += Intent().apply {
                    component = ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", context.packageName)
                }
            }

            // —— OPPO/realme/OnePlus ——
            mfr == "oppo" || mfr == "realme" || mfr == "oneplus" -> {
                list += Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
                list += Intent().apply {
                    component = ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.startupapp.StartupAppListActivity"
                    )
                }
            }

            // —— VIVO/iQOO ——
            mfr == "vivo" || mfr == "iqoo" -> {
                list += Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }

            // —— 魅族 ——
            mfr == "meizu" -> {
                list += Intent().apply {
                    component = ComponentName(
                        "com.meizu.safe",
                        "com.meizu.safe.security.SHOW_APPSEC"
                    )
                }
            }

            // —— 三星 ——
            mfr == "samsung" -> {
                list += Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"
                    )
                }
            }
        }

        // 不添加应用详情页 fallback——全部失败时返回空列表，让 startIntent 返回 false
        // 由 UI 层显示文字引导
        return list
    }

    // ==================== 兼容旧 API（保留向后兼容） ====================

    /**
     * 返回第一个可 resolve 的 Intent（兼容旧调用方）。
     * 推荐使用 [startIntent] 替代。
     */
    fun intentFor(type: PermissionType): Intent? =
        buildJumpChain(type).firstOrNull { canResolve(it) }

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
            buildJumpChain(PermissionType.NOTIFICATION).firstOrNull()
        } else null
    }

    /**
     * 应用详情设置页（通用，包含「允许创建小组件」等权限）。
     */
    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        pkgUri
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    // ==================== 厂商检测与文案 ====================

    private fun isChineseRom(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        return mfr in setOf(
            "huawei", "honor", "xiaomi", "redmi", "poco", "blackshark",
            "oppo", "realme", "oneplus", "coloros",
            "vivo", "iqoo", "meizu", "flyme",
            "samsung", "zte", "lenovo", "motorola", "moto"
        )
    }

    fun vendorName(): String = when (Build.MANUFACTURER?.lowercase()) {
        "huawei" -> "华为"
        "honor" -> "荣耀"
        "xiaomi", "redmi", "poco", "blackshark" -> "小米"
        "oppo", "realme", "oneplus", "coloros" -> "OPPO"
        "vivo", "iqoo" -> "VIVO"
        "meizu", "flyme" -> "魅族"
        "samsung" -> "三星"
        "lenovo", "motorola", "moto" -> "Moto/联想"
        "sony" -> "索尼"
        else -> Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "当前"
    }

    private fun vendorBadge(): String? = if (isChineseRom()) vendorName() else null

    /**
     * 国产 ROM 操作路径文字引导。
     * 当 [startIntent] 返回 false 时，由 UI 层调用此方法显示文字教程。
     *
     * 完整移植自 Countdown PermissionGuideData：12 品牌 × 4 类权限。
     */
    fun vendorGuideFor(type: PermissionType): String? {
        val guide = PermissionGuideData.forCurrent()
        return when (type) {
            PermissionType.AUTO_START -> guide.autoStartPath
            PermissionType.BACKGROUND_RUN -> guide.batteryManualPath
            PermissionType.LOCK_SCREEN -> guide.lockScreenWhiteListPath
            PermissionType.WIDGET -> if (isChineseRom()) guide.widgetPath else null
            else -> null
        }
    }
}
