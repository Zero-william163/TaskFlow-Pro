package com.taskflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WidgetHelper"

/**
 * Widget 全链路调试入口。所有日志统一 TAG="WidgetHelper"，便于 Logcat 过滤。
 *
 * v7 修复核心问题：
 * - requestPinWidget 不再静默吞掉异常，全部打印完整 stacktrace 到 Logcat
 * - 暴露 [lastPinError] 供 UI 层读取并展示给用户
 * - 全流程日志：API、supported、ComponentName、accepted、异常类型、当前 Widget 数量
 */
object WidgetHelper {

    /**
     * 上一次 requestPinWidget 失败的异常（如果有）。
     * UI 层可读取此字段向用户展示真实失败原因，而不是"假装成功"。
     */
    @Volatile
    var lastPinError: Throwable? = null
        private set

    /**
     * 上一次 requestPinWidget 的诊断信息（无论成功失败）。
     * 包含 API、Launcher 包名、isRequestPinAppWidgetSupported 等。
     */
    @Volatile
    var lastPinDiagnostic: String = ""
        private set

    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            Log.d(TAG, "refresh: appWidgetIds=${ids.toList()}")
            if (ids.isEmpty()) return@launch
            val pending = withContext(Dispatchers.IO) {
                TaskRepository.get(context).getPinnedPending().size
            }
            Log.d(TAG, "refresh: pinnedPending count=$pending")
            ids.forEach { id ->
                try {
                    val views = buildViews(context, id, pending)
                    manager.updateAppWidget(id, views)
                    Log.d(TAG, "refresh: widget $id updated")
                } catch (e: Throwable) {
                    Log.e(TAG, "refresh: widget $id buildViews failed", e)
                }
            }
        }
    }

    suspend fun buildForId(context: Context, appWidgetId: Int): RemoteViews {
        val remaining = withContext(Dispatchers.IO) {
            TaskRepository.get(context).getPinnedPending().size
        }
        return buildViews(context, appWidgetId, remaining)
    }

    private fun buildViews(context: Context, appWidgetId: Int, remaining: Int): RemoteViews {
        Log.d(TAG, "buildViews: widgetId=$appWidgetId, remaining=$remaining")
        val views = RemoteViews(context.packageName, R.layout.widget_content)
        val headerPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_header, headerPi)
        views.setTextViewText(
            R.id.count_text,
            if (remaining > 0) context.getString(R.string.widget_remaining, remaining)
            else context.getString(R.string.widget_all_done)
        )
        val listIntent = Intent(context, TaskListRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.task_list, listIntent)
        val template = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val templatePi = PendingIntent.getActivity(
            context, appWidgetId, template,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setPendingIntentTemplate(R.id.task_list, templatePi)
        if (remaining == 0) {
            views.setViewVisibility(R.id.task_list, View.GONE)
            views.setViewVisibility(R.id.empty_text, View.VISIBLE)
            views.setTextViewText(R.id.empty_text, context.getString(R.string.widget_no_tasks))
            views.setOnClickPendingIntent(R.id.empty_text, headerPi)
        } else {
            views.setViewVisibility(R.id.task_list, View.VISIBLE)
            views.setViewVisibility(R.id.empty_text, View.GONE)
        }
        Log.d(TAG, "buildViews: ✅ RemoteViews built for widgetId=$appWidgetId")
        return views
    }

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    /**
     * 请求系统 Pin Widget。
     *
     * 返回值：
     * - true: 系统接受了请求（但不代表 Widget 已创建）
     * - false: 系统不支持或请求失败（详细原因见 [lastPinError] / [lastPinDiagnostic]）
     *
     * v7 修复：
     * - 不再 `catch(_: Throwable)` 静默吞掉异常
     * - 全部打印完整 stacktrace 到 Logcat
     * - 记录 API、Launcher 包名、isRequestPinAppWidgetSupported、accepted
     * - 异常存入 [lastPinError] 供 UI 读取展示
     *
     * 必须在主线程调用。
     */
    fun requestPinWidget(context: Context): Boolean {
        lastPinError = null
        val launcherPkg = getLauncherPackage(context)
        val apiLevel = Build.VERSION.SDK_INT

        Log.d(TAG, "================ requestPinWidget START ================")
        Log.d(TAG, "requestPinWidget: api=$apiLevel, launcher=$launcherPkg, " +
            "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastPinDiagnostic = "API < 26 (current=$apiLevel), requestPinAppWidget 不可用"
            Log.w(TAG, "requestPinWidget: $lastPinDiagnostic")
            return false
        }

        val manager = AppWidgetManager.getInstance(context)
        val supported = try {
            manager.isRequestPinAppWidgetSupported
        } catch (e: Throwable) {
            lastPinError = e
            lastPinDiagnostic = "isRequestPinAppWidgetSupported 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            return false
        }

        val provider = providerComponent(context)
        val currentIds = try {
            manager.getAppWidgetIds(provider).toList()
        } catch (e: Throwable) {
            Log.w(TAG, "requestPinWidget: getAppWidgetIds failed", e)
            emptyList()
        }

        Log.d(TAG, "requestPinWidget: isRequestPinAppWidgetSupported=$supported, " +
            "provider=${provider.flattenToShortString()}, existingIds=$currentIds")

        lastPinDiagnostic = "api=$apiLevel, launcher=$launcherPkg, " +
            "isRequestPinAppWidgetSupported=$supported, existingWidgetCount=${currentIds.size}"

        if (!supported) {
            Log.w(TAG, "requestPinWidget: 当前 Launcher 不支持 requestPinAppWidget，" +
                "需引导用户手动添加")
            return false
        }

        // Launcher 通过 resultExtras 回传 widget id，必须显式 setResult。
        // 回调 PendingIntent 必须可变（FLAG_MUTABLE），系统会向其 extra 写入新 widget id。
        val callbackIntent = Intent(context, WidgetPinResultReceiver::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val callback = try {
            PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                callbackIntent,
                flags
            )
        } catch (e: Throwable) {
            lastPinError = e
            lastPinDiagnostic = "PendingIntent.getBroadcast 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            return false
        }
        Log.d(TAG, "requestPinWidget: callback PendingIntent 创建成功, flags=$flags")

        return try {
            val accepted = manager.requestPinAppWidget(provider, null, callback)
            Log.d(TAG, "requestPinWidget: requestPinAppWidget returned accepted=$accepted")
            Log.d(TAG, "================ requestPinWidget END (accepted=$accepted) ================")
            lastPinDiagnostic += ", accepted=$accepted"
            if (!accepted) {
                // 系统接受了请求但返回 false：常见于厂商 ROM 拦截
                Log.w(TAG, "requestPinWidget: 系统返回 false（厂商 ROM 可能拦截了请求）")
            }
            accepted
        } catch (e: Throwable) {
            // v7 关键修复：不再 `catch(_: Throwable)` 静默吞掉
            // 打印完整 stacktrace，存入 lastPinError 供 UI 展示
            lastPinError = e
            lastPinDiagnostic = "requestPinAppWidget 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            Log.e(TAG, "requestPinWidget: 完整 stacktrace:", e)
            Log.d(TAG, "================ requestPinWidget END (exception) ================")
            false
        }
    }

    /**
     * 获取当前默认 Launcher 包名（用于诊断日志）。
     * 通过 ACTION_HOME Intent 的 resolveActivity 推断。
     */
    private fun getLauncherPackage(context: Context): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName ?: "unknown"
        } catch (e: Throwable) {
            "unknown(${e.javaClass.simpleName})"
        }
    }

    /**
     * 实时检测：Widget 是否存在于桌面。
     * 只信任系统 API，不信任任何本地缓存。
     */
    fun isWidgetPlaced(context: Context): Boolean {
        return try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(providerComponent(context))
                .isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 同 [isWidgetPlaced]，兼容调用方命名。
     */
    fun isAnyWidgetPlaced(context: Context): Boolean = isWidgetPlaced(context)
}
