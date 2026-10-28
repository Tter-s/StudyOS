package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.DaySchedule
import com.zhixu.studyos.core.model.DayScheduleItem
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object SemesterCalendar {
    fun weekOneMonday(semester: SemesterSettings): LocalDate =
        semester.startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun teachingWeek(date: LocalDate, semester: SemesterSettings): Int {
        val days = ChronoUnit.DAYS.between(weekOneMonday(semester), date)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    fun weekStart(semester: SemesterSettings, week: Int): LocalDate {
        require(week >= 1) { "教学周必须从第 1 周开始" }
        return weekOneMonday(semester).plusWeeks(week.toLong() - 1L)
    }

    fun occursOn(course: Course, date: LocalDate, semester: SemesterSettings): Boolean {
        if (date.isBefore(semester.startDate) || date.dayOfWeek.value != course.dayOfWeek) {
            return false
        }
        val week = teachingWeek(date, semester)
        val parityMatches = when (course.weekParity) {
            WeekParity.ALL -> true
            WeekParity.ODD -> week % 2 == 1
            WeekParity.EVEN -> week % 2 == 0
        }
        return week in course.startWeek..course.endWeek && parityMatches
    }
}

object ScheduleEngine {
    fun courseInstances(
        course: Course,
        semester: SemesterSettings,
        exceptions: List<CourseException> = emptyList(),
    ): List<ScheduleInstance> {
        val exceptionsByDate = exceptions
            .filter { it.courseId == course.id }
            .associateBy(CourseException::originalDate)
        val result = mutableListOf<ScheduleInstance>()
        for (week in course.startWeek..course.endWeek) {
            val date = SemesterCalendar.weekStart(semester, week)
                .plusDays(course.dayOfWeek.toLong() - 1L)
            if (!SemesterCalendar.occursOn(course, date, semester)) continue
            when (val exception = exceptionsByDate[date]) {
                null -> result += course.toInstance(date)
                else -> when (exception.type) {
                    CourseExceptionType.CANCEL -> Unit
                    CourseExceptionType.MOVE -> result += course.toMovedInstance(exception)
                }
            }
        }
        return result.sortedWith(compareBy(ScheduleInstance::anchorDate, ScheduleInstance::startMinute))
    }

    fun instancesForAnchors(
        snapshot: ScheduleSnapshot,
        anchorStart: LocalDate,
        anchorEnd: LocalDate,
    ): List<ScheduleInstance> {
        require(!anchorEnd.isBefore(anchorStart)) { "结束日期不能早于开始日期" }
        val result = linkedMapOf<String, ScheduleInstance>()
        val semester = snapshot.semester

        if (semester != null) {
            snapshot.courses.forEach { course ->
                val exceptions = snapshot.exceptions.filter { it.courseId == course.id }
                val byOriginalDate = exceptions.associateBy(CourseException::originalDate)
                var date = anchorStart
                while (!date.isAfter(anchorEnd)) {
                    if (SemesterCalendar.occursOn(course, date, semester)) {
                        when (val exception = byOriginalDate[date]) {
                            null -> result[course.toInstance(date).identity] = course.toInstance(date)
                            else -> when (exception.type) {
                                CourseExceptionType.CANCEL -> Unit
                                CourseExceptionType.MOVE -> {
                                    val moved = course.toMovedInstance(exception)
                                    if (!moved.anchorDate.isBefore(anchorStart) && !moved.anchorDate.isAfter(anchorEnd)) {
                                        result[moved.identity] = moved
                                    }
                                }
                            }
                        }
                    }
                    date = date.plusDays(1L)
                }

                // A move can originate outside the query range and land inside it.
                exceptions.forEach { exception ->
                    if (exception.type != CourseExceptionType.MOVE) return@forEach
                    val targetDate = exception.targetDate ?: return@forEach
                    if (targetDate < anchorStart || targetDate > anchorEnd) return@forEach
                    if (!SemesterCalendar.occursOn(course, exception.originalDate, semester)) return@forEach
                    val moved = course.toMovedInstance(exception)
                    result[moved.identity] = moved
                }
            }
        }

        snapshot.events.forEach { event ->
            if (event.date >= anchorStart && event.date <= anchorEnd) {
                val instance = event.toInstance()
                result[instance.identity] = instance
            }
        }

        var routineDate = anchorStart
        while (!routineDate.isAfter(anchorEnd)) {
            snapshot.routines.forEach { routine ->
                val repeats = when (routine.repeat) {
                    RoutineRepeat.DAILY -> true
                    RoutineRepeat.WEEKLY -> routine.dayOfWeek == routineDate.dayOfWeek.value
                }
                if (repeats) {
                    val instance = routine.toInstance(routineDate)
                    result[instance.identity] = instance
                }
            }
            routineDate = routineDate.plusDays(1L)
        }

        return result.values.sortedWith(
            compareBy(ScheduleInstance::anchorDate, ScheduleInstance::startMinute, ScheduleInstance::title),
        )
    }

    fun daySchedule(
        snapshot: ScheduleSnapshot,
        date: LocalDate,
        fallbackZoneId: ZoneId,
    ): DaySchedule {
        val zoneId = snapshot.semester?.zoneId ?: fallbackZoneId
        val instances = instancesForAnchors(snapshot, date.minusDays(1L), date)
        val visibleItems = instances.mapNotNull { instance ->
            val segment = visibleSegment(instance, date) ?: return@mapNotNull null
            DayScheduleItem(
                instance = instance,
                segmentStartMinute = segment.first,
                segmentEndMinuteExclusive = segment.second,
            )
        }.sortedWith(compareBy(DayScheduleItem::segmentStartMinute, DayScheduleItem::segmentEndMinuteExclusive))
        return DaySchedule(
            date = date,
            zoneId = zoneId,
            items = visibleItems,
            freeTimes = FreeTimeCalculator.calculate(date, instances, zoneId),
        )
    }

    fun weekSchedule(
        snapshot: ScheduleSnapshot,
        week: Int,
        fallbackZoneId: ZoneId,
    ): List<DaySchedule> {
        val semester = semesterOrThrow(snapshot)
        val monday = SemesterCalendar.weekStart(semester, week)
        return (0L..6L).map { offset ->
            daySchedule(snapshot, monday.plusDays(offset), fallbackZoneId)
        }
    }

    fun visibleSegment(instance: ScheduleInstance, date: LocalDate): Pair<Int, Int>? = when (date) {
        instance.anchorDate -> {
            val start = instance.startMinute.coerceAtMost(MINUTES_PER_DAY)
            val end = instance.endMinuteExclusive.coerceAtMost(MINUTES_PER_DAY)
            if (start < end) start to end else null
        }
        instance.anchorDate.plusDays(1L) -> {
            val end = (instance.endMinuteExclusive - MINUTES_PER_DAY).coerceAtMost(MINUTES_PER_DAY)
            if (instance.endMinuteExclusive > MINUTES_PER_DAY && end > 0) 0 to end else null
        }
        else -> null
    }

    fun eventInstance(event: OneOffEvent): ScheduleInstance = event.toInstance()

    private fun semesterOrThrow(snapshot: ScheduleSnapshot): SemesterSettings =
        requireNotNull(snapshot.semester) { "请先设置学期" }

    private fun Course.toInstance(date: LocalDate): ScheduleInstance = ScheduleInstance(
        type = ScheduleItemType.COURSE,
        sourceId = id,
        title = name,
        subtitle = listOfNotNull(teacher?.takeIf(String::isNotBlank), classroom).joinToString(" · "),
        colorArgb = colorArgb,
        anchorDate = date,
        originalDate = date,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
    )

    private fun Course.toMovedInstance(exception: CourseException): ScheduleInstance {
        require(exception.courseId == id && exception.type == CourseExceptionType.MOVE) { "调课例外与课程不匹配" }
        val targetDate = requireNotNull(exception.targetDate) { "调课日期缺失" }
        val targetStart = requireNotNull(exception.targetStartMinute) { "调课开始时间缺失" }
        val targetEnd = requireNotNull(exception.targetEndMinuteExclusive) { "调课结束时间缺失" }
        require(ScheduleValidation.timeRangeError(targetStart, targetEnd) == null) { "调课时间无效" }
        return ScheduleInstance(
            type = ScheduleItemType.COURSE,
            sourceId = id,
            title = name,
            subtitle = listOfNotNull(teacher?.takeIf(String::isNotBlank), classroom).joinToString(" · "),
            colorArgb = colorArgb,
            anchorDate = targetDate,
            originalDate = exception.originalDate,
            startMinute = targetStart,
            endMinuteExclusive = targetEnd,
            isMoved = true,
        )
    }

    private fun OneOffEvent.toInstance(): ScheduleInstance = ScheduleInstance(
        type = ScheduleItemType.ONE_OFF_EVENT,
        sourceId = id,
        title = title,
        subtitle = location,
        colorArgb = colorArgb,
        anchorDate = date,
        originalDate = date,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
    )

    private fun RoutineBlock.toInstance(date: LocalDate): ScheduleInstance = ScheduleInstance(
        type = ScheduleItemType.ROUTINE,
        sourceId = id,
        title = title,
        subtitle = when (category) {
            com.zhixu.studyos.core.model.RoutineCategory.SLEEP -> "睡眠"
            com.zhixu.studyos.core.model.RoutineCategory.MEAL -> "用餐"
            com.zhixu.studyos.core.model.RoutineCategory.FIXED -> "固定事务"
        },
        colorArgb = 0xFF90A4AE.toInt(),
        anchorDate = date,
        originalDate = date,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        routineCategory = category,
    )
}
