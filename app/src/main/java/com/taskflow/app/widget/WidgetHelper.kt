package com.taskflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WidgetHelper {

    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            if (ids.isEmpty()) return@launch

            val pending = withContext(Dispatchers.IO) {
                TaskRepository.get(context).getPinnedPending()
            }
            val remaining = pending.size
            ids.forEach { id ->
                val views = buildViews(context, id, remaining)
                manager.updateAppWidget(id, views)
                manager.notifyAppWidgetViewDataChanged(id, R.id.task_list)
            }
        }
    }

    suspend fun buildForId(context: Context, appWidgetId: Int): RemoteViews {
        val remaining = withContext(Dispatchers.IO) {
            TaskRepository.get(context).getPinnedPending().size
        }
        return buildViews(context, appWidgetId, remaining)
    }

    private fun buildViews(
        context: Context,
        appWidgetId: Int,
        remaining: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_content)

        // 点击 header 区域打开 APP
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

        // ListView 数据绑定
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
            // 空状态点击打开 APP
            views.setOnClickPendingIntent(R.id.empty_text, headerPi)
        } else {
            views.setViewVisibility(R.id.task_list, View.VISIBLE)
            views.setViewVisibility(R.id.empty_text, View.GONE)
        }

        return views
    }

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    fun requestPinWidget(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                val callback = PendingIntent.getBroadcast(
                    context,
                    System.currentTimeMillis().toInt(),
                    Intent(context, WidgetPinResultReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        manager.requestPinAppWidget(providerComponent(context), null, callback)
                    } else {
                        manager.requestPinAppWidget(providerComponent(context), null, callback)
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        }
        return false
    }

    fun isAnyWidgetPlaced(context: Context): Boolean {
        return AppWidgetManager.getInstance(context)
            .getAppWidgetIds(providerComponent(context))
            .isNotEmpty()
    }
}
