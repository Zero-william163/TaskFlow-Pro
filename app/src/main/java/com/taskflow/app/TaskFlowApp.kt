package com.taskflow.app

import android.app.Application
import android.util.Log
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.notification.NotificationHelper
import com.taskflow.app.notification.ReminderScheduler

class TaskFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 全局异常保护：如果 Application.onCreate() 崩溃，
        // Widget 的 onUpdate() 永远不会执行 → "Problem loading widget"
        // 每个初始化步骤单独 try/catch，确保单点失败不会拖垮整个进程
        safeInit("ServiceLocator") { ServiceLocator.init(this) }
        safeInit("TaskDatabase") { TaskDatabase.get(this) }
        safeInit("NotificationHelper") { NotificationHelper(this) }
        safeInit("ReminderScheduler") { ReminderScheduler(this) }
    }

    private inline fun safeInit(name: String, block: () -> Unit) {
        try {
            block()
            Log.d("TaskFlowApp", "✅ $name init OK")
        } catch (e: Throwable) {
            Log.e("TaskFlowApp", "❌ $name init FAILED (非致命，继续启动)", e)
        }
    }
}
