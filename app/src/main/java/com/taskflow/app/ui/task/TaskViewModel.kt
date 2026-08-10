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
 *
 * The "cancel old alarm → persist → schedule new alarm" sequence is guaranteed
 * during saves so edits never orphan an AlarmManager slot.
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

    /**
     * Persist the task and wire up reminders. Callbacks are called on the Main
     * dispatcher and carry the resulting task id + whether this is a new insert.
     */
    fun saveTask(task: Task, onDone: (id: Long, isNew: Boolean) -> Unit) {
        viewModelScope.launch {
            val isNew = task.id == 0L
            // Drop any previous alarm we may have set (both edit and first-save paths).
            if (!isNew) reminderScheduler.cancel(task.id)

            val resultingId = if (isNew) {
                taskRepository.addTask(task)
            } else {
                taskRepository.updateTask(task)
                task.id
            }

            // Wire the new reminder with the saved task. When reminder is disabled
            // or the task is complete we explicitly leave the slot empty.
            val rebuilt = task.copy(id = resultingId)
            if (!rebuilt.isCompleted && rebuilt.reminderTime != null) {
                reminderScheduler.schedule(rebuilt)
            }

            onDone(resultingId, isNew)
        }
    }

    /**
     * Pin a task to the widget. Suspending variant so callers can chain the
     * DB write → widget refresh on the same coroutine (no race conditions).
     */
    suspend fun pinToWidgetAndThen(taskId: Long, onDone: suspend () -> Unit = {}) {
        taskRepository.setPinnedToWidget(taskId, true)
        onDone()
    }

    /** Legacy async wrapper for non-suspending call sites. */
    fun pinToWidget(taskId: Long, onDone: suspend () -> Unit = {}) {
        viewModelScope.launch {
            pinToWidgetAndThen(taskId, onDone)
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
            if (completed) {
                reminderScheduler.cancel(task.id)
            } else {
                task.reminderTime?.let { reminderScheduler.schedule(task) }
            }
        }
    }
}
