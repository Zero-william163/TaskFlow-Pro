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

        // ===== 二分排查法 Step 2 =====
        // 推 widget_content.xml（含 ListView + ImageView）+ 绑定 RemoteViewsService
        // 但 Factory 返回固定假数据，不读 Room。
        // 如果成功 → widget_content 布局和 Service 都 OK，瓶颈在 Room
        // 如果失败 → widget_content.xml 布局本身有问题
        appWidgetIds.forEach { id ->
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_content)
                views.setTextViewText(R.id.count_text, "测试模式")
                views.setViewVisibility(R.id.task_list, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.empty_text, android.view.View.GONE)

                // 绑定 RemoteViewsService（Factory 返回假数据）
                val listIntent = android.content.Intent(context, TaskListRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = android.net.Uri.parse(toUri(android.content.Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.task_list, listIntent)

                appWidgetManager.updateAppWidget(id, views)
                Log.d(TAG, "onUpdate: widget $id → widget_content + 假数据 Service (二分法 Step 2)")
            } catch (e: Throwable) {
                Log.e(TAG, "onUpdate: ❌ widget_content FAILED for $id", e)
                // fallback to widget_test
                try {
                    appWidgetManager.updateAppWidget(id,
                        RemoteViews(context.packageName, R.layout.widget_test))
                } catch (e2: Throwable) {
                    Log.e(TAG, "onUpdate: ❌ widget_test also FAILED", e2)
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
