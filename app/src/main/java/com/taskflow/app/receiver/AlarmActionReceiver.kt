package com.taskflow.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskflow.app.service.AlarmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 通知操作接收器（关闭/稍后提醒）
 *
 * v2.8.0: 支持 aggregated multi-task alarms。
 *   - ACTION_STOP_ALARM    → 标记所有任务完成 + 停止闹钟
 *   - ACTION_SNOOZE_ALARM  → 稍后提醒（5分钟后）
 *
 * 兼容旧版单任务 EXTRA_TASK_ID 和新版多任务 EXTRA_TASK_IDS。
 */
class AlarmActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_ALARM = "com.taskflow.app.action.STOP_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.taskflow.app.action.SNOOZE_ALARM"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_IDS = "extra_task_ids"
        private const val TAG = "AlarmActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Resolve task IDs: prefer the array (v2.8.0), fall back to single id.
        val taskIds: LongArray = if (intent.hasExtra(EXTRA_TASK_IDS)) {
            intent.getLongArrayExtra(EXTRA_TASK_IDS) ?: longArrayOf()
        } else {
            val single = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            if (single == -1L) longArrayOf() else longArrayOf(single)
        }
        if (taskIds.isEmpty()) {
            Log.w(TAG, "onReceive: no taskIds, action=$action")
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_STOP_ALARM -> {
                        Log.d(TAG, "ACTION_STOP_ALARM taskIds=${taskIds.toList()}")
                        // 1. Mark ALL tasks in the batch as completed
                        AlarmService.markAllTasksCompleted(context, taskIds)
                        // 2. Stop the alarm (sound/vibration/foreground notification)
                        AlarmService.stopAlarm(context)
                    }
                    ACTION_SNOOZE_ALARM -> {
                        Log.d(TAG, "ACTION_SNOOZE_ALARM taskIds=${taskIds.toList()}")
                        // Snooze the primary task (first in the batch). The
                        // AlarmScheduler handles re-registration for the others
                        // via rescheduleDailyAfterFire.
                        AlarmService.snoozeAlarm(context, taskIds.first())
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
