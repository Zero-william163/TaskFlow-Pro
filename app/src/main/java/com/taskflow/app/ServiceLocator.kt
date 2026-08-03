package com.taskflow.app

import android.content.Context
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.notification.ReminderScheduler
import com.taskflow.app.permission.PermissionManager
import com.taskflow.app.update.UpdateChecker

/**
 * Tiny manual dependency container. Avoids a DI framework while keeping construction
 * in a single testable place. All members are application-scoped singletons.
 */
object ServiceLocator {

    @Volatile private var initialized = false

    lateinit var taskRepository: TaskRepository
        private set
    lateinit var categoryRepository: CategoryRepository
        private set
    lateinit var userPreferences: UserPreferences
        private set
    lateinit var reminderScheduler: ReminderScheduler
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var updateChecker: UpdateChecker
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            taskRepository = TaskRepository.get(app)
            categoryRepository = CategoryRepository.get(app)
            userPreferences = UserPreferences.get(app)
            reminderScheduler = ReminderScheduler(app)
            permissionManager = PermissionManager(app)
            updateChecker = UpdateChecker(app)
            initialized = true
        }
    }
}
