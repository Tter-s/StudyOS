package com.zhixu.studyos.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val MAX_TASK_SESSIONS: Int = 20
const val MAX_RECURRENCE_OCCURRENCES: Int = 200
const val TASK_COLOR_ARGB: Int = 0xFF4C8DFF.toInt()
const val TASK_COMPLETED_COLOR_ARGB: Int = 0xFF7FA7C9.toInt()
const val REST_COLOR_ARGB: Int = 0xFF63B38A.toInt()

enum class TaskStatus {
    BACKLOG,
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED;

    val isCompleted: Boolean get() = this == COMPLETED
    val hasPlan: Boolean get() = this == SCHEDULED || this == IN_PROGRESS || this == COMPLETED
}

enum class TaskPriority { LOW, MEDIUM, HIGH }

enum class TaskRecurrence { NONE, DAILY, WEEKLY }

data class TaskSession(
    val id: Long = 0,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    /** 用户锁定或手工调整过的时段；自动排程只替换未锁定时段。 */
    val isLocked: Boolean = false,
) {
    val endDate: LocalDate
        get() = date.plusDays(Math.floorDiv(endMinuteExclusive, MINUTES_PER_DAY).toLong())

    val endMinuteOfDay: Int
        get() = Math.floorMod(endMinuteExclusive, MINUTES_PER_DAY)
}

data class TaskTime(
    val date: LocalDate,
    val minute: Int,
) : Comparable<TaskTime> {
    private val localValue: Long get() = date.toEpochDay() * MINUTES_PER_DAY + minute

    override fun compareTo(other: TaskTime): Int = localValue.compareTo(other.localValue)

    fun plusDays(days: Long): TaskTime = TaskTime(date.plusDays(days), minute)
}

data class TaskSegment(
    val session: TaskSession,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val isContinuation: Boolean,
)

data class TaskDayEntry(
    val task: StudyTask,
    val segments: List<TaskSegment>,
)

data class TaskSaveResult(
    val createdTaskIds: List<Long>,
)

enum class StudyBlockKind { COURSE, EVENT, TASK, REST }

data class StudyDayBlock(
    val kind: StudyBlockKind,
    val title: String,
    val subtitle: String,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val colorArgb: Int,
    val isContinuation: Boolean = false,
    val taskId: Long? = null,
    val isCompleted: Boolean = false,
)

data class StudyDayPlan(
    val date: LocalDate,
    val zoneId: ZoneId,
    val tasks: List<TaskDayEntry>,
    val timeline: List<StudyDayBlock>,
    val freeTimes: List<FreeTimeInterval>,
) {
    val totalFreeMinutes: Long get() = freeTimes.sumOf(FreeTimeInterval::durationMinutes)
    val taskMinutes: Int get() = tasks.sumOf { entry -> entry.segments.sumOf { it.endMinuteExclusive - it.startMinute } }
}

data class TaskConflict(
    val taskId: Long,
    val taskTitle: String,
    val date: LocalDate,
    val otherTitle: String,
    val otherStartMinute: Int,
    val otherEndMinuteExclusive: Int,
)
