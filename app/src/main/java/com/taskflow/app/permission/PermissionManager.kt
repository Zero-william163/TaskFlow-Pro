package com.taskflow.app.permission

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.taskflow.app.R
import com.taskflow.app.widget.TaskWidgetProvider

enum class PermissionType {
    NOTIFICATION,
    INSTALL,
    BATTERY,
    EXACT_ALARM,
    WIDGET
}

enum class PermissionStatus { GRANTED, DENIED, ADDED, NOT_ADDED }

data class PermissionItem(
    val type: PermissionType,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val status: PermissionStatus,
    /** True when the current device version exposes this permission at all. */
    val applicable: Boolean
) {
    val isOk: Boolean
        get() = status == PermissionStatus.GRANTED || status == PermissionStatus.ADDED
}

/**
 * Centralized permission inspection and deep-link resolution. Each [intentFor] returns
 * an intent that jumps *directly* to the relevant system page (not the app's main
 * settings screen), version-aware across Android 10–14.
 */
class PermissionManager(private val context: Context) {

    fun all(): List<PermissionItem> = listOf(
        notification(),
        widget(),
        install(),
        exactAlarm(),
        battery(),
    )

    fun notification(): PermissionItem = PermissionItem(
        type = PermissionType.NOTIFICATION,
        titleRes = R.string.permission_notifications,
        descRes = R.string.permission_notifications_desc,
        status = if (NotificationManagerCompat.from(context).areNotificationsEnabled())
            PermissionStatus.GRANTED else PermissionStatus.DENIED,
        applicable = true
    )

    fun widget(): PermissionItem {
        val added = isWidgetAdded()
        return PermissionItem(
            type = PermissionType.WIDGET,
            titleRes = R.string.permission_widget,
            descRes = R.string.permission_widget_desc,
            status = if (added) PermissionStatus.ADDED else PermissionStatus.NOT_ADDED,
            applicable = true
        )
    }

    fun install(): PermissionItem = PermissionItem(
        type = PermissionType.INSTALL,
        titleRes = R.string.permission_install,
        descRes = R.string.permission_install_desc,
        status = if (context.packageManager.canRequestPackageInstalls())
            PermissionStatus.GRANTED else PermissionStatus.DENIED,
        applicable = true
    )

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

    /** Whether at least one instance of our widget is placed on the home screen. */
    fun isWidgetAdded(): Boolean {
        return try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, TaskWidgetProvider::class.java)
            )
            ids.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Resolve the deep-link intent for a permission. Returns null only when the
     * permission does not exist on this Android version.
     */
    fun intentFor(type: PermissionType): Intent? = when (type) {
        PermissionType.NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        PermissionType.INSTALL -> Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        PermissionType.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionType.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else null
        PermissionType.WIDGET -> null // handled by the widget pinning flow, not a settings page
    }

    /** Aggregate gate: are all *required* permissions (notifications) granted? */
    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
