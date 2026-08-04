package com.taskflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/**
 * Owns widget view construction and refresh.
 *
 * The widget is a *collection* widget: the task list is a [android.widget.ListView]
 * backed by [TaskListRemoteViewsService]. This lets the widget scroll through any
 * number of tasks instead of the previous fixed 6-row layout.
 *
 * Data source is always Room (via [TaskRepository.getPinnedPending]) — there is no
 * temporary cache, so a completed task disappears from the widget the moment its
 * Room row is updated and the change broadcast is received.
 */
object WidgetHelper {

    /** Trigger an asynchronous refresh of every placed widget instance. */
    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            if (ids.isEmpty()) return@launch

            // Refresh the per-widget header (count) and tell each ListView to reload.
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

    /** Synchronous build used by the provider's onUpdate. */
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

        // Header tap → open app.
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))

        views.setTextViewText(
            R.id.count_text,
            if (remaining > 0) context.getString(R.string.widget_remaining, remaining)
            else context.getString(R.string.widget_all_done)
        )

        // Wire the ListView to its RemoteViewsService.
        val listIntent = Intent(context, TaskListRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.task_list, listIntent)

        // Template intent so each row's fill-in intent opens the task detail.
        val template = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val templatePi = PendingIntent.getActivity(
            context, 0, template,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setPendingIntentTemplate(R.id.task_list, templatePi)

        // Toggle empty state: hide the list and show the placeholder when there is
        // nothing to render. (weight keeps the list's slot; we just hide it.)
        if (remaining == 0) {
            views.setViewVisibility(R.id.task_list, View.GONE)
            views.setViewVisibility(R.id.empty_text, View.VISIBLE)
            views.setTextViewText(R.id.empty_text, context.getString(R.string.widget_no_tasks))
        } else {
            views.setViewVisibility(R.id.task_list, View.VISIBLE)
            views.setViewVisibility(R.id.empty_text, View.GONE)
        }

        return views
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    /**
     * Attempts the modern pinned-widget flow (API 26+). Returns true when the system
     * accepted the request; callers should fall back to a manual instruction dialog.
     */
    fun requestPinWidget(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                val callback = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, WidgetPinResultReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                manager.requestPinAppWidget(providerComponent(context), null, callback)
                true
            } else false
        } else false
    }
}
