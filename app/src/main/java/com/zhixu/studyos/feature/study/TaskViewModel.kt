package com.zhixu.studyos.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.data.repository.SleepSnapshot
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.history.TaskEventView
import com.zhixu.studyos.core.history.TaskHistoryRepository
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.StudyDayPlan
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskConflict
import com.zhixu.studyos.core.task.StudyDayPlanner
import com.zhixu.studyos.core.task.TaskConflictDetector
import com.zhixu.studyos.core.task.TaskPlanner
import com.zhixu.studyos.core.sleep.SleepInterval
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import com.zhixu.studyos.core.sleep.formatSleepMinute
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TaskViewMode { DAY, WEEK }

data class TaskWeekDaySummary(
    val date: LocalDate,
    val plan: StudyDayPlan,
) {
    val totalCount: Int get() = plan.tasks.size
    val completedCount: Int get() = plan.tasks.count { it.task.isCompleted }
    val courseCount: Int get() = plan.timeline.count { it.kind == com.zhixu.studyos.core.model.StudyBlockKind.COURSE }
}

data class TaskUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val viewMode: TaskViewMode = TaskViewMode.DAY,
    val allTasks: List<StudyTask> = emptyList(),
    val dayPlan: StudyDayPlan? = null,
    val weekDays: List<TaskWeekDaySummary> = emptyList(),
    val backlog: List<StudyTask> = emptyList(),
    val conflictsByTaskId: Map<Long, List<TaskConflict>> = emptyMap(),
    val scheduleSnapshot: ScheduleSnapshot? = null,
    val errorMessage: String? = null,
    val scheduleWarning: String? = null,
    val sleepConflicts: List<String> = emptyList(),
    val notice: String? = null,
)

