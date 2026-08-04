package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Widget 能力检测。
 *
 * 关键修复：
 * - 华为/荣耀设备不信任 isRequestPinAppWidgetSupported，直接走手动引导
 * - 状态判断只使用 getAppWidgetIds()，不信任 UserPreferences
 */
object WidgetCapability {

    private const val TAG = "WidgetCapability"

    private val restrictedVendors = setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "vivo", "meizu")

    data class Report(
        val apiSupported: Boolean,
        val launcherSupported: Boolean,
        val widgetAlreadyPlaced: Boolean,
        val batteryOptimized: Boolean,
        val vendorRestricted: Boolean,
        val vendorName: String?
    ) {
        /**
         * 是否可以尝试自动 Pin。
         * 华为/小米等设备即使 isRequestPinAppWidgetSupported=true 也不可靠，
         * 直接跳过自动 Pin。
         */
        val canAttemptAutoPin: Boolean
            get() = apiSupported && launcherSupported &&
                !widgetAlreadyPlaced && !vendorRestricted

        val shouldShowManualGuide: Boolean
            get() = !apiSupported || !launcherSupported || vendorRestricted

        val blockingReason: String get() = when {
            widgetAlreadyPlaced -> "桌面已存在 TaskFlow 小组件，无需重复添加。"
            !apiSupported -> "系统版本过低（Android 8.0 以下），不支持自动添加小组件。"
            vendorRestricted -> "$vendorName 设备不支持自动添加，请手动添加小组件。"
            !launcherSupported -> "当前桌面启动器不支持自动添加小组件。"
            batteryOptimized -> "应用受电池优化限制，可能影响小组件回调。建议加入电池优化白名单。"
            else -> ""
        }
    }

    fun report(context: Context): Report {
        val apiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val launcherSupported = if (apiSupported) {
            try {
                AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            } catch (e: Exception) {
                false
            }
        } else false

        // 只信任系统真实状态
        val widgetAlreadyPlaced = try {
            val provider = ComponentName(context, TaskWidgetProvider::class.java)
            AppWidgetManager.getInstance(context).getAppWidgetIds(provider).isNotEmpty()
        } catch (e: Exception) {
            false
        }

        val batteryOptimized = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName).not()
        } catch (e: Exception) {
            false
        }

        val vendorName = Build.MANUFACTURER?.lowercase()
        val vendorRestricted = vendorName in restrictedVendors

        Log.d(TAG, "report: api=$apiSupported launcher=$launcherSupported " +
            "placed=$widgetAlreadyPlaced battery=$batteryOptimized " +
            "vendor=$vendorName restricted=$vendorRestricted " +
            "canAutoPin=${apiSupported && launcherSupported && !widgetAlreadyPlaced && !vendorRestricted}")

        return Report(
            apiSupported = apiSupported,
            launcherSupported = launcherSupported,
            widgetAlreadyPlaced = widgetAlreadyPlaced,
            batteryOptimized = batteryOptimized,
            vendorRestricted = vendorRestricted,
            vendorName = vendorName?.replaceFirstChar { it.uppercase() }
        )
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { Log.e(TAG, "Cannot open battery settings", it) }
    }

    fun openWidgetPicker(context: Context) {
        val candidates = listOf(
            Intent("android.appwidget.action.APPWIDGET_PICK"),
            Intent("com.huawei.android.launcher.action.APPWIDGET_PICK"),
            Intent("com.miui.launcher.action.APPWIDGET_PICK"),
            Intent("com.oppo.launcher.action.APPWIDGET_PICK")
        )
        val pm = context.packageManager
        val resolved = candidates.firstOrNull { intent ->
            runCatching {
                pm.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
        }
        runCatching {
            (resolved ?: Intent(Settings.ACTION_SETTINGS)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.let { context.startActivity(it) }
        }.onFailure { Log.e(TAG, "Cannot open widget picker", it) }
    }
}
