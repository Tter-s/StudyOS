package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.MAX_RECURRENCE_OCCURRENCES
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TASK_COLOR_ARGB
import com.zhixu.studyos.core.model.TASK_COMPLETED_COLOR_ARGB
import com.zhixu.studyos.core.model.TaskDayEntry
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.LocalDate

object TaskPlanner {
    /**
     * Materializes a bounded recurrence series once, so every completion/order change has a stable task id.
     * Editing one recurring instance affects only that instance; the series metadata remains visible on each row.
     */
    fun expandForCreation(
        task: StudyTask,
        seriesId: String?,
        firstSortOrder: Int,
    ): List<StudyTask> {
        val status = if (task.sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED
        val base = task.copy(
            id = 0,
            status = status,
            completedAt = null,
            seriesId = null,
            sortOrder = firstSortOrder,
            sessions = task.sessions.map { it.copy(id = 0) },
        )
        if (task.recurrence == TaskRecurrence.NONE) return listOf(base)

        require(task.sessions.isNotEmpty()) { "周期任务需要至少一个学习会话" }
        val until = requireNotNull(task.recurrenceUntil) { "周期任务需要结束日期" }
        val firstSessionDate = task.sessions.minOf(TaskSession::date)
        require(!until.isBefore(firstSessionDate)) { "周期结束日期不能早于第一次学习" }
        val periodDays = when (task.recurrence) {
            TaskRecurrence.DAILY -> task.recurrenceInterval.toLong()
            TaskRecurrence.WEEKLY -> task.recurrenceInterval.toLong() * 7L
            TaskRecurrence.NONE -> 1L
        }
        val requiredSeriesId = requireNotNull(seriesId) { "周期任务缺少系列标识" }

        val result = mutableListOf<StudyTask>()
        var offsetDays = 0L
        while (firstSessionDate.plusDays(offsetDays) <= until) {
            require(result.size < MAX_RECURRENCE_OCCURRENCES) { "周期任务超过生成上限" }
            result += base.copy(
                seriesId = requiredSeriesId,
                recurrence = task.recurrence,
                recurrenceInterval = task.recurrenceInterval,
                recurrenceUntil = until,
                dueDate = task.dueDate?.plusDays(offsetDays),
                sortOrder = firstSortOrder + result.size,
                sessions = task.sessions.map { session ->
                    session.copy(id = 0, date = session.date.plusDays(offsetDays))
                },
            )
            offsetDays += periodDays
        }
        return result
    }

    fun withPlanStatus(task: StudyTask): StudyTask = TaskStateMachine.afterReplan(task)

    fun dayEntries(tasks: List<StudyTask>, date: LocalDate): List<TaskDayEntry> =
        tasks.mapNotNull { task ->
            val segments = task.sessionsVisibleOn(date)
            if (segments.isEmpty()) null else TaskDayEntry(task, segments)
        }.sortedWith(
            compareBy<TaskDayEntry>(
                { it.task.sortOrder },
                { it.task.plannedStart },
                { it.task.id },
            ),
        )

    /** Incomplete tasks with no plan or whose plan ended before today must remain recoverable. */
    fun backlogTasks(tasks: List<StudyTask>, today: LocalDate): List<StudyTask> = tasks.filter { task ->
        if (task.isCompleted) return@filter false
        val end = task.plannedEnd
        task.sessions.isEmpty() || end == null || end.date.isBefore(today)
    }.sortedWith(
        compareByDescending<StudyTask> { it.priority.ordinal }
            .thenBy { it.dueDate ?: LocalDate.MAX }
            .thenBy { it.sortOrder }
            .thenBy { it.id },
    )

    fun sessionInstance(task: StudyTask, session: TaskSession): ScheduleInstance = ScheduleInstance(
        type = ScheduleItemType.TASK,
        sourceId = task.id,
        title = task.title,
        subtitle = listOfNotNull(
            task.subject.takeIf(String::isNotBlank),
            task.statusLabel(),
        ).joinToString(" · "),
        colorArgb = if (task.isCompleted) TASK_COMPLETED_COLOR_ARGB else TASK_COLOR_ARGB,
        anchorDate = session.date,
        originalDate = session.date,
        startMinute = session.startMinute,
        endMinuteExclusive = session.endMinuteExclusive,
        taskCategory = task.status,
    )

    fun priorityLabel(priority: TaskPriority): String = when (priority) {
        TaskPriority.LOW -> "低"
        TaskPriority.MEDIUM -> "中"
        TaskPriority.HIGH -> "高"
    }

    fun statusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.BACKLOG -> "待安排"
        TaskStatus.SCHEDULED -> "已安排"
        TaskStatus.IN_PROGRESS -> "进行中"
        TaskStatus.COMPLETED -> "已完成"
    }

    fun recurrenceLabel(task: StudyTask): String = when (task.recurrence) {
        TaskRecurrence.NONE -> "单次任务"
        TaskRecurrence.DAILY -> "每${if (task.recurrenceInterval == 1) "" else " ${task.recurrenceInterval} "}天"
        TaskRecurrence.WEEKLY -> "每${if (task.recurrenceInterval == 1) "" else " ${task.recurrenceInterval} "}周"
    }

    private fun StudyTask.statusLabel(): String = statusLabel(status)
}
