package com.taskflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.taskflow.app.ServiceLocator
import com.taskflow.app.ui.calendar.CalendarViewModel
import com.taskflow.app.ui.home.HomeViewModel
import com.taskflow.app.ui.permission.PermissionViewModel
import com.taskflow.app.ui.settings.SettingsViewModel
import com.taskflow.app.ui.stats.StatsViewModel
import com.taskflow.app.ui.task.TaskViewModel
import com.taskflow.app.ui.update.UpdateViewModel

/**
 * Single [ViewModelProvider.Factory] that constructs every screen ViewModel from the
 * [ServiceLocator]. Keeps construction centralized without a DI framework.
 */
object AppViewModelFactory : ViewModelProvider.Factory {

    private val locator get() = ServiceLocator

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(locator.taskRepository, locator.categoryRepository, locator.reminderScheduler, locator.focusHistoryRepository) as T
            modelClass.isAssignableFrom(TaskViewModel::class.java) ->
                TaskViewModel(locator.taskRepository, locator.categoryRepository, locator.reminderScheduler) as T
            modelClass.isAssignableFrom(CalendarViewModel::class.java) ->
                CalendarViewModel(locator.taskRepository, locator.categoryRepository, locator.reminderScheduler) as T
            modelClass.isAssignableFrom(StatsViewModel::class.java) ->
                StatsViewModel(locator.taskRepository, locator.categoryRepository, locator.focusHistoryRepository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(locator.userPreferences, locator.updateChecker, locator.soundEffectManager) as T
            modelClass.isAssignableFrom(UpdateViewModel::class.java) ->
                UpdateViewModel(locator.updateChecker, locator.userPreferences) as T
            modelClass.isAssignableFrom(PermissionViewModel::class.java) ->
                PermissionViewModel(locator.permissionManager) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
