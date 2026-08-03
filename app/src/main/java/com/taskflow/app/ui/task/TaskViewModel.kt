package com.taskflow.app.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.notification.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persistence gateway for add / edit / detail. Form input state lives in the
 * composables; this ViewModel owns the categories list and all task mutations
 * (including reminder scheduling).
 */
class TaskViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    val categories: StateFlow<List<Category>> =
        categoryRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun observeTask(id: Long) = taskRepository.observeTask(id)

    suspend fun getTask(id: Long): Task? = taskRepository.getTask(id)

    fun saveTask(task: Task, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            if (task.id == 0L) {
                val id = taskRepository.addTask(task)
                if (task.reminderTime != null) reminderScheduler.schedule(task.copy(id = id))
                onDone(id)
            } else {
                taskRepository.updateTask(task)
                reminderScheduler.cancel(task.id)
                if (task.reminderTime != null && !task.isCompleted) reminderScheduler.schedule(task)
                onDone(task.id)
            }
        }
    }

    fun deleteTask(task: Task, onDone: () -> Unit) {
        viewModelScope.launch {
            reminderScheduler.cancel(task.id)
            taskRepository.deleteTask(task.id)
            onDone()
        }
    }

    fun setCompleted(task: Task, completed: Boolean) {
        viewModelScope.launch {
            taskRepository.setCompleted(task, completed)
            if (completed) reminderScheduler.cancel(task.id)
            else task.reminderTime?.let { reminderScheduler.schedule(task) }
        }
    }
}
