package com.taskflow.app.widget

import android.app.AppWidgetManager
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
 * Pre-flight checks for widget creation. The user spec reports that
 * "创建小组件 → 没有任何反应" on certain devices. Root cause is almost always
 * one of:
 *
 *  1. The current launcher does not implement `requestPinAppWidget`
 *     (Huawei EMUI / HarmonyOS, older MIUI, some AOSP forks).
 *  2. The app is under battery optimization → background broadcast that
 *     backs the pin callback is delayed/dropped.
 *  3. `isRequestPinAppWidgetSupported` lies on some OEM ROMs (returns true
 *     but the launcher has no UI to honor it).
 *
 * This helper centralises those checks so both the onboarding screen and
 * the in-app "add widget" flow can surface a clear, actionable reason
 * instead of silently doing nothing.
 */
object WidgetCapability {

    private const val TAG = "WidgetCapability"

    /** A vendor known to ship non-standard launcher pinning behaviour. */
    private val restrictedVendors = setOf("huawei", "honor", "xiaomi", "redmi", "oppo", "vivo", "meizu")

    data class Report(
        val apiSupported: Boolean,           // API >= 26 (O)
        val launcherSupported: Boolean,      // isRequestPinAppWidgetSupported
        val widgetAlreadyPlaced: Boolean,    // at least one widget id exists
        val batteryOptimized: Boolean,       // true = app is restricted
        val vendorRestricted: Boolean,       // Huawei/Xiaomi/etc. flag
        val vendorName: String?
    ) {
        /** Overall: can we attempt auto-pin? */
        val canAttemptAutoPin: Boolean get() = apiSupported && launcherSupported && !widgetAlreadyPlaced
        /** Should we skip auto-pin and go straight to manual instructions? */
        val shouldShowManualGuide: Boolean get() = !apiSupported || !launcherSupported || vendorRestricted
        /** A human-readable reason shown in the dialog. */
        val blockingReason: String get() = when {
            widgetAlreadyPlaced -> "桌面已存在 TaskFlow 小组件，无需重复添加。"
            !apiSupported -> "系统版本过低（Android 8.0 以下），不支持自动添加小组件。"
            !launcherSupported -> "当前桌面启动器不支持自动添加小组件。"
            vendorRestricted -> "$vendorName 设备可能需要手动添加小组件。"
            batteryOptimized -> "应用受电池优化限制，可能影响小组件回调。建议加入电池优化白名单。"
            else -> ""
        }
    }

    /** Run all pre-flight checks. Cheap — safe to call on the main thread. */
    fun report(context: Context): Report {
        val apiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val launcherSupported = if (apiSupported) {
            runCatching {
                AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            }.getOrDefault(false)
        } else false
        val widgetAlreadyPlaced = runCatching {
            val provider = ComponentName(context, TaskWidgetProvider::class.java)
            AppWidgetManager.getInstance(context).getAppWidgetIds(provider).isNotEmpty()
        }.getOrDefault(false)
        val batteryOptimized = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        val vendorName = Build.MANUFACTURER?.lowercase()
        val vendorRestricted = vendorName in restrictedVendors

        Log.d(TAG, "report: api=$apiSupported launcher=$launcherSupported " +
            "placed=$widgetAlreadyPlaced battery=$batteryOptimized " +
            "vendor=$vendorName restricted=$vendorRestricted")
        return Report(
            apiSupported = apiSupported,
            launcherSupported = launcherSupported,
            widgetAlreadyPlaced = widgetAlreadyPlaced,
            batteryOptimized = batteryOptimized,
            vendorRestricted = vendorRestricted,
            vendorName = vendorName?.replaceFirstChar { it.uppercase() }
        )
    }

    /** Opens the system battery-optimization exemption screen for this app. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { Log.e(TAG, "Cannot open battery settings", it) }
    }

    /**
     * Best-effort: open the manufacturer's widget picker. Falls back to the
     * AOSP launcher settings intent when no vendor-specific one resolves.
     */
    fun openWidgetPicker(context: Context) {
        val candidates = listOf(
            // Generic AOSP / Pixel
            Intent("android.appwidget.action.APPWIDGET_PICK"),
            // Huawei / HarmonyOS
            Intent("com.huawei.android.launcher.action.APPWIDGET_PICK"),
            // Xiaomi MIUI
            Intent("com.miui.launcher.action.APPWIDGET_PICK"),
            // OPPO ColorOS
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