private data class TaskLoad(val tasks: List<StudyTask> = emptyList(), val error: String? = null)
private data class ScheduleLoad(val snapshot: ScheduleSnapshot? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
data class TaskHistoryUiState(
    val visible: Boolean = false,
    val isLoading: Boolean = false,
    val taskId: Long? = null,
    val events: List<TaskEventView> = emptyList(),
    val errorMessage: String? = null,
)

class TaskViewModel(
    private val repository: TaskRepository,
    private val scheduleRepository: ScheduleRepository,
    private val clock: Clock,
    private val historyRepository: TaskHistoryRepository? = null,
    private val sleepRepository: SleepRepository? = null,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val requestedDate = MutableStateFlow<LocalDate?>(null)
    private val viewMode = MutableStateFlow(TaskViewMode.DAY)
    private val notice = MutableStateFlow<String?>(null)
    private val sleepSnapshot = MutableStateFlow(SleepSnapshot())
    private val _historyState = MutableStateFlow(TaskHistoryUiState())
    val historyState: StateFlow<TaskHistoryUiState> = _historyState

    private val taskLoad = refresh.flatMapLatest {
        repository.observeAllTasks()
            .map { TaskLoad(tasks = it) }
            .catch { emit(TaskLoad(error = "无法读取本地任务，请重试。")) }
    }
    private val scheduleLoad = refresh.flatMapLatest {
        scheduleRepository.observeSnapshot()
            .map { ScheduleLoad(snapshot = it) }
            .catch { emit(ScheduleLoad(error = "课表读取失败，课程冲突提醒暂不可用。")) }
    }

    init {
        if (sleepRepository != null) {
            viewModelScope.launch {
                sleepRepository.observeSnapshot().collect { sleepSnapshot.value = it }
            }
        }
    }

    private val noticeAndSleep = combine(notice, sleepSnapshot) { noticeValue, sleep ->
        noticeValue to sleep
    }

    val uiState: StateFlow<TaskUiState> = combine(
        taskLoad,
        scheduleLoad,
        requestedDate,
        viewMode,
        noticeAndSleep,
    ) { tasksLoad, scheduleState, dateRequest, mode, noticeSleep ->
        val operationNotice = noticeSleep.first
        val sleep = noticeSleep.second
        val zoneId = scheduleState.snapshot?.semester?.zoneId ?: clock.zone
        val today = LocalDate.now(clock.withZone(zoneId))
        val selectedDate = dateRequest ?: today
        if (tasksLoad.error != null) {
            TaskUiState(
                isLoading = false,
                today = today,
                selectedDate = selectedDate,
                zoneId = zoneId,
                viewMode = mode,
                scheduleSnapshot = scheduleState.snapshot,
                errorMessage = tasksLoad.error,
                scheduleWarning = scheduleState.error,
                notice = operationNotice,
            )
        } else {
            buildState(
                tasks = tasksLoad.tasks,
                schedule = scheduleState.snapshot,
                scheduleError = scheduleState.error,
                today = today,
                selectedDate = selectedDate,
                mode = mode,
                notice = operationNotice,
                sleep = sleep,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TaskUiState(today = LocalDate.now(clock), selectedDate = LocalDate.now(clock)),
    )

    fun retry() {
        refresh.value += 1
    }

    fun selectDate(date: LocalDate) {
        requestedDate.value = date
    }

    fun previousDay() {
        requestedDate.value = uiState.value.selectedDate.minusDays(1)
    }

    fun nextDay() {
        requestedDate.value = uiState.value.selectedDate.plusDays(1)
    }

    fun today() {
        requestedDate.value = uiState.value.today
    }

    fun tomorrow(): LocalDate = uiState.value.today.plusDays(1)

    fun setViewMode(mode: TaskViewMode) {
        viewMode.value = mode
    }

    fun consumeNotice() {
        notice.value = null
    }

    fun openTaskHistory(taskId: Long) {
        val repository = historyRepository ?: return
        _historyState.value = TaskHistoryUiState(visible = true, isLoading = true, taskId = taskId)
        viewModelScope.launch {
            runCatching { repository.observeTaskHistory(taskId).first() }
                .onSuccess { events ->
                    _historyState.value = TaskHistoryUiState(
                        visible = true,
                        isLoading = false,
                        taskId = taskId,
                        events = events,
                    )
                }
                .onFailure {
                    _historyState.value = TaskHistoryUiState(
                        visible = true,
                        isLoading = false,
                        taskId = taskId,
                        errorMessage = it.message ?: "无法读取任务历史",
                    )
                }
        }
    }

    fun closeTaskHistory() {
        _historyState.value = TaskHistoryUiState()
    }

    fun deleteTaskHistory(taskId: Long) {
        val repository = historyRepository ?: return
        viewModelScope.launch {
            runCatching { repository.deleteTaskHistory(taskId) }
                .onSuccess {
                    notice.value = "该任务的本地历史已永久删除"
                    _historyState.value = TaskHistoryUiState()
                }
                .onFailure { notice.value = it.message ?: "删除任务历史失败" }
        }
    }

    fun saveTask(task: StudyTask, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val previewConflicts = previewConflictMessages(task)
                val result = repository.saveTask(task)
                val message = if (result.createdTaskIds.size > 1) {
                    "已创建 ${result.createdTaskIds.size} 个周期任务"
                } else {
                    "任务已保存"
                }
                val conflictSuffix = if (previewConflicts > 0) {
                    "；检测到 $previewConflicts 处课程时间冲突"
                } else {
                    ""
                }
                notice.value = message + conflictSuffix
                onResult(null)
            }.onFailure {
                onResult(it.message?.takeIf(String::isNotBlank) ?: "任务保存失败")
            }
        }
    }

    fun deleteTask(taskId: Long, wholeSeries: Boolean = false) {
        val task = uiState.value.dayPlan?.tasks?.firstOrNull { it.task.id == taskId }?.task
            ?: uiState.value.backlog.firstOrNull { it.id == taskId }
        viewModelScope.launch {
            runCatching {
                if (wholeSeries && task?.seriesId != null) {
                    repository.deleteSeries(task.seriesId)
                } else {
                    repository.deleteTask(taskId)
                }
            }.onSuccess {
                notice.value = if (wholeSeries) "周期任务已删除" else "任务已删除"
            }.onFailure {
                notice.value = it.message ?: "删除失败"
            }
        }
    }

    fun setTaskCompleted(taskId: Long, completed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setTaskCompleted(taskId, completed) }
                .onSuccess { notice.value = if (completed) "任务已完成" else "已取消完成" }
                .onFailure { notice.value = it.message ?: "更新任务失败" }
        }
    }

    fun startTask(taskId: Long) {
        viewModelScope.launch {
            runCatching { repository.startTask(taskId) }
                .onSuccess { notice.value = "任务已开始" }
                .onFailure { notice.value = it.message ?: "无法开始任务" }
        }
    }

    fun pauseTask(taskId: Long) {
        viewModelScope.launch {
            runCatching { repository.pauseTask(taskId) }
                .onSuccess { notice.value = "任务已暂停，回到已安排" }
                .onFailure { notice.value = it.message ?: "无法暂停任务" }
        }
    }

    fun setSessionLocked(taskId: Long, sessionId: Long, locked: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setSessionLocked(taskId, sessionId, locked) }
                .onSuccess {
                    notice.value = if (locked) "学习时段已锁定，自动排程不会覆盖" else "学习时段已解锁"
                }
                .onFailure { notice.value = it.message ?: "更新锁定状态失败" }
        }
    }

    fun postponeTask(taskId: Long, days: Long = 1L) {
        val schedule = uiState.value.scheduleSnapshot
        viewModelScope.launch {
            runCatching { repository.postponeTask(taskId, days) }
                .onSuccess { task ->
                    val conflicts = schedule?.let {
                        TaskConflictDetector.findConflicts(task, it, clock.zone).size
                    } ?: 0
                    val suffix = if (conflicts > 0) "；新时间与课程冲突 $conflicts 处" else ""
                    notice.value = "任务已延期 $days 天$suffix"
                }
                .onFailure { notice.value = it.message ?: "延期失败" }
        }
    }

    fun moveTask(taskId: Long, direction: Int) {
        val ordered = uiState.value.dayPlan?.tasks?.map { it.task.id }.orEmpty()
        val from = ordered.indexOf(taskId)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, ordered.lastIndex)
        if (to == from) return
        val reordered = ordered.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
        viewModelScope.launch {
            runCatching { repository.reorderTasks(reordered) }
                .onFailure { notice.value = it.message ?: "无法保存任务顺序" }
        }
    }

    private fun buildState(
        tasks: List<StudyTask>,
        schedule: ScheduleSnapshot?,
        scheduleError: String?,
        today: LocalDate,
        selectedDate: LocalDate,
        mode: TaskViewMode,
        notice: String?,
        sleep: SleepSnapshot,
    ): TaskUiState {
        fun intervalsFor(date: LocalDate): List<SleepInterval> =
            SleepProtectionEngine.intervalsOn(date, sleep.schedule, sleep.overrides)

        val dayTasks = TaskPlanner.dayEntries(tasks, selectedDate)
        val dayPlan = StudyDayPlanner.build(
            selectedDate,
            dayTasks,
            schedule,
            clock.zone,
            intervalsFor(selectedDate),
        )
        val monday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDays = (0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            val weekTasks = TaskPlanner.dayEntries(tasks, date)
            TaskWeekDaySummary(
                date = date,
                plan = StudyDayPlanner.build(
                    date,
                    weekTasks,
                    schedule,
                    clock.zone,
                    intervalsFor(date),
                ),
            )
        }
        val sleepConflicts = tasks
            .filterNot(StudyTask::isCompleted)
            .flatMap { task ->
                task.sessionsVisibleOn(selectedDate).mapNotNull { segment ->
                    intervalsFor(selectedDate).firstOrNull { interval ->
                        segment.startMinute < interval.endMinuteExclusive &&
                            interval.startMinute < segment.endMinuteExclusive
                    }?.let { interval ->
                        "「${task.title}」与睡眠保护重叠 " +
                            "${formatSleepMinute(maxOf(segment.startMinute, interval.startMinute))}–" +
                            "${formatSleepMinute(minOf(segment.endMinuteExclusive, interval.endMinuteExclusive))}"
                    }
                }
            }
            .distinct()
        val backlog = TaskPlanner.backlogTasks(tasks, today)
        val conflicts = TaskConflictDetector.findAll(
            tasks = tasks.filterNot(StudyTask::isCompleted),
            schedule = schedule,
            fallbackZoneId = clock.zone,
        )
        return TaskUiState(
            isLoading = false,
            today = today,
            selectedDate = selectedDate,
            zoneId = schedule?.semester?.zoneId ?: clock.zone,
            viewMode = mode,
            allTasks = tasks,
            dayPlan = dayPlan,
            weekDays = weekDays,
            backlog = backlog,
            conflictsByTaskId = conflicts,
            scheduleSnapshot = schedule,
            scheduleWarning = scheduleError,
            sleepConflicts = sleepConflicts,
            notice = notice,
        )
    }

    private fun previewConflictMessages(task: StudyTask): Int {
        val schedule = uiState.value.scheduleSnapshot ?: return 0
        if (task.isCompleted) return 0
        val preview = if (task.id == 0L) {
            TaskPlanner.expandForCreation(task.copy(id = 0), "preview", firstSortOrder = 0)
        } else {
            listOf(task.copy(id = task.id))
        }
        return preview.sumOf { occurrence ->
            TaskConflictDetector.findConflicts(occurrence, schedule, clock.zone).size
        }
    }
}
