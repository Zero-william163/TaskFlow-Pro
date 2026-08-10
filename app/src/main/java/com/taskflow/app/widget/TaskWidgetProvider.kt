package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "WidgetProvider"

class TaskWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d(TAG, "================ onUpdate ================")
        Log.d(TAG, "onUpdate: ids=${appWidgetIds.toList()}, count=${appWidgetIds.size}")

        // 先立即更新一次：使用 widget_test（绝对安全的布局，纯 LinearLayout+TextView+硬编码颜色）
        // 注意：RemoteViews 构造函数不会 inflate，真正的 inflate 在 Launcher 进程中发生。
        // 所以不能用 try/catch 来判断布局是否有效——必须确保布局本身就是合法的。
        // widget_test.xml 不含 <View>、不引用 drawable/color 资源，100% RemoteViews 兼容。
        appWidgetIds.forEach { id ->
            try {
                val immediate = RemoteViews(context.packageName, R.layout.widget_test)
                appWidgetManager.updateAppWidget(id, immediate)
                Log.d(TAG, "onUpdate: widget $id 立即推送 widget_test (最安全布局)")
            } catch (e: Throwable) {
                Log.e(TAG, "onUpdate: ❌ widget_test push FAILED for $id (极端罕见)", e)
            }
        }

        appWidgetIds.forEach { id ->
            scope.launch {
                try {
                    Log.d(TAG, "onUpdate: building views for widget $id...")
                    val views = WidgetHelper.buildForId(context, id)
                    appWidgetManager.updateAppWidget(id, views)
                    Log.d(TAG, "onUpdate: ✅ widget $id updated successfully")
                } catch (e: Throwable) {
                    // buildForId 理论上不会抛异常（4 层 fallback），但这里兜底以防万一
                    Log.e(TAG, "onUpdate: ❌ widget $id failed even after fallbacks", e)
                    try {
                        appWidgetManager.updateAppWidget(id,
                            RemoteViews(context.packageName, R.layout.widget_test))
                        Log.d(TAG, "onUpdate: widget $id → widget_test fallback")
                    } catch (e2: Throwable) {
                        Log.e(TAG, "onUpdate: ❌ widget_test also FAILED", e2)
                    }
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
        Log.d(TAG, "onAppWidgetOptionsChanged: id=$appWidgetId, options=$newOptions")
        scope.launch {
            try {
                val views = WidgetHelper.buildForId(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                Log.d(TAG, "onAppWidgetOptionsChanged: widget $appWidgetId rebuilt")
            } catch (e: Throwable) {
                Log.e(TAG, "onAppWidgetOptionsChanged: failed", e)
            }
        }
    }

    // onEnabled / onDisabled 不再写入本地缓存。
    // Widget 放置状态唯一可信源是 AppWidgetManager.getAppWidgetIds()，
    // 由 WidgetHelper.isWidgetPlaced() 实时读取，避免本地缓存与系统状态不一致。
    override fun onEnabled(context: Context) {
        Log.d(TAG, "================ onEnabled ================")
        Log.d(TAG, "onEnabled: ✅ 首个 Widget 被放置到桌面 (state tracked via system API)")
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "================ onDeleted ================")
        Log.d(TAG, "onDeleted: ids=${appWidgetIds.toList()}")
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "================ onDisabled ================")
        Log.d(TAG, "onDisabled: 所有 Widget 已从桌面移除 (state tracked via system API)")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}, extras=${intent.extras}")
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TASKS_CHANGED, ACTION_WIDGET_REFRESH -> WidgetHelper.refresh(context)
            ACTION_TOGGLE_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                Log.d(TAG, "onReceive: TOGGLE_TASK taskId=$taskId")
                if (taskId > 0) {
                    scope.launch {
                        val repo = TaskRepository.get(context)
                        val task = repo.getTask(taskId)
                        if (task != null) {
                            repo.setCompleted(task, !task.isCompleted)
                            Log.d(TAG, "onReceive: task $taskId toggled to ${!task.isCompleted}")
                        } else {
                            Log.w(TAG, "onReceive: task $taskId not found")
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
