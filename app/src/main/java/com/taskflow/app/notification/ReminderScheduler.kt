package com.taskflow.app.notification

import android.content.Context
import com.taskflow.app.data.model.Task
import com.taskflow.app.util.AlarmScheduler

/**
 * 兼容过渡层：所有调用点（HomeViewModel、TaskViewModel、CalendarViewModel、
 * BootReceiver、ServiceLocator）仍然使用 `ReminderScheduler(context).schedule(task)`，
 * 我们在这里内部转发到新的 util.AlarmScheduler 统一调度器。
 *
 * 目的：对齐用户提供的闹钟模板结构（util.AlarmScheduler / service.AlarmService
 * / receiver.AlarmActionReceiver / receiver.AlarmReceiver / ui.alarm.AlarmActivity
 * 五件套）的同时，保持现有代码调用签名稳定。
 */
class ReminderScheduler(private val context: Context) {

    fun schedule(task: Task) {
        AlarmScheduler.scheduleTaskReminder(context, task)
    }

    fun cancel(taskId: Long) {
        AlarmScheduler.cancelTaskReminder(context, taskId)
    }

    fun rescheduleDaily(task: Task): Long? {
        return AlarmScheduler.rescheduleDailyAfterFire(context, task)
    }
}
