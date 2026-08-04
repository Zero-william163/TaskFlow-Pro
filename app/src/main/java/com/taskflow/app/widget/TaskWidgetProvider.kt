package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App Widget entry point. Rebuilds its RemoteViews on update, resize, and whenever a
 * [ACTION_TASKS_CHANGED] / [ACTION_WIDGET_REFRESH] broadcast is received, so the
 * home-screen component always reflects the latest task list without manual refresh.
 */
class TaskWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            scope.launch {
                val views = WidgetHelper.buildForId(context, id)
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        scope.launch {
            val views = WidgetHelper.buildForId(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onEnabled(context: Context) {
        // First widget instance placed — remember so we never show the guide again.
        scope.launch { UserPreferences.get(context).setWidgetAdded(true) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TASKS_CHANGED, ACTION_WIDGET_REFRESH -> WidgetHelper.refresh(context)
            ACTION_TOGGLE_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId > 0) {
                    scope.launch {
                        val repo = TaskRepository.get(context)
                        val task = repo.getTask(taskId)
                        if (task != null) {
                            repo.setCompleted(task, !task.isCompleted)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.taskflow.app.WIDGET_REFRESH"
        const val ACTION_TASKS_CHANGED = "com.taskflow.app.TASKS_CHANGED"
        const val ACTION_TOGGLE_TASK = "com.taskflow.app.TOGGLE_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
