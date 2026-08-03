package com.taskflow.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Restores scheduled reminders after device reboot or app update, and forces a widget
 * refresh so the home-screen component reflects persisted data immediately.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = TaskRepository.get(context)
                val scheduler = ReminderScheduler(context)
                val now = LocalDateTime.now()
                val upcoming = withContext(Dispatchers.IO) { repo.getUpcomingReminders(now) }
                upcoming.forEach { scheduler.schedule(it) }
                WidgetHelper.refresh(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
