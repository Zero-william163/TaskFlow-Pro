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

object WidgetHelper {

    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            Log.d(TAG, "refresh: appWidgetIds=${ids.toList()}")
            if (ids.isEmpty()) return@launch
            val pending = withContext(Dispatchers.IO) {
                TaskRepository.get(context).getPinnedPending().size
            }
            ids.forEach { id ->
                val views = buildViews(context, id, pending)
                manager.updateAppWidget(id, views)
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
        return views
    }

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    /**
     * 请求系统 Pin Widget。
     *
     * 返回值：
     * - true: 系统接受了请求（但不代表 Widget 已创建）
     * - false: 系统不支持或请求失败
     *
     * 注意：此方法返回 true 仅代表系统接受了请求。
     * Widget 是否真正创建必须通过 [isWidgetPlaced]() 再次确认，
     * 或等待 [WidgetPinResultReceiver] 回调。
     *
     * 必须在主线程调用。
     */
    fun requestPinWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false

        // Launcher 通过 resultExtras 回传 widget id，必须显式 setResult。
        val callbackIntent = Intent(context, WidgetPinResultReceiver::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val callback = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            callbackIntent,
            flags
        )
        return try {
            manager.requestPinAppWidget(providerComponent(context), null, callback)
        } catch (_: Throwable) {
            false
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
