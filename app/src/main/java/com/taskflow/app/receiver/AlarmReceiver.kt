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
 * 核心职责（v2.8.0 重构）：
 *   1. **Alarm Suppression (Method B — Receiver 校验拦截)**
 *      响铃前优先查询数据库：若任务 `isCompleted == true` 或周期任务
 *      `lastCompletedDate == 今天`，直接 return 丢弃，绝不触发响铃与全屏 UI。
 *
 *   2. **Alarm Aggregation（同时间多任务合并提醒）**
 *      不针对每个任务单独弹出 Activity。根据触发任务的 reminderTime
 *      查询所有同一时刻设定的未完成任务，将任务 ID/标题数组传递给
 *      AlarmService → AlarmActivity，一次性展示合并后的任务列表。
 *
 *   3. **跨应用全屏提醒**
 *      通过 AlarmService 构建高优先级 Notification + setFullScreenIntent，
 *      在第三方 App 之上/锁屏之上拉起 AlarmActivity。
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
                    // Snooze alarms still need suppression check.
                    val repository = TaskRepository.get(context)
                    val task = withContext(Dispatchers.IO) { repository.getTask(taskId) }
                    if (task != null && shouldSuppressAlarm(task)) {
                        Log.d(TAG, "Snooze alarm for task $taskId suppressed (already completed)")
                        return@launch
                    }
                    startAlarmService(context, longArrayOf(taskId), arrayOf(eventContent), daysRemaining, targetReached)
                } else {
                    // ==================== 每日闹钟：从 Repository 读取数据 ====================
                    val repository = TaskRepository.get(context)
                    val task = withContext(Dispatchers.IO) { repository.getTask(taskId) }

                    if (task == null) {
                        Log.w(TAG, "task $taskId not found, cancel alarm")
                        AlarmScheduler.cancelTaskReminder(context, taskId)
                        return@launch
                    }

                    // ===== Alarm Suppression (Method B): Receiver 校验拦截 =====
                    // If the task is permanently completed OR a recurring task
                    // already checked off today, drop the alarm silently.
                    if (shouldSuppressAlarm(task)) {
                        Log.d(TAG, "Alarm suppressed for task $taskId (isCompleted=${task.isCompleted}, " +
                            "isCompletedToday=${task.isCompletedToday})")
                        AlarmScheduler.cancelTaskReminder(context, taskId)
                        return@launch
                    }

                    // ===== Alarm Aggregation: 合并同一时间的所有未完成任务 =====
                    val aggregatedTasks = withContext(Dispatchers.IO) {
                        repository.getTasksAtSameReminderTime(task)
                    }
                    Log.d(TAG, "Aggregation: triggered by task $taskId, found ${aggregatedTasks.size} tasks at same time")

                    val taskIds = aggregatedTasks.map { it.id }.toLongArray()
                    val taskNames = aggregatedTasks.map { it.title.ifEmpty { "任务" } }.toTypedArray()

                    val daysRemaining = daysRemainingForTask(task)
                    val targetReached = isTargetReachedForTask(task)

                    startAlarmService(context, taskIds, taskNames, daysRemaining, targetReached)

                    // 重新注册明天的闹钟（仅 DAILY 模式）— 对每个聚合任务执行
                    aggregatedTasks.forEach { aggregatedTask ->
                        AlarmScheduler.rescheduleDailyAfterFire(context, aggregatedTask)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling alarm", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Alarm Suppression 判定：永久完成 或 周期任务今日已打卡 → 抑制响铃。
     */
    private fun shouldSuppressAlarm(task: Task): Boolean {
        if (task.isCompleted) return true
        // Recurring task checked off today: lastCompletedDate == today
        if (task.isRecurring && task.isCompletedToday) return true
        return false
    }

    // ==================== 统一启动 AlarmService 前台服务 ====================

    private fun startAlarmService(
        context: Context,
        taskIds: LongArray,
        taskNames: Array<String>,
        daysRemaining: Long,
        targetReached: Boolean
    ) {
        val alarmIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmService.EXTRA_TASK_IDS, taskIds)
            putExtra(AlarmService.EXTRA_TASK_NAMES, taskNames)
            putExtra(AlarmService.EXTRA_DAYS_REMAINING, daysRemaining)
            putExtra(AlarmService.EXTRA_TARGET_REACHED, targetReached)
        }
        ContextCompat.startForegroundService(context, alarmIntent)
        Log.d(TAG, "startAlarmService: taskIds=${taskIds.toList()}, names=${taskNames.toList()}, days=$daysRemaining")
    }

    // ==================== TaskFlow 业务适配 ====================

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
