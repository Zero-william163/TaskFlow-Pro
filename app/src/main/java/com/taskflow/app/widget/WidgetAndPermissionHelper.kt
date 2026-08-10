package com.taskflow.app.widget

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.taskflow.app.R
import com.taskflow.app.permission.PermissionLogger

private const val TAG = "WidgetPermHelper"

/**
 * 小组件固定 + 受限设置引导的统一工具类。
 *
 * 核心职责：
 * 1. [checkHasWidget] — 检测桌面是否已存在 TaskFlow 小组件（只信任系统 API）
 * 2. [requestPinWidget] — 优雅拉起 requestPinAppWidget（免悬浮窗权限），含异常捕获 + 国产 ROM 兜底
 * 3. [showRestrictedSettingsGuide] — 弹出「允许受限制设置」步骤引导 Dialog 并跳转应用详情页
 * 4. [showPinWidgetGuideDialog] — 国产 ROM 拦截自动创建时的手把手引导
 * 5. [maybePromptPinWidget] — 保存卡片时的智能判断入口
 *
 * 设计原则：
 * - requestPinAppWidget 不需要 SYSTEM_ALERT_WINDOW 权限，可避开悬浮窗变灰问题
 * - Widget 状态唯一可信源：AppWidgetManager.getAppWidgetIds()，不信任本地缓存
 * - 受限设置引导：不直接跳 ACTION_MANAGE_OVERLAY_PERMISSION（开关灰色无意义），
 *   而是跳应用详情页引导用户手动解除限制
 */
object WidgetAndPermissionHelper {

    /** Widget Provider 的 ComponentName */
    private fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    /**
     * 检测桌面上是否已存在 TaskFlow 小组件。
     * 只信任系统 API [AppWidgetManager.getAppWidgetIds]，不信任任何本地缓存。
     *
     * @return true 表示桌面已有至少一个 Widget 实例
     */
    fun checkHasWidget(context: Context): Boolean {
        return try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(providerComponent(context))
                .isNotEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "checkHasWidget failed", e)
            false
        }
    }

    /**
     * 优雅拉起桌面小组件固定申请。
     *
     * 使用 Android 8.0+ 原生 [AppWidgetManager.requestPinAppWidget]，
     * **该 API 不需要 SYSTEM_ALERT_WINDOW 权限**，可避开悬浮窗变灰问题。
     *
     * 流程：
     * 1. 检查 API >= 26 且 Launcher 支持自动添加
     * 2. 调用 requestPinAppWidget 拉起系统固定弹窗
     * 3. 若返回 false 或抛异常 → 调用 [showPinWidgetGuideDialog] 显示手动引导
     *
     * @param activity 调用方 Activity（用于显示 Dialog）
     * @return true 表示系统接受了请求（不代表 Widget 已创建，需等待回调）
     */
    fun requestPinWidget(activity: Activity): Boolean {
        // API < 26 不支持 requestPinAppWidget，直接显示手动引导
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showPinWidgetGuideDialog(activity)
            return false
        }

        val manager = AppWidgetManager.getInstance(activity)

        // 检查 Launcher 是否支持自动添加
        if (!manager.isRequestPinAppWidgetSupported) {
            Log.w(TAG, "requestPinWidget: Launcher 不支持自动添加")
            showPinWidgetGuideDialog(activity)
            return false
        }

        // 构建回调 PendingIntent：用户确认放置后系统回调 WidgetPinResultReceiver
        val callbackIntent = Intent(activity, WidgetPinResultReceiver::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val callback = PendingIntent.getBroadcast(
            activity,
            System.currentTimeMillis().toInt(),
            callbackIntent,
            flags
        )

        return try {
            val accepted = manager.requestPinAppWidget(
                providerComponent(activity),
                null,
                callback
            )
            if (accepted) {
                Log.d(TAG, "requestPinWidget: 系统接受了请求，等待用户确认放置")
                PermissionLogger.logJumpSuccess(
                    com.taskflow.app.permission.PermissionType.OVERLAY,
                    Intent("requestPinAppWidget")
                )
            } else {
                // requestPinAppWidget 返回 false：厂商 ROM 拦截了请求
                Log.w(TAG, "requestPinWidget: 系统拒绝（厂商 ROM 可能拦截）")
                showPinWidgetGuideDialog(activity)
            }
            accepted
        } catch (e: Throwable) {
            // 捕获厂商 ROM 抛出的异常（部分华为/小米设备会抛 SecurityException）
            Log.e(TAG, "requestPinWidget: 异常（厂商 ROM 拦截）", e)
            showPinWidgetGuideDialog(activity)
            false
        }
    }

    /**
     * 弹出「允许受限制设置」步骤引导 Dialog。
     *
     * 解决 Android 13+ 侧载 APK 导致的 SYSTEM_ALERT_WINDOW 开关变灰问题。
     *
     * 关键设计：**不直接跳转 ACTION_MANAGE_OVERLAY_PERMISSION**（开关灰色无法点击），
     * 而是弹 Dialog 引导用户操作，确认后跳转应用详情页。
     *
     * 用户操作步骤：
     * 1. 点击「前往应用信息」按钮
     * 2. 进入应用详情页后，点击右上角三点菜单 (⋮)
     * 3. 选择「允许受限制的设置」
     * 4. 返回后即可正常开启悬浮窗开关
     *
     * @param context 调用方 Context（需为 Activity Context 以显示 Dialog）
     */
    fun showRestrictedSettingsGuide(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.permission_guide_title)
            .setMessage(R.string.permission_overlay_restricted_guide)
            .setPositiveButton(R.string.permission_go_app_info) { _, _ ->
                jumpToAppDetails(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 弹出国产 ROM 手动添加小组件引导 Dialog。
     *
     * 当 requestPinAppWidget 返回 false 或抛异常时调用，
     * 引导用户手动长按桌面添加小组件。
     */
    fun showPinWidgetGuideDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.widget_pin_guide_title)
            .setMessage(R.string.widget_pin_guide_msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 保存卡片时的智能判断入口。
     *
     * 在用户新建/编辑任务并点击保存后调用：
     * 1. 检测桌面是否已有 Widget
     * 2. 若无 → 弹窗询问「是否在桌面创建小组件？」
     * 3. 点击「是」→ 调用 [requestPinWidget]
     *
     * @param activity 调用方 Activity
     * @return true 表示已弹出询问 Dialog（或已尝试添加），false 表示桌面已有 Widget
     */
    fun maybePromptPinWidget(activity: Activity): Boolean {
        // 桌面已有 Widget，无需提示
        if (checkHasWidget(activity)) {
            return false
        }
        // 弹出询问 Dialog
        AlertDialog.Builder(activity)
            .setTitle(R.string.widget_pin_prompt_title)
            .setMessage(R.string.widget_pin_prompt_msg)
            .setPositiveButton(R.string.widget_pin_prompt_yes) { _, _ ->
                requestPinWidget(activity)
            }
            .setNegativeButton(R.string.widget_pin_prompt_no, null)
            .show()
        return true
    }

    /**
     * 精确跳转至当前应用的【应用信息】页面。
     *
     * 用于受限设置引导：用户需在此页面点击三点菜单 → 允许受限制的设置。
     */
    private fun jumpToAppDetails(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "jumpToAppDetails failed", e)
            Toast.makeText(context, "无法跳转应用信息页", Toast.LENGTH_SHORT).show()
        }
    }
}
