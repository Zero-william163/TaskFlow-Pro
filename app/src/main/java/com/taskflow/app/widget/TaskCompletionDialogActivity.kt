package com.taskflow.app.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.taskflow.app.R
import com.taskflow.app.ServiceLocator
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TaskCompleteDialog"

/**
 * 轻量级透明 Activity，用于小组件任务卡片点击确认。
 *
 * 使用 [Theme.TaskFlow.Dialog]（透明、浮动、无标题），
 * 看起来像直接在桌面上弹出的 Dialog，而非打开整个 App。
 *
 * 流程：
 * 1. Widget 卡片点击 → PendingIntent 启动此 Activity，携带 [EXTRA_TASK_ID]
 * 2. 后台线程从 Room 获取任务标题
 * 3. 显示 AlertDialog：「是否将「task title」标记为已完成？」
 * 4. 确认 → 数据库标记完成 → 刷新所有 Widget → finish()
 * 5. 取消 / 点击空白 → 直接 finish()
 */
class TaskCompletionDialogActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 确保 ServiceLocator 已初始化（App 进程可能被杀后重启）
        ServiceLocator.init(applicationContext)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        Log.d(TAG, "onCreate: taskId=$taskId")

        if (taskId <= 0L) {
            Log.w(TAG, "onCreate: invalid taskId, finishing")
            finish()
            return
        }

        scope.launch {
            val task = withContext(Dispatchers.IO) {
                TaskRepository.get(applicationContext).getTask(taskId)
            }

            if (task == null) {
                Log.w(TAG, "task $taskId not found, finishing")
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            if (task.isCompleted) {
                Log.d(TAG, "task $taskId already completed, finishing")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@TaskCompletionDialogActivity,
                        R.string.widget_mark_already_done,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                showConfirmDialog(taskId, task.title)
            }
        }
    }

    private fun showConfirmDialog(taskId: Long, taskTitle: String) {
        val displayTitle = taskTitle.ifBlank { getString(R.string.widget_mark_default_task) }

        AlertDialog.Builder(this)
            .setTitle(R.string.widget_mark_complete_title)
            .setMessage(getString(R.string.widget_mark_complete_message, displayTitle))
            .setPositiveButton(R.string.widget_mark_complete_yes) { _, _ ->
                Log.d(TAG, "confirm: marking task $taskId as completed")
                scope.launch {
                    val repo = TaskRepository.get(applicationContext)
                    val task = withContext(Dispatchers.IO) { repo.getTask(taskId) }
                    if (task != null) {
                        withContext(Dispatchers.IO) {
                            repo.setCompleted(task, true)
                        }
                        Log.d(TAG, "confirm: task $taskId marked completed, refreshing widget")
                        refreshWidget(applicationContext)
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton(R.string.widget_mark_complete_no) { _, _ ->
                Log.d(TAG, "cancel: user dismissed")
                finish()
            }
            .setOnCancelListener {
                // 点击空白区域或按返回键
                Log.d(TAG, "onCancel: user tapped outside / pressed back")
                finish()
            }
            .show()
    }

    /**
     * 通知所有 Widget 实例其 ListView 数据已变更，
     * 使已完成的任务立即从 Widget 列表中消失。
     */
    private fun refreshWidget(context: Context) {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, TaskWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(provider)
            Log.d(TAG, "refreshWidget: notifying ${ids.size} widget(s)")
            for (id in ids) {
                manager.notifyAppWidgetViewDataChanged(id, R.id.task_list)
            }
            WidgetHelper.refresh(context)
        } catch (e: Throwable) {
            Log.e(TAG, "refreshWidget failed", e)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
