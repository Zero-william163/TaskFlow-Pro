package com.taskflow.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "WidgetListService"

/**
 * Backs the widget's scrollable [android.widget.ListView].
 *
 * The system calls [RemoteViewsFactory.onDataSetChanged] on a worker thread whenever
 * the widget is refreshed (via `notifyAppWidgetViewDataChanged`). We then read the
 * pinned, incomplete tasks straight from Room — Room remains the single source of
 * truth, no in-memory cache is kept here.
 */
class TaskListRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        )
        Log.d(TAG, "onGetViewFactory: widgetId=$widgetId")
        return TaskListFactory(applicationContext, widgetId)
    }

    private class TaskListFactory(
        private val context: Context,
        private val widgetId: Int
    ) : RemoteViewsFactory {

        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        private val dayFmt = DateTimeFormatter.ofPattern("MM/dd")

        // Guarded by the system's serialized calls to onDataSetChanged / getViewAt.
        @Volatile
        private var tasks: List<Task> = emptyList()

        override fun onCreate() {
            Log.d(TAG, "factory[$widgetId].onCreate")
        }

        override fun onDestroy() {
            Log.d(TAG, "factory[$widgetId].onDestroy")
        }

        override fun onDataSetChanged() {
            // Runs on a background thread; blocking read is safe and standard here.
            Log.d(TAG, "factory[$widgetId].onDataSetChanged: querying Room...")
            tasks = try {
                val result = runBlocking { TaskRepository.get(context).getPinnedPending() }
                Log.d(TAG, "factory[$widgetId].onDataSetChanged: Room returned ${result.size} tasks")
                result
            } catch (e: Throwable) {
                Log.e(TAG, "factory[$widgetId].onDataSetChanged: Room query FAILED → 空列表", e)
                emptyList()
            }
        }

        override fun getCount(): Int {
            // 关键修复：永远至少返回 1 条数据（空态/占位），确保 Launcher 不会因
            // count=0 把 ListView 直接折叠为空白；真正的空态/加载态由 getViewAt 返回。
            val count = tasks.size.coerceAtLeast(1)
            Log.d(TAG, "factory[$widgetId].getCount=$count (realTasks=${tasks.size})")
            return count
        }

        override fun getViewAt(position: Int): RemoteViews {
            // 关键修复：当没有真实任务时，返回内联构建的空态 RemoteViews，
            // 完全不依赖 widget_task_item / widget_test 布局文件，确保 100% 成功渲染。
            if (tasks.isEmpty() || position >= tasks.size) {
                Log.d(TAG, "factory[$widgetId].getViewAt($position): return inline empty view")
                return buildInlineEmptyView()
            }
            val task = tasks[position]
            return try {
                val views = RemoteViews(context.packageName, R.layout.widget_task_item)
                Log.d(TAG, "factory[$widgetId].getViewAt($position): widget_task_item inflate OK")

                try {
                    views.setTextViewText(R.id.item_title, task.title)
                } catch (e: Throwable) {
                    Log.e(TAG, "factory[$widgetId].getViewAt($position): R.id.item_title FAILED", e)
                }

                val meta = try {
                    task.dueDate?.let { describeTime(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, "factory[$widgetId].getViewAt($position): describeTime FAILED", e)
                    null
                }
                if (meta != null) {
                    views.setViewVisibility(R.id.item_meta, android.view.View.VISIBLE)
                    try {
                        views.setTextViewText(R.id.item_meta, meta)
                    } catch (e: Throwable) {
                        Log.e(TAG, "factory[$widgetId].getViewAt($position): R.id.item_meta FAILED", e)
                    }
                } else {
                    views.setViewVisibility(R.id.item_meta, android.view.View.GONE)
                }

                try {
                    views.setImageViewResource(R.id.item_check, R.drawable.widget_check_circle)
                } catch (e: Throwable) {
                    Log.e(TAG, "factory[$widgetId].getViewAt($position): R.id.item_check drawable FAILED", e)
                }

                // Checkbox click → broadcast to toggle task completion directly from widget.
                try {
                    val toggleIntent = Intent(TaskWidgetProvider.ACTION_TOGGLE_TASK).apply {
                        setPackage(context.packageName)
                        putExtra(TaskWidgetProvider.EXTRA_TASK_ID, task.id)
                    }
                    views.setOnClickPendingIntent(
                        R.id.item_check,
                        android.app.PendingIntent.getBroadcast(
                            context,
                            task.id.toInt(),
                            toggleIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "factory[$widgetId].getViewAt($position): toggle PendingIntent FAILED", e)
                }

                // Per-item click → open the task's detail page. The ListView carries the
                // template PendingIntent (set by WidgetHelper); we only fill in the id.
                try {
                    val fillIn = Intent().apply {
                        putExtra(MainActivity.EXTRA_TASK_ID, task.id)
                    }
                    views.setOnClickFillInIntent(R.id.widget_item_root, fillIn)
                } catch (e: Throwable) {
                    Log.e(TAG, "factory[$widgetId].getViewAt($position): fillIn FAILED", e)
                }

                Log.d(TAG, "factory[$widgetId].getViewAt($position): ✅ OK")
                views
            } catch (e: Throwable) {
                // 顶层兜底：任何 getViewAt 失败 → 返回 loading_view（空壳）不抛异常
                Log.e(TAG, "factory[$widgetId].getViewAt($position): ❌ 外层失败 → 返回 loadingView()", e)
                loadingView()
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 2  // 0 = item, 1 = inline empty

        override fun getItemId(position: Int): Long =
            tasks.getOrNull(position)?.id ?: -1L

        override fun hasStableIds(): Boolean = true

        private fun describeTime(time: LocalDateTime): String {
            val now = LocalDateTime.now()
            return when {
                time.toLocalDate().isEqual(now.toLocalDate()) -> time.format(timeFmt)
                time.toLocalDate().isEqual(now.toLocalDate().plusDays(1)) -> "明 " + time.format(timeFmt)
                time.year == now.year -> time.format(dayFmt)
                else -> time.format(DateTimeFormatter.ofPattern("yy/MM/dd"))
            }
        }

        private fun loadingView(): RemoteViews = buildInlineEmptyView()

        /**
         * Widget ListView item 级别的内联空态视图。
         * 使用专用布局 widget_empty_item.xml（wrap_content + minHeight=72dp），
         * 避免之前用 widget_test.xml (match_parent) 导致的 ListView item 高度异常。
         */
        private fun buildInlineEmptyView(): RemoteViews {
            val text = if (tasks.isEmpty()) {
                context.getString(R.string.widget_no_tasks)
            } else {
                context.getString(R.string.widget_loading)
            }
            return try {
                RemoteViews(context.packageName, R.layout.widget_empty_item).also { views ->
                    try {
                        views.setTextViewText(R.id.empty_item_text, text)
                    } catch (e: Throwable) {
                        Log.w(TAG, "buildInlineEmptyView: setTextViewText FAILED", e)
                    }
                }
            } catch (e: Throwable) {
                // 终极兜底：widget_empty_item 也 inflate 失败 → 回退 widget_task_item
                // 把标题设为 text，保证内容可见。
                Log.e(TAG, "buildInlineEmptyView: ❌ widget_empty_item inflate FAILED", e)
                try {
                    RemoteViews(context.packageName, R.layout.widget_task_item).also { views ->
                        views.setTextViewText(R.id.item_title, text)
                        views.setViewVisibility(R.id.item_check, android.view.View.GONE)
                        views.setViewVisibility(R.id.item_meta, android.view.View.GONE)
                    }
                } catch (e2: Throwable) {
                    Log.e(TAG, "buildInlineEmptyView: ❌ widget_task_item 也 FAILED", e2)
                    RemoteViews(context.packageName, R.layout.widget_test)
                }
            }
        }
    }
}
