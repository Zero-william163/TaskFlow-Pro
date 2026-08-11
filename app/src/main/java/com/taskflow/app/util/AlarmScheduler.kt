package com.taskflow.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.taskflow.app.data.model.ReminderMode
import com.taskflow.app.data.model.Task
import com.taskflow.app.receiver.AlarmReceiver
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 闹钟调度器
 *
 * 结构对齐用户提供的 Countdown 模板：
 *   - scheduleTaskReminder / cancelTaskReminder  任务级提醒（TaskFlow 多任务版"每日闹钟"）
 *   - scheduleOneShotAlarm  一次性稍后提醒（snooze 场景，对应用户模板的同名方法）
 *   - cancelAlarm           统一取消入口（对应用户模板的同名方法）
 *   - scheduleExactAlarmCompat / scheduleInexactAlarmFallback  兼容性调度逻辑完全对齐模板
 *   - canScheduleExactAlarms / openExactAlarmSettings  精确闹钟权限检测与跳转
 */
object AlarmScheduler {

    private const val REQUEST_CODE_TASK_BASE = 10_000
    private const val REQUEST_CODE_ONESHOT_BASE = 1_000_000
    private const val TAG = "AlarmScheduler"

    // ==================== 任务级提醒（TaskFlow 多任务版"每日闹钟"） ====================

    fun scheduleTaskReminder(context: Context, task: Task) {
        val reminder = task.reminderTime ?: return
        if (task.isCompleted) {
            cancelTaskReminder(context, task.id)
            return
        }
        val triggerAt = nextTrigger(task, reminder)
        if (triggerAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelTaskReminder(context, task.id)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DAILY_REMINDER
            putExtra(AlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeForTask(task.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d(TAG, "Scheduling task alarm(taskId=${task.id}) at ${formatDateTime(triggerAt)}")

        try {
            scheduleExactAlarmCompat(alarmManager, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling task alarm", e)
            scheduleInexactAlarmFallback(alarmManager, triggerAt, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Exception scheduling task alarm", e)
        }
    }

    fun cancelTaskReminder(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DAILY_REMINDER
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeForTask(taskId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        // 同时取消该任务对应的稍后提醒
        cancelOneShotForTask(context, taskId)
    }

    /**
     * 每日重复任务触发后，重新注册明天同一时间的闹钟。
     * 对齐用户模板 AlarmReceiver 中"重新注册明天的闹钟"的调用模式。
     */
    fun rescheduleDailyAfterFire(context: Context, task: Task): Long? {
        if (task.reminderMode != ReminderMode.DAILY) return null
        if (task.isCompleted) return null
        val reminder = task.reminderTime ?: return null
        val tomorrow = LocalDateTime.now().plusDays(1)
            .withHour(reminder.hour)
            .withMinute(reminder.minute)
            .withSecond(0).withNano(0)
        task.dueDate?.let { due ->
            if (tomorrow.isAfter(due)) return null
        }
        val triggerAt = tomorrow.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DAILY_REMINDER
            putExtra(AlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeForTask(task.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            scheduleExactAlarmCompat(am, triggerAt, pendingIntent)
            Log.d(TAG, "rescheduleDaily(taskId=${task.id}) → ${formatDateTime(triggerAt)}")
        } catch (_: SecurityException) {
            scheduleInexactAlarmFallback(am, triggerAt, pendingIntent)
        }
        return triggerAt
    }

    // ==================== 一次性闹钟（稍后提醒用） ====================

    fun scheduleOneShotAlarm(
        context: Context,
        triggerTimeMillis: Long,
        taskId: Long,
        eventContent: String = "",
        daysRemaining: Long = 0,
        targetReached: Boolean = false
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelOneShotForTask(context, taskId)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DAILY_REMINDER
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlarmReceiver.EXTRA_EVENT_CONTENT, eventContent)
            putExtra(AlarmReceiver.EXTRA_DAYS_REMAINING, daysRemaining)
            putExtra(AlarmReceiver.EXTRA_TARGET_REACHED, targetReached)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeForOneShot(taskId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            scheduleExactAlarmCompat(alarmManager, triggerTimeMillis, pendingIntent)
            Log.d(TAG, "scheduleOneShotAlarm(taskId=$taskId) → ${formatDateTime(triggerTimeMillis)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule one-shot alarm", e)
            scheduleInexactAlarmFallback(alarmManager, triggerTimeMillis, pendingIntent)
        }
    }

    /** 便利方法：稍后提醒 N 分钟 */
    fun scheduleSnoozeOneShot(context: Context, taskId: Long, snoozeMinutes: Long = 5L) {
        val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60_000L
        scheduleOneShotAlarm(context, triggerAt, taskId)
    }

    fun cancelAlarm(context: Context) {
        // 统一取消：无需区分任务/稍后提醒（保持用户模板的同名方法入口）
        // 由于 TaskFlow 是多任务，外部调用此方法应配合 cancelTaskReminder(id) 使用
        Log.d(TAG, "cancelAlarm called (no-op: use cancelTaskReminder per taskId)")
    }

    private fun cancelOneShotForTask(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_DAILY_REMINDER
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeForOneShot(taskId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    // ==================== 兼容性调度（完全对齐用户模板的 switch 结构） ====================

    private fun scheduleExactAlarmCompat(
        alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent
    ) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerTime, pendingIntent), pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }
            else -> {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        }
    }

    private fun scheduleInexactAlarmFallback(
        alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback scheduling also failed", e)
        }
    }

    // ==================== 精确闹钟权限检查 & 跳转（完全对齐用户模板） ====================

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ==================== Private helpers ====================

    private fun requestCodeForTask(taskId: Long): Int =
        (REQUEST_CODE_TASK_BASE + taskId).toInt()

    private fun requestCodeForOneShot(taskId: Long): Int =
        (REQUEST_CODE_ONESHOT_BASE + taskId).toInt()

    private fun nextTrigger(task: Task, reminder: LocalDateTime): Long {
        val base = reminder.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (task.reminderMode == ReminderMode.DAILY && base <= System.currentTimeMillis()) {
            var cursor = LocalDateTime.now()
                .withHour(reminder.hour)
                .withMinute(reminder.minute)
                .withSecond(0).withNano(0)
            if (!cursor.isAfter(LocalDateTime.now())) cursor = cursor.plusDays(1)
            task.dueDate?.let { if (cursor.isAfter(it)) return base }
            return cursor.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return base
    }

    fun formatDateTime(ms: Long): String {
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        return java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .toLocalDateTime().format(fmt)
    }
}
