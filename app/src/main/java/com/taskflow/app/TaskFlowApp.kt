package com.taskflow.app

import android.app.Application
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.notification.NotificationHelper
import com.taskflow.app.notification.ReminderScheduler

class TaskFlowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Touch the database early so the seeding callback runs and channels exist.
        TaskDatabase.get(this)
        NotificationHelper(this)
        // Ensure reminder intents resolve (registers the manifest receiver implicitly).
        ReminderScheduler(this)
    }
}
