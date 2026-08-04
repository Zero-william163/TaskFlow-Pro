package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

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
        val canAttemptAutoPin: Boolean
            get() = apiSupported && launcherSupported &&
                !widgetAlreadyPlaced && !vendorRestricted

        val shouldShowPermissionGuide: Boolean
            get() = !apiSupported || !launcherSupported || vendorRestricted

        val blockingReason: String get() = when {
            widgetAlreadyPlaced -> "桌面已存在 TaskFlow 小组件，无需重复添加。"
            !apiSupported -> "系统版本过低（Android 8.0 以下），不支持添加小组件。"
            vendorRestricted -> "$vendorName 设备可能需要手动开启创建小组件权限。"
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

    /**
     * 跳转到应用系统设置页，用户可在此开启「允许创建小组件」等权限。
     * 华为/小米/OPPO/VIVO 等国产ROM 将此开关放在应用信息 → 权限 → 更多权限中。
     */
    fun openAppPermissionSettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { Log.e(TAG, "Cannot open app settings", it) }
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
}
