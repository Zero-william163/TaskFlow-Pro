package com.taskflow.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backs the widget's scrollable [android.widget.ListView].
 *
 * The system calls [RemoteViewsFactory.onDataSetChanged] on a worker thread whenever
 * the widget is refreshed (via `notifyAppWidgetViewDataChanged`). We then read the
 * pinned, incomplete tasks straight from Room — Room remains the single source of
 * truth, no in-memory cache is kept here.
 */
class TaskListRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TaskListFactory(applicationContext)

    private class TaskListFactory(private val context: Context) : RemoteViewsFactory {

        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        private val dayFmt = DateTimeFormatter.ofPattern("MM/dd")

        // Guarded by the system's serialized calls to onDataSetChanged / getViewAt.
        @Volatile
        private var tasks: List<Task> = emptyList()

        override fun onCreate() { /* no-op */ }

        override fun onDestroy() { /* no-op */ }

        override fun onDataSetChanged() {
            // Runs on a background thread; blocking read is safe and standard here.
            tasks = runBlocking { TaskRepository.get(context).getPinnedPending() }
        }

        override fun getCount(): Int = tasks.size

        override fun getViewAt(position: Int): RemoteViews {
            if (position >= tasks.size) return loadingView()
            val task = tasks[position]
            val views = RemoteViews(context.packageName, R.layout.widget_task_item)

            views.setTextViewText(R.id.item_title, task.title)

            val meta = task.dueDate?.let { describeTime(it) }
            if (meta != null) {
                views.setViewVisibility(R.id.item_meta, android.view.View.VISIBLE)
                views.setTextViewText(R.id.item_meta, meta)
            } else {
                views.setViewVisibility(R.id.item_meta, android.view.View.GONE)
            }

            views.setImageViewResource(R.id.item_check, R.drawable.widget_check_circle)

            // Per-item click → open the task's detail page. The ListView carries the
            // template PendingIntent (set by WidgetHelper); we only fill in the id.
            val fillIn = Intent().apply {
                putExtra(MainActivity.EXTRA_TASK_ID, task.id)
            }
            views.setOnClickFillInIntent(R.id.widget_item_root, fillIn)

            return views
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
            RemoteViews(context.packageName, R.layout.widget_loading)
    }
}
