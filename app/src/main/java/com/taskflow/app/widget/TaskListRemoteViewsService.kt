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
                runBlocking { TaskRepository.get(context).getPinnedPending() }
            } catch (e: Throwable) {
                Log.e(TAG, "factory[$widgetId].onDataSetChanged: Room query failed", e)
                emptyList()
            }
            Log.d(TAG, "factory[$widgetId].onDataSetChanged: tasks=${tasks.size}")
        }

        override fun getCount(): Int {
            val count = tasks.size
            Log.d(TAG, "factory[$widgetId].getCount=$count")
            return count
        }

        override fun getViewAt(position: Int): RemoteViews {
            Log.d(TAG, "factory[$widgetId].getViewAt($position)")
            if (position >= tasks.size) return loadingView()
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

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long =
            tasks.getOrNull(position)?.id ?: position.toLong()

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

        private fun loadingView(): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_test)
    }
}
