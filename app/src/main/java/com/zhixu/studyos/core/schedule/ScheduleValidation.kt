package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineRepeat

object ScheduleValidation {
    fun courseError(course: Course): String? = when {
        course.name.isBlank() -> "请输入课程名称"
        course.classroom.isBlank() -> "请输入教室"
        course.dayOfWeek !in 1..7 -> "请选择星期"
        course.startWeek < 1 || course.endWeek < course.startWeek -> "教学周范围无效"
        else -> timeRangeError(course.startMinute, course.endMinuteExclusive)
    }

    fun eventError(event: OneOffEvent): String? = when {
        event.title.isBlank() -> "请输入日程标题"
        else -> timeRangeError(event.startMinute, event.endMinuteExclusive)
    }

    fun routineError(routine: RoutineBlock): String? = when {
        routine.title.isBlank() -> "请输入事务名称"
        routine.repeat == RoutineRepeat.WEEKLY && routine.dayOfWeek !in 1..7 -> "请选择每周重复的星期"
        routine.repeat == RoutineRepeat.DAILY && routine.dayOfWeek != null -> "每日事务不应指定星期"
        else -> timeRangeError(routine.startMinute, routine.endMinuteExclusive)
    }

    fun exceptionError(exception: CourseException): String? {
        if (exception.courseId <= 0) return "课程数据无效"
        if (exception.type == CourseExceptionType.CANCEL) return null
        if (exception.targetDate == null) return "请选择调课日期"
        val start = exception.targetStartMinute ?: return "请输入调课开始时间"
        val end = exception.targetEndMinuteExclusive ?: return "请输入调课结束时间"
        return timeRangeError(start, end)
    }

    fun requireValidCourse(course: Course) {
        val error = courseError(course)
        require(error == null) { error ?: "课程参数无效" }
    }

    fun requireValidEvent(event: OneOffEvent) {
        val error = eventError(event)
        require(error == null) { error ?: "日程参数无效" }
    }

    fun requireValidRoutine(routine: RoutineBlock) {
        val error = routineError(routine)
        require(error == null) { error ?: "事务参数无效" }
    }

    fun requireValidException(exception: CourseException) {
        val error = exceptionError(exception)
        require(error == null) { error ?: "调课或停课参数无效" }
    }

    fun timeRangeError(startMinute: Int, endMinuteExclusive: Int): String? = when {
        startMinute !in 0 until MINUTES_PER_DAY -> "开始时间必须在 00:00–23:59 之间"
        endMinuteExclusive <= startMinute -> "结束时间必须晚于开始时间"
        endMinuteExclusive > startMinute + MINUTES_PER_DAY -> "单个日程不能超过 24 小时"
        else -> null
    }
}
