package com.taskflow.app.permission

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
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
import com.taskflow.app.notification.NotificationHelper

/**
 * 权限类型标识符。稳定的枚举，用于 UI 列表与持久化。
 *
 * 设计原则（v6 重构）：
 * - A 级（REQUIRED）：通知 / 通知渠道 / 精确闹钟 / 忽略电池优化 —— 核心提醒功能依赖
 * - B 级（RECOMMENDED）：悬浮窗 —— 全屏提醒降级方案
 * - 厂商专项（VENDOR）：自启动 / 电池手动管理 / 锁屏清理白名单 —— 无法系统检测，需手动确认
 *
 * 已移除（不应展示给用户）：
 * - FOREGROUND_SERVICE：OPSTR_RUN_ANY_IN_BACKGROUND 为 @SystemApi，非用户可见权限
 * - WIDGET：Widget 状态独立管理（见 WidgetHelper / WidgetCapability），不混入权限列表
 */
enum class PermissionType {
    NOTIFICATION,           // 通知总开关
    CHANNEL_ALARM,          // 闹钟通知渠道（reminders 渠道单独检测）
    EXACT_ALARM,            // 精确闹钟
    BATTERY,                // 忽略电池优化
    OVERLAY,                // 悬浮窗
    AUTO_START,             // 厂商自启动
    BACKGROUND_RUN,         // 厂商电池手动管理
    LOCK_SCREEN             // 厂商锁屏清理白名单
}

enum class PermissionStatus { GRANTED, DENIED, ADDED, NOT_ADDED, MANUAL, NONE }

