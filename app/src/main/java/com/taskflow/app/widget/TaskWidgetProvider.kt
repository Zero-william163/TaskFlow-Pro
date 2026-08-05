package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "WidgetProvider"

class TaskWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d(TAG, "onUpdate: ids=${appWidgetIds.toList()}")
        appWidgetIds.forEach { id ->
            scope.launch {
                try {
                    val views = WidgetHelper.buildForId(context, id)
                    appWidgetManager.updateAppWidget(id, views)
                    Log.d(TAG, "onUpdate: widget $id updated")
                } catch (e: Throwable) {
                    Log.e(TAG, "onUpdate: widget $id failed", e)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        Log.d(TAG, "onAppWidgetOptionsChanged: id=$appWidgetId")
        scope.launch {
            try {
                val views = WidgetHelper.buildForId(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Throwable) {
                Log.e(TAG, "onAppWidgetOptionsChanged: failed", e)
            }
        }
    }

    // onEnabled / onDisabled 不再写入本地缓存。
    // Widget 放置状态唯一可信源是 AppWidgetManager.getAppWidgetIds()，
    // 由 WidgetHelper.isWidgetPlaced() 实时读取，避免本地缓存与系统状态不一致。
    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled: first widget placed (state tracked via system API)")
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "onDeleted: ids=${appWidgetIds.toList()}")
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "onDisabled: last widget removed (state tracked via system API)")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
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
