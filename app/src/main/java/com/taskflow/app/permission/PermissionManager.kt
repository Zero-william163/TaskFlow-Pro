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
 * 统一权限管理器（v4 — 多级降级跳转链）。
 *
 * ==================== 核心承诺 ====================
 * 每项权限的跳转严格按"多级降级链"执行，**绝不允许最后落在系统设置首页**：
 *
 *   优先级 1  →  本应用对应权限的具体页面（带 package + uid）
 *   优先级 2  →  本应用详情页 ACTION_APPLICATION_DETAILS_SETTINGS（带 package）
 *   优先级 3  →  权限分类页（如全局精确闹钟页）
 *   终止条件：全部 resolveActivity 失败 → 返回 false，由 UI 层显示【操作路径文字引导】
 *
 * ==================================================
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

    // ==================== 多级降级跳转链（核心） ====================

    /**
     * 按优先级尝试跳转，返回是否成功启动了某个页面。
     * 若所有候选都失败，返回 false——UI 层应显示 vendorGuideFor() 文字引导。
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
                Log.d(TAG, "startIntent[$type]: jumped via ${intent.action}")
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
     * 关键改进（vs 旧版 intentFor）：
     * - 旧版只返回 1 个 Intent，resolveActivity 失败就直接返回 false
     * - 新版返回完整候选列表，逐个尝试，大幅提高跳转成功率
     *
     * 候选列表末尾**不允许**是系统设置首页（ACTION_SETTINGS），
     * 最差降级到应用详情页（带 package URI）。
     */
    @SuppressLint("BatteryLife")
    private fun buildJumpChain(type: PermissionType): List<Intent> {
        val pkg = context.packageName
        return when (type) {

            // —— 通知权限 ——
            PermissionType.NOTIFICATION -> listOf(
                // 1. 本应用通知设置页（+ uid，兼容国产 ROM）
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    if (appUid != -1) putExtra("android.app.extra.APP_UID", appUid)
                },
                // 2. 应用详情页（用户可以从这里进入通知设置）
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 精确闹钟 ——
            PermissionType.EXACT_ALARM -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    // 1. 本应用精确闹钟请求页（data = pkgUri 才能精准定位本应用）
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = pkgUri
                    } else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    // 2. 全局精确闹钟设置页（部分 ROM 会在顶部显示本应用条目）
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) else null,
                // 3. 应用详情页
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 电池优化 ——
            PermissionType.BATTERY -> listOf(
                // 1. 直接弹出忽略电池优化对话框（最精准，直接显示本应用）
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = pkgUri
                },
                // 2. 忽略电池优化列表页（带 pkgUri，部分 ROM 会高亮显示本应用）
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, pkgUri),
                // 3. 忽略电池优化列表页（不带 pkgUri 的 fallback）
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                // 4. 应用详情页
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— Widget ——
            PermissionType.WIDGET -> listOf(
                // Widget 走系统 Pin 流程，不是设置页跳转；这里只给应用详情页作为 fallback
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
            )

            // —— 国产 ROM 自启动 ——
            PermissionType.AUTO_START -> buildVendorJumpChain(VendorAction.AUTO_START)

            // —— 国产 ROM 后台运行 ——
            PermissionType.BACKGROUND_RUN -> buildVendorJumpChain(VendorAction.BATTERY_MANUAL)
        }
    }

    // ==================== 厂商专属 Intent 候选链 ====================

    private enum class VendorAction { AUTO_START, BATTERY_MANUAL }

    /**
     * 厂商专属权限的 Intent 候选链。
     *
     * 每个厂商提供多个候选 Component，逐个 resolveActivity 检查。
     * 最后降级到应用详情页（带 package URI），**绝不降级到设置首页**。
     */
    private fun buildVendorJumpChain(action: VendorAction): List<Intent> {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return emptyList()
        val list = mutableListOf<Intent>()

        when {
            // —— 华为/荣耀 ——
            mfr == "huawei" || mfr == "honor" -> {
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                    )
                }
            }

            // —— 小米/红米/POCO ——
            mfr == "xiaomi" || mfr == "redmi" || mfr == "poco" -> {
                if (action == VendorAction.AUTO_START) {
                    list += Intent("miui.intent.action.OP_AUTO_START").apply {
                        component = android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    }
                }
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", context.packageName)
                }
            }

            // —— OPPO/realme/OnePlus ——
            mfr == "oppo" || mfr == "realme" || mfr == "oneplus" -> {
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.startupapp.StartupAppListActivity"
                    )
                }
            }

            // —— VIVO/iQOO ——
            mfr == "vivo" || mfr == "iqoo" -> {
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }

            // —— 魅族 ——
            mfr == "meizu" -> {
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.meizu.safe",
                        "com.meizu.safe.security.SHOW_APPSEC"
                    )
                }
            }

            // —— 三星 ——
            mfr == "samsung" -> {
                list += Intent().apply {
                    component = android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"
                    )
                }
            }
        }

        // —— 所有厂商的共同最后降级：应用详情页（仍然比设置首页更精准！） ——
        list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
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

    // ==================== 厂商检测 ====================

    private fun isChineseRom(): Boolean {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return false
        return mfr in setOf(
            "huawei", "honor", "xiaomi", "redmi", "poco",
            "oppo", "realme", "oneplus", "vivo", "iqoo",
            "meizu", "samsung", "zte", "lenovo"
        )
    }

    /**
     * 国产 ROM 自启动+后台运行引导文案。
     * 当 [startIntent] 返回 false 时，由 UI 层调用此方法显示文字教程。
     */
    fun vendorGuideFor(type: PermissionType): String? {
        val mfr = Build.MANUFACTURER?.lowercase() ?: return null
        return when {
            type == PermissionType.AUTO_START && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：\n设置 → 应用和服务 → 应用启动管理\n找到 TaskFlow → 关闭自动管理\n→ 开启「自动启动」「关联启动」「后台活动」三个开关"

            type == PermissionType.AUTO_START && (mfr == "xiaomi" || mfr == "redmi" || mfr == "poco") ->
                "小米/红米/POCO：\n安全中心（或手机管家）→ 应用权限 → 自启动\n找到 TaskFlow → 开启「允许自启动」"

            type == PermissionType.AUTO_START && (mfr == "oppo" || mfr == "realme" || mfr == "oneplus") ->
                "OPPO/realme/一加：\n设置 → 电池 → 更多电池设置 → 应用耗电管理\n找到 TaskFlow → 开启「允许自启动」和「允许后台运行」"

            type == PermissionType.AUTO_START && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：\ni管家 → 应用管理 → 权限管理 → 自启动\n找到 TaskFlow → 开启「允许自启动」"

            type == PermissionType.AUTO_START && mfr == "meizu" ->
                "魅族：\n手机管家 → 权限管理 → 自启动管理\n找到 TaskFlow → 允许"

            type == PermissionType.AUTO_START && mfr == "samsung" ->
                "三星：\n设置 → 应用 → TaskFlow → 电池 → 后台使用限制 → 从不休眠"

            type == PermissionType.BACKGROUND_RUN && (mfr == "huawei" || mfr == "honor") ->
                "华为/荣耀：\n设置 → 应用和服务 → 应用启动管理\n找到 TaskFlow → 关闭自动管理\n→ 开启「后台活动」开关"

            type == PermissionType.BACKGROUND_RUN && (mfr == "xiaomi" || mfr == "redmi" || mfr == "poco") ->
                "小米/红米/POCO：\n安全中心 → 电池 → 应用智能省电\n找到 TaskFlow → 设为「无限制」"

            type == PermissionType.BACKGROUND_RUN && (mfr == "oppo" || mfr == "realme" || mfr == "oneplus") ->
                "OPPO/realme/一加：\n设置 → 电池 → 更多 → 应用耗电管理\n找到 TaskFlow → 开启「允许后台运行」"

            type == PermissionType.BACKGROUND_RUN && (mfr == "vivo" || mfr == "iqoo") ->
                "VIVO/iQOO：\ni管家 → 应用管理 → 后台管理\n找到 TaskFlow → 允许后台运行"

            type == PermissionType.BACKGROUND_RUN && mfr == "meizu" ->
                "魅族：\n设置 → 电量管理 → 应用电量管理\n→ TaskFlow → 待机耗电优化 → 关闭"

            type == PermissionType.BACKGROUND_RUN && mfr == "samsung" ->
                "三星：\n设置 → 电池和设备维护 → 电池 → 后台使用限制\n→ 将 TaskFlow 加入「从不休眠的应用」"

            type == PermissionType.WIDGET && isChineseRom() ->
                "国产 ROM 可能需要手动开启创建小组件权限：\n设置 → 应用和服务 → 权限管理\n找到 TaskFlow → 开启「允许创建小组件」\n\n然后长按桌面空白处 → 小组件 → 找到 TaskFlow → 拖拽到桌面"

            else -> null
        }
    }

    fun vendorName(): String? =
        Build.MANUFACTURER?.lowercase()?.replaceFirstChar { it.uppercase() }
}
