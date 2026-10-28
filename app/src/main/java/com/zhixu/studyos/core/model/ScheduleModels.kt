package com.zhixu.studyos.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

const val MINUTES_PER_DAY: Int = 24 * 60

enum class WeekParity { ALL, ODD, EVEN }

enum class CourseExceptionType { CANCEL, MOVE }

enum class RoutineCategory { SLEEP, MEAL, FIXED }

enum class RoutineRepeat { DAILY, WEEKLY }

enum class ScheduleItemType { COURSE, ONE_OFF_EVENT, ROUTINE, TASK }

data class SemesterSettings(
    val startDate: LocalDate,
    val zoneId: ZoneId,
)

data class Course(
    val id: Long = 0,
    val name: String,
    val teacher: String? = null,
    val classroom: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekParity: WeekParity = WeekParity.ALL,
    val colorArgb: Int,
)

data class CourseException(
    val id: Long = 0,
    val courseId: Long,
    val originalDate: LocalDate,
    val type: CourseExceptionType,
    val targetDate: LocalDate? = null,
    val targetStartMinute: Int? = null,
    val targetEndMinuteExclusive: Int? = null,
)

data class OneOffEvent(
    val id: Long = 0,
    val title: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val location: String = "",
    val colorArgb: Int,
)

data class RoutineBlock(
    val id: Long = 0,
    val title: String,
    val category: RoutineCategory,
    val repeat: RoutineRepeat,
    val dayOfWeek: Int? = null,
    val startMinute: Int,
    val endMinuteExclusive: Int,
)

data class ScheduleSnapshot(
    val semester: SemesterSettings? = null,
    val courses: List<Course> = emptyList(),
    val exceptions: List<CourseException> = emptyList(),
    val events: List<OneOffEvent> = emptyList(),
    val routines: List<RoutineBlock> = emptyList(),
)

/** A single occurrence expanded from a recurrence rule, never a per-week database row. */
data class ScheduleInstance(
    val type: ScheduleItemType,
    val sourceId: Long,
    val title: String,
    val subtitle: String,
    val colorArgb: Int,
    val anchorDate: LocalDate,
    val originalDate: LocalDate = anchorDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val isMoved: Boolean = false,
    val routineCategory: RoutineCategory? = null,
    val taskCategory: TaskStatus? = null,
) {
    val identity: String
        get() = "${type.name}:$sourceId:$originalDate"
}

data class DayScheduleItem(
    val instance: ScheduleInstance,
    val segmentStartMinute: Int,
    val segmentEndMinuteExclusive: Int,
)

data class FreeTimeInterval(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    val durationMinutes: Long get() = Duration.between(start, end).toMinutes()
}

data class DaySchedule(
    val date: LocalDate,
    val zoneId: ZoneId,
    val items: List<DayScheduleItem>,
    val freeTimes: List<FreeTimeInterval>,
) {
    val totalFreeMinutes: Long get() = freeTimes.sumOf(FreeTimeInterval::durationMinutes)
}

data class ScheduleConflict(
    val candidate: ScheduleInstance,
    val existing: ScheduleInstance,
    val date: LocalDate,
)
