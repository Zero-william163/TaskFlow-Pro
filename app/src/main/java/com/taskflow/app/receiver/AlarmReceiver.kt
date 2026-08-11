package com.taskflow.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.service.AlarmService
import com.taskflow.app.util.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 闹钟触发接收器
 *
 * 完全对齐用户提供的模板结构：
 *   - onReceive 中先判断 intent.action == ACTION_DAILY_REMINDER
 *   - hasExtras == true  →  稍后提醒场景：直接使用 Intent extras（eventContent / daysRemaining / targetReached）
 *   - hasExtras == false →  每日闹钟场景：从 Repository 读取任务数据
 *   - 最后统一调用 startAlarmService 启动前台服务
 *   - 每日场景末尾重新注册明天的闹钟
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DAILY_REMINDER = "com.taskflow.app.ACTION_DAILY_REMINDER"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_EVENT_CONTENT = "event_content"
        const val EXTRA_DAYS_REMAINING = "days_remaining"
        const val EXTRA_TARGET_REACHED = "target_reached"
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_REMINDER) return

        Log.d(TAG, "Alarm received at ${System.currentTimeMillis()}")

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) {
            Log.w(TAG, "onReceive: invalid taskId, ignore")
            return
        }

        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                val hasExtras = intent.hasExtra(EXTRA_EVENT_CONTENT)

                if (hasExtras) {
                    // ==================== 稍后提醒：直接使用 Intent extras ====================
                    val eventContent = intent.getStringExtra(EXTRA_EVENT_CONTENT) ?: "任务"
                    val daysRemaining = intent.getLongExtra(EXTRA_DAYS_REMAINING, 0)
                    val targetReached = intent.getBooleanExtra(EXTRA_TARGET_REACHED, false)
                    startAlarmService(context, taskId, eventContent, daysRemaining, targetReached)
                } else {
                    // ==================== 每日闹钟：从 Repository 读取数据 ====================
                    val repository = TaskRepository.get(context)
                    val task = withContext(Dispatchers.IO) { repository.getTask(taskId) }

                    if (task == null) {
                        Log.w(TAG, "task $taskId not found, cancel alarm")
                        AlarmScheduler.cancelTaskReminder(context, taskId)
                        return@launch
                    }
                    if (task.isCompleted) {
                        Log.d(TAG, "task $taskId already completed, cancel alarm")
                        AlarmScheduler.cancelTaskReminder(context, taskId)
                        return@launch
                    }

                    val daysRemaining = daysRemainingForTask(task)
                    val targetReached = isTargetReachedForTask(task)
                    val eventContent = task.title.ifEmpty { "任务" }

                    startAlarmService(context, taskId, eventContent, daysRemaining, targetReached)

                    // 重新注册明天的闹钟（仅 DAILY 模式）
                    AlarmScheduler.rescheduleDailyAfterFire(context, task)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm", e)
            } finally {
                pending.finish()
            }
        }
    }

    // ==================== 统一启动 AlarmService 前台服务 ====================

    private fun startAlarmService(
        context: Context,
        taskId: Long,
        eventContent: String,
        daysRemaining: Long,
        targetReached: Boolean
    ) {
        val alarmIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmService.EXTRA_TASK_ID, taskId)
            putExtra(AlarmService.EXTRA_EVENT_CONTENT, eventContent)
            putExtra(AlarmService.EXTRA_DAYS_REMAINING, daysRemaining)
            putExtra(AlarmService.EXTRA_TARGET_REACHED, targetReached)
        }
        ContextCompat.startForegroundService(context, alarmIntent)
        Log.d(TAG, "startAlarmService: taskId=$taskId, event=$eventContent, days=$daysRemaining")
    }

    // ==================== TaskFlow 业务适配：将 Task 字段映射为模板的 daysRemaining / targetReached ====================

    private fun daysRemainingForTask(task: Task): Long {
        val due = task.dueDate ?: return 0L
        val today = java.time.LocalDate.now()
        return today.until(due.toLocalDate()).days.toLong()
    }

    private fun isTargetReachedForTask(task: Task): Boolean {
        val due = task.dueDate ?: return false
        return !java.time.LocalDate.now().isBefore(due.toLocalDate())
    }
}
