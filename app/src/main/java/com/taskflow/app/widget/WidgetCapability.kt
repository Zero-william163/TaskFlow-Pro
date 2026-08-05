package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.taskflow.app.permission.PermissionManager

/**
 * Widget 能力与引导工具。
 *
 * 设计原则：
 * 1. 状态只信任系统 API（AppWidgetManager / PowerManager），不信任任何本地缓存
 * 2. 引导文案对所有国产 ROM 给出可操作的文字教程
 * 3. 所有跳转都直达**具体权限页**，绝不返回设置首页
 */
object WidgetCapability {

    private val restrictedVendors = setOf(
        "huawei", "honor", "xiaomi", "redmi", "oppo", "realme", "vivo", "iqoo", "meizu"
    )

    data class WidgetReport(
        val canAutoPin: Boolean,
        val alreadyPlaced: Boolean,
        val vendorRestricted: Boolean,
        val vendorName: String?,
        val launcherSupported: Boolean,
        val batteryOptimized: Boolean
    ) {
        /** 派生字段：是否可以尝试 requestPinAppWidget */
        val canAttemptAutoPin: Boolean get() = canAutoPin && !alreadyPlaced

        /** 派生字段：是否需要手动引导 */
        val needsManualGuide: Boolean get() = vendorRestricted || !launcherSupported

        /** 兼容 SettingsScreen 的字段名 */
        val widgetAlreadyPlaced: Boolean get() = alreadyPlaced

        /**
         * 当无法自动添加时，返回给用户的阻塞原因文案。
         * 优先使用厂商特定引导，否则返回通用提示。
         */
        fun blockingReason(context: Context): String {
            val pm = PermissionManager(context)
            return when {
                alreadyPlaced -> "桌面已存在小组件，无需重复添加"
                vendorRestricted -> {
                    pm.widgetVendorGuide()
                        ?: "${vendorName ?: "当前"} 设备可能需要先开启创建小组件权限"
                }
                !launcherSupported -> "当前桌面启动器不支持自动添加小组件，请手动添加：长按桌面 → 小组件 → 找到 TaskFlow → 拖拽到桌面"
                batteryOptimized -> "电池优化可能影响小组件，建议加入白名单：设置 → 电池 → 找到 TaskFlow → 允许后台活动"
                else -> "无法自动添加，请手动添加：长按桌面 → 小组件 → 找到 TaskFlow → 拖拽到桌面"
            }
        }
    }

    fun report(context: Context): WidgetReport {
        val apiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val launcherSupported = if (apiSupported) {
            try {
                AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            } catch (_: Exception) {
                false
            }
        } else false

        val alreadyPlaced = try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java))
                .isNotEmpty()
        } catch (_: Exception) {
            false
        }

        val batteryIgnored = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
        val batteryOptimized = !batteryIgnored

        val vendorName = Build.MANUFACTURER?.lowercase()
        val vendorRestricted = vendorName in restrictedVendors

        return WidgetReport(
            canAutoPin = apiSupported && launcherSupported && !vendorRestricted,
            alreadyPlaced = alreadyPlaced,
            vendorRestricted = vendorRestricted,
            vendorName = vendorName?.replaceFirstChar { it.uppercase() },
            launcherSupported = launcherSupported,
            batteryOptimized = batteryOptimized
        )
    }

    /**
     * 打开应用详情页（包含「允许创建小组件」等权限开关）。
     */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    /**
     * 打开本应用的权限页（应用详情页的权限子页在很多 ROM 上无法直达，
     * 因此这里采用应用详情页 + 说明文字的组合方案）。
     */
    fun openAppPermissionSettings(context: Context) {
        openAppDetails(context)
    }

    /**
     * 直达电池优化页面（带包名，直接定位到本应用）。
     */
    fun openBatterySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    /**
     * 构建 Widget 引导文案（由上层 UI 调用）。
     */
    fun guideText(report: WidgetReport, context: Context): String =
        report.blockingReason(context)
}