data class PermissionItem(
    val type: PermissionType,
    /** 权限等级：A级必需 / B级推荐 / 厂商专项 */
    val level: PermissionLevel,
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
 * 统一权限管理器（v6 — 大型商业 APP 标准重构）。
 *
 * ==================== 核心承诺 ====================
 * 每项权限的跳转严格按"多级降级链"执行，**绝不允许最后落在系统设置首页**：
 *
 *   优先级 1  →  本应用对应权限的具体页面（带 package + uid + channelId）
 *   优先级 2  →  本应用详情页 ACTION_APPLICATION_DETAILS_SETTINGS（带 package）
 *   优先级 3  →  权限分类页（如 ACTION_MANAGE_OVERLAY_PERMISSION 全局页）
 *   终止条件：全部 resolveActivity 失败 → 返回 false，由 UI 层显示【操作路径文字引导】
 *
 * ==================== v6 重构要点 ====================
 * - 引入 [PermissionLevel] 三级分类：REQUIRED(A级) / RECOMMENDED(B级) / VENDOR(厂商专项)
 * - 移除 FOREGROUND_SERVICE：OPSTR_RUN_ANY_IN_BACKGROUND 为 @SystemApi，非用户可见
 * - 移除 WIDGET：Widget 状态独立管理（见 WidgetHelper / WidgetCapability）
 * - 全链路接入 [PermissionLogger] 统一日志体系
 * - 全部检测只信任系统 API，不信任任何本地缓存
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
        autoStart(),
        backgroundRun(),
        lockScreen()
    )

    /** A 级（必需）权限：核心提醒功能依赖 */
    fun requiredItems(): List<PermissionItem> = all().filter {
        it.level == PermissionLevel.REQUIRED
    }

    /** B 级（推荐）权限：提升稳定性 */
    fun recommendedItems(): List<PermissionItem> = all().filter {
        it.level == PermissionLevel.RECOMMENDED
    }

    /** 厂商专项权限：无法系统检测，需手动确认 */
    fun vendorItems(): List<PermissionItem> = all().filter {
        it.level == PermissionLevel.VENDOR
    }

    // ==================== 通知权限 ====================

    fun notification(): PermissionItem {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        PermissionLogger.logCheck(PermissionType.NOTIFICATION, enabled)
        return PermissionItem(
            type = PermissionType.NOTIFICATION,
            level = PermissionLevel.REQUIRED,
            titleRes = R.string.permission_notifications,
            descRes = R.string.permission_notifications_desc,
            status = if (enabled) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = true,
            badge = "A级"
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
        PermissionLogger.logCheck(PermissionType.CHANNEL_ALARM, granted)
        return PermissionItem(
            type = PermissionType.CHANNEL_ALARM,
            level = PermissionLevel.REQUIRED,
            titleRes = R.string.permission_channel_alarm,
            descRes = R.string.permission_channel_alarm_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable,
            badge = "A级"
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
        PermissionLogger.logCheck(PermissionType.EXACT_ALARM, granted)
        return PermissionItem(
            type = PermissionType.EXACT_ALARM,
            level = PermissionLevel.REQUIRED,
            titleRes = R.string.permission_exact_alarm,
            descRes = R.string.permission_exact_alarm_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable,
            badge = "A级"
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
        PermissionLogger.logCheck(PermissionType.BATTERY, ignoring)
        return PermissionItem(
            type = PermissionType.BATTERY,
            level = PermissionLevel.REQUIRED,
            titleRes = R.string.permission_battery,
            descRes = R.string.permission_battery_desc,
            status = if (ignoring) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = true,
            badge = "A级"
        )
    }

    fun isBatteryIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ==================== 悬浮窗权限（B 级推荐） ====================

    fun overlay(): PermissionItem {
        val applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val granted = if (applicable) Settings.canDrawOverlays(context) else true
        PermissionLogger.logCheck(PermissionType.OVERLAY, granted)
        return PermissionItem(
            type = PermissionType.OVERLAY,
            level = PermissionLevel.RECOMMENDED,
            titleRes = R.string.permission_overlay,
            descRes = R.string.permission_overlay_desc,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            applicable = applicable,
            badge = "B级"
        )
    }

    fun isOverlayGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 检测悬浮窗权限是否被 Android 13+ Restricted Settings 机制限制。
     *
     * 当 APK 通过单文件侧载（非官方应用商店）安装时，系统会将
     * SYSTEM_ALERT_WINDOW、ACCESSIBILITY 等敏感权限标记为「受限」，
     * 在设置页显示为灰色开关。
     *
     * 解决方案：引导用户前往「允许受限制的设置」页面解封。
     *
     * 检测逻辑：
     * 1. Android 13+ 且未授予悬浮窗权限
     * 2. 通过 AppOpsManager.checkOpNoThrow() 检查 OPSTR_SYSTEM_ALERT_WINDOW 状态
     * 3. 如果状态为 MODE_DEFAULT（未授权且受限），则判定为受限
     */
    fun isOverlayRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        // 如果已有悬浮窗权限，说明不受限制
        if (Settings.canDrawOverlays(context)) return false
        // 检测是否处于受限状态
        return try {
            val aom = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = aom.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(),
                context.packageName
            )
            // MODE_DEFAULT = 0 通常表示未授权且受限
            mode == android.app.AppOpsManager.MODE_DEFAULT
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 跳转到应用详情页，用于解决 Android 13+ Restricted Settings（权限变灰）。
     *
     * 关键设计：**不直接跳转 ACTION_MANAGE_OVERLAY_PERMISSION**。
     * 因为侧载应用的悬浮窗开关在那一页是灰色的，用户无法点击，跳过去毫无意义。
     *
     * 正确做法：跳转到【应用信息】页，引导用户：
     * 1. 点击右上角三点菜单 (⋮)
     * 2. 选择「允许受限制的设置」
     * 3. 返回后再开启悬浮窗开关
     *
     * UI 层应在调用此方法前先弹出 [showRestrictedSettingsGuide] 步骤引导 Dialog。
     */
    fun jumpToRestrictedSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PermissionLogger.logJumpSuccess(PermissionType.OVERLAY, intent)
            true
        } catch (e: Throwable) {
            PermissionLogger.logJumpFail(PermissionType.OVERLAY, Intent(), e)
            false
        }
    }

    // ==================== 国产系统厂商专项（VENDOR） ====================

    fun autoStart(): PermissionItem? {
        if (!isChineseRom()) return null
        val confirmed = isVendorConfirmed(PermissionType.AUTO_START)
        PermissionLogger.logCheck(PermissionType.AUTO_START, confirmed, "manual")
        return PermissionItem(
            type = PermissionType.AUTO_START,
            level = PermissionLevel.VENDOR,
            titleRes = R.string.permission_autostart,
            descRes = R.string.permission_autostart_desc,
            status = if (confirmed) PermissionStatus.MANUAL else PermissionStatus.NONE,
            applicable = true,
            badge = vendorBadge()
        )
    }

    fun backgroundRun(): PermissionItem? {
        if (!isChineseRom()) return null
        val confirmed = isVendorConfirmed(PermissionType.BACKGROUND_RUN)
        PermissionLogger.logCheck(PermissionType.BACKGROUND_RUN, confirmed, "manual")
        return PermissionItem(
            type = PermissionType.BACKGROUND_RUN,
            level = PermissionLevel.VENDOR,
            titleRes = R.string.permission_background_run,
            descRes = R.string.permission_background_run_desc,
            status = if (confirmed) PermissionStatus.MANUAL else PermissionStatus.NONE,
            applicable = true,
            badge = vendorBadge()
        )
    }

    fun lockScreen(): PermissionItem? {
        if (!isChineseRom()) return null
        val confirmed = isVendorConfirmed(PermissionType.LOCK_SCREEN)
        PermissionLogger.logCheck(PermissionType.LOCK_SCREEN, confirmed, "manual")
        return PermissionItem(
            type = PermissionType.LOCK_SCREEN,
            level = PermissionLevel.VENDOR,
            titleRes = R.string.permission_lock_screen,
            descRes = R.string.permission_lock_screen_desc,
            status = if (confirmed) PermissionStatus.MANUAL else PermissionStatus.NONE,
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
        PermissionLogger.logManualConfirm(type, confirmed)
    }

    // ==================== 多级降级跳转链（核心） ====================

    /**
     * 按优先级尝试跳转，返回是否成功启动了某个页面。
     * 若所有候选都失败，返回 false——UI 层应显示 [vendorGuideFor] 文字引导。
     *
     * **绝不跳转系统设置首页。**
     */
    fun startIntent(type: PermissionType): Boolean {
        PermissionLogger.logClick(type)
        val candidates = buildJumpChain(type)
        for (intent in candidates) {
            val resolved = canResolve(intent)
            PermissionLogger.logResolve(type, intent, resolved)
            if (!resolved) continue
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                PermissionLogger.logJumpSuccess(type, intent)
                return true
            } catch (e: Throwable) {
                PermissionLogger.logJumpFail(type, intent, e)
            }
        }
        PermissionLogger.logFallbackToGuide(type, candidates.size)
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

            // —— 悬浮窗 ——
            // 优先级 1：直达悬浮窗权限管理页（带 pkgUri）
            //           如果检测到 Restricted Settings，会先触发系统受限对话框
            // 优先级 2：不带包名的全局悬浮窗管理页
            // 优先级 3：应用详情页（用户需手动找权限）
            PermissionType.OVERLAY -> run {
                val chain = mutableListOf<Intent>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    chain += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                    chain += Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                }
                chain += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                chain
            }

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
     * Widget 引导由 [WidgetCapability] 独立调用 PermissionGuideData.widgetPath。
     */
    fun vendorGuideFor(type: PermissionType): String? {
        val guide = PermissionGuideData.forCurrent()
        return when (type) {
            PermissionType.AUTO_START -> guide.autoStartPath
            PermissionType.BACKGROUND_RUN -> guide.batteryManualPath
            PermissionType.LOCK_SCREEN -> guide.lockScreenWhiteListPath
            else -> null
        }
    }

    /** Widget 厂商操作路径引导（独立管理，不混入权限列表） */
    fun widgetVendorGuide(): String? =
        if (isChineseRom()) PermissionGuideData.forCurrent().widgetPath else null
}
