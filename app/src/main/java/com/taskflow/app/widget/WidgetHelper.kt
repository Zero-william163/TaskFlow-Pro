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
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Owns widget view construction and refresh. Building reads the database, so it always
 * runs on the IO dispatcher. Both [TaskWidgetProvider] and [WidgetRefreshReceiver]
 * delegate here.
 */
object WidgetHelper {

    private const val MAX_ROWS = 6
    private val rowIds = intArrayOf(
        R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4, R.id.row_5
    )
    private val titleIds = intArrayOf(
        R.id.title_0, R.id.title_1, R.id.title_2, R.id.title_3, R.id.title_4, R.id.title_5
    )
    private val metaIds = intArrayOf(
        R.id.meta_0, R.id.meta_1, R.id.meta_2, R.id.meta_3, R.id.meta_4, R.id.meta_5
    )
    private val checkIds = intArrayOf(
        R.id.check_0, R.id.check_1, R.id.check_2, R.id.check_3, R.id.check_4, R.id.check_5
    )

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("MM/dd")

    /** Trigger an asynchronous refresh of every placed widget instance. */
    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            if (ids.isNotEmpty()) {
                val tasks = withContext(Dispatchers.IO) {
                    TaskRepository.get(context).getPending()
                }
                ids.forEach { id ->
                    val capacity = capacityFor(context, manager, id)
                    val views = buildViews(context, tasks, capacity)
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }

    /** Synchronous build used by the provider's onUpdate. */
    suspend fun buildForId(context: Context, appWidgetId: Int): RemoteViews {
        val manager = AppWidgetManager.getInstance(context)
        val capacity = capacityFor(context, manager, appWidgetId)
        val tasks = withContext(Dispatchers.IO) { TaskRepository.get(context).getPending() }
        return buildViews(context, tasks, capacity)
    }

    private fun buildViews(
        context: Context,
        tasks: List<Task>,
        capacity: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_content)

        // Tap anywhere → open the app.
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openAppPendingIntent(context)
        )

        val pending = tasks.filter { !it.isCompleted }.take(capacity.coerceAtLeast(1))
        val remaining = tasks.count { !it.isCompleted }

        views.setTextViewText(
            R.id.count_text,
            if (remaining > 0) context.getString(R.string.widget_remaining, remaining)
            else context.getString(R.string.widget_all_done)
        )

        if (pending.isEmpty()) {
            views.setViewVisibility(R.id.empty_text, View.VISIBLE)
            views.setTextViewText(R.id.empty_text, context.getString(R.string.widget_no_tasks))
        } else {
            views.setViewVisibility(R.id.empty_text, View.GONE)
        }

        for (i in 0 until MAX_ROWS) {
            val show = i < pending.size
            views.setViewVisibility(rowIds[i], if (show) View.VISIBLE else View.GONE)
            if (show) {
                val task = pending[i]
                views.setTextViewText(titleIds[i], task.title)
                val meta = task.dueDate?.let { describeTime(it) }
                if (meta != null) {
                    views.setViewVisibility(metaIds[i], View.VISIBLE)
                    views.setTextViewText(metaIds[i], meta)
                } else {
                    views.setViewVisibility(metaIds[i], View.GONE)
                }
                views.setImageViewResource(checkIds[i], R.drawable.widget_check_circle)
            }
        }
        return views
    }

    private fun describeTime(time: LocalDateTime): String {
        val now = LocalDateTime.now()
        return when {
            time.toLocalDate().isEqual(now.toLocalDate()) -> time.format(timeFmt)
            time.toLocalDate().isEqual(now.toLocalDate().plusDays(1)) -> "明 " + time.format(timeFmt)
            time.year == now.year -> time.format(dayFmt)
            else -> time.format(DateTimeFormatter.ofPattern("yy/MM/dd"))
        }
    }

    /** Decide how many task rows fit based on the widget's current height. */
    private fun capacityFor(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int
    ): Int {
        val options = manager.getAppWidgetOptions(appWidgetId) ?: return 2
        val minDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        val maxDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 140)
        val height = minOf(minDp, maxDp).coerceAtLeast(120)
        return when {
            height < 170 -> 2   // small (2x2)
            height < 240 -> 3   // medium (4x2)
            height < 300 -> 4
            else -> 6           // large (4x4)
        }.coerceIn(1, MAX_ROWS)
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
