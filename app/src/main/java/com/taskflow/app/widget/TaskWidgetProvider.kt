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

        // ===== 二分排查法 Step 3 =====
        // 恢复真实 Room 读取。所有入口（onUpdate / onAppWidgetOptionsChanged / refresh）
        // 统一异步调用 WidgetHelper.buildForId，它内部有 4 层 fallback，保证不崩溃。
        // 每个 widgetId 独立更新，互不影响。
        appWidgetIds.forEach { id -> buildWidgetAsync(context, appWidgetManager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        Log.d(TAG, "onAppWidgetOptionsChanged: id=$appWidgetId, options=$newOptions")
        buildWidgetAsync(context, appWidgetManager, appWidgetId)
    }

    /** 所有 Widget 刷新入口统一走这里：异步读 Room + WidgetHelper 渲染 + widget_test 兜底。 */
    private fun buildWidgetAsync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        scope.launch {
            try {
                Log.d(TAG, "buildWidgetAsync[$appWidgetId]: start")
                val views = WidgetHelper.buildForId(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                // 断点 #1 同根因修复：onUpdate / onAppWidgetOptionsChanged 也必须 notify
                // ListView 刷新，否则 Factory 永远不重新读 DB。
                try {
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.task_list)
                    Log.d(TAG, "buildWidgetAsync[$appWidgetId]: ✅ OK (ListView notified)")
                } catch (e: Throwable) {
                    Log.e(TAG, "buildWidgetAsync[$appWidgetId]: ❌ notifyAppWidgetViewDataChanged FAILED", e)
                }
            } catch (e: Throwable) {
                // WidgetHelper.buildForId 内部已经 4 层 fallback，理论上不会抛；
                // 这里兜底防止极端情况。
                Log.e(TAG, "buildWidgetAsync[$appWidgetId]: ❌ FAILED → widget_test", e)
                try {
                    appWidgetManager.updateAppWidget(appWidgetId,
                        RemoteViews(context.packageName, R.layout.widget_test))
                } catch (e2: Throwable) {
                    Log.e(TAG, "buildWidgetAsync[$appWidgetId]: ❌ widget_test also FAILED", e2)
                }
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
            ACTION_TOGGLE_MODE -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                Log.d(TAG, "onReceive: TOGGLE_MODE widgetId=$widgetId")
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetHelper.toggleMode(context, widgetId)
                }
            }
            ACTION_SET_MODE -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val mode = intent.getStringExtra(EXTRA_MODE)
                Log.d(TAG, "onReceive: SET_MODE widgetId=$widgetId, mode=$mode")
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && mode != null) {
                    WidgetHelper.setMode(context, widgetId, mode)
                }
            }
            ACTION_TOGGLE_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                Log.d(TAG, "onReceive: TOGGLE_TASK taskId=$taskId")
                if (taskId > 0) {
                    scope.launch {
                        val repo = TaskRepository.get(context)
                        val task = repo.getTask(taskId)
                        if (task != null) {
                            if (task.isRecurring) {
                                // Recurring: toggle daily check-off
                                repo.setCompleted(task, !task.isCompletedToday)
                                Log.d(TAG, "onReceive: recurring task $taskId checkOff=${!task.isCompletedToday}")
                            } else {
                                repo.setCompleted(task, !task.isCompleted)
                                Log.d(TAG, "onReceive: task $taskId toggled to ${!task.isCompleted}")
                            }
                            // ====== Spec: 点击直接完成/恢复任务，并刷新小组件 ======
                            // Full rebuild + per-widget ListView notify for instant UI update.
                            WidgetHelper.refresh(context)
                            // Also explicitly notify all widget ListViews to re-query.
                            try {
                                val mgr = AppWidgetManager.getInstance(context)
                                val ids = mgr.getAppWidgetIds(
                                    android.content.ComponentName(context, TaskWidgetProvider::class.java)
                                )
                                ids.forEach { id ->
                                    mgr.notifyAppWidgetViewDataChanged(id, R.id.task_list)
                                }
                                Log.d(TAG, "onReceive: notified ${ids.size} widget ListViews")
                            } catch (e: Throwable) {
                                Log.w(TAG, "onReceive: notifyAppWidgetViewDataChanged failed", e)
                            }
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
        // 别名：便于 WidgetHelper 中使用 TaskWidgetProvider.ACTION_REFRESH
        const val ACTION_REFRESH = ACTION_WIDGET_REFRESH
        const val ACTION_TASKS_CHANGED = "com.taskflow.app.TASKS_CHANGED"
        const val ACTION_TOGGLE_TASK = "com.taskflow.app.TOGGLE_TASK"
        const val ACTION_TOGGLE_MODE = "com.taskflow.app.TOGGLE_MODE"
        const val ACTION_SET_MODE = "com.taskflow.app.SET_MODE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_MODE = "extra_mode"

        // Widget display modes
        const val WIDGET_MODE_TODAY = "today"
        const val WIDGET_MODE_ALL = "all"
    }
}
