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
 * 对应用户模板中的 AlarmActionReceiver：
 *   - ACTION_STOP_ALARM    → 关闭闹钟（对应用户模板的"关闭"操作）
 *   - ACTION_SNOOZE_ALARM  → 稍后提醒（对应用户模板的"稍后提醒"操作）
 *
 * 所有操作转发到 AlarmService 的静态入口，保证前台服务/媒体/震动的生命周期统一管理。
 */
class AlarmActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_ALARM = "com.taskflow.app.action.STOP_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.taskflow.app.action.SNOOZE_ALARM"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val TAG = "AlarmActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) {
            Log.w(TAG, "onReceive: invalid taskId, action=$action")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_STOP_ALARM -> {
                        Log.d(TAG, "ACTION_STOP_ALARM taskId=$taskId")
                        // 1. 标记任务完成
                        AlarmService.markTaskCompleted(context, taskId)
                        // 2. 停止闹钟（声音/震动/前台通知）
                        AlarmService.stopAlarm(context)
                    }
                    ACTION_SNOOZE_ALARM -> {
                        Log.d(TAG, "ACTION_SNOOZE_ALARM taskId=$taskId")
                        // 统一入口：稍后提醒 5 分钟
                        AlarmService.snoozeAlarm(context, taskId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
