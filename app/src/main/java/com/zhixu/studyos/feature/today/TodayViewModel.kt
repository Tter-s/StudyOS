package com.zhixu.studyos.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.DailyLogRepository
import com.zhixu.studyos.core.model.DailyTask
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DayTasksUiState(
    val date: LocalDate,
    val tasks: List<DailyTask> = emptyList(),
    val nextDayTasks: List<DailyTask> = emptyList(),
    val summary: String? = null,
    val entertainmentMinutes: Int? = null,
    val message: String? = null,
) {
    val total: Int get() = tasks.size
    val completed: Int get() = tasks.count { it.isCompleted }
    val nextDayTotal: Int get() = nextDayTasks.size
    val nextDayCompleted: Int get() = nextDayTasks.count { it.isCompleted }
}

/**
 * 今日/指定日期的待办。
 *
 * [followToday] 为 true 时，页面回到前台会重新取当天日期；明日待办页固定使用传入日期。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: DailyLogRepository,
    private val clock: Clock,
    initialDate: LocalDate = LocalDate.now(clock),
    private val followToday: Boolean = true,
) : ViewModel() {
    private val date = MutableStateFlow(initialDate)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DayTasksUiState> = date
        .flatMapLatest { day ->
            combine(
                repository.observeTasks(day),
                repository.observeTasks(day.plusDays(1)),
                repository.observeSummary(day),
                repository.observeEntertainment(day),
                message,
            ) { tasks, tomorrowTasks, summary, entertainment, notice ->
                DayTasksUiState(
                    date = day,
                    tasks = tasks,
                    nextDayTasks = tomorrowTasks,
                    summary = summary?.takeIf { it.isNotBlank() },
                    entertainmentMinutes = entertainment,
                    message = notice,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DayTasksUiState(date = initialDate),
        )

    fun refreshDate() {
        if (!followToday) return
        val today = LocalDate.now(clock)
        if (today != date.value) date.value = today
    }

    fun addTask(title: String) {
        runAction { repository.addTask(date.value, title) }
    }

    fun renameTask(taskId: Long, title: String) {
        runAction { repository.renameTask(taskId, title) }
    }

    fun setCompleted(task: DailyTask, completed: Boolean) {
        runAction { repository.setTaskCompleted(task.id, completed) }
    }

    fun deleteTask(taskId: Long) {
        runAction { repository.deleteTask(taskId) }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }
                .onFailure { error -> message.value = error.message ?: "操作未完成，请重试" }
        }
    }
}
