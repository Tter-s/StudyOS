package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.schedule.ScheduleValidation

/**
 * Field rules for an imported course. The rules are intentionally stricter than
 * "show something": every required field must be present and valid before a
 * draft can be selected for import.
 */
object ScheduleImportValidation {
    fun timeRange(draft: ScheduleImportDraft, grid: PeriodGrid): Pair<Int, Int>? {
        val start = draft.periodStart ?: return null
        val end = draft.periodEnd ?: return null
        return grid.range(start, end)
    }

    fun validationError(draft: ScheduleImportDraft, grid: PeriodGrid): String? {
        if (draft.name.isBlank()) return "课程名称待确认"
        if (draft.classroom.isBlank()) return "教室待确认"
        if (draft.dayOfWeek == null || draft.dayOfWeek !in 1..7) return "星期待确认"
        val periodStart = draft.periodStart
        val periodEnd = draft.periodEnd
        if (periodStart == null || periodEnd == null) return "节次待确认"
        if (periodStart < 1 || periodEnd < periodStart) return "节次范围无效"
        val slotStart = grid.slot(periodStart) ?: return "节次待确认"
        val slotEnd = grid.slot(periodEnd) ?: return "节次待确认"
        if ((periodStart..periodEnd).any { grid.slot(it) == null }) return "节次待确认"
        if (slotEnd.endMinuteExclusive <= slotStart.startMinute) return "节次时间范围无效"
        val startWeek = draft.startWeek ?: return "教学周待确认"
        val endWeek = draft.endWeek ?: return "教学周待确认"
        if (startWeek < 1 || endWeek < startWeek) return "教学周范围无效"
        if (endWeek > MAX_IMPORT_WEEK) return "教学周不能超过第 $MAX_IMPORT_WEEK 周"
        val timeError = ScheduleValidation.timeRangeError(
            slotStart.startMinute,
            slotEnd.endMinuteExclusive,
        )
        if (timeError != null) return timeError
        return null
    }

    fun toCourse(draft: ScheduleImportDraft, grid: PeriodGrid): Course? {
        if (validationError(draft, grid) != null) return null
        val time = timeRange(draft, grid) ?: return null
        return Course(
            id = 0,
            name = draft.name.trim(),
            teacher = draft.teacher?.trim()?.takeIf(String::isNotEmpty),
            classroom = draft.classroom.trim(),
            dayOfWeek = requireNotNull(draft.dayOfWeek),
            startMinute = time.first,
            endMinuteExclusive = time.second,
            startWeek = requireNotNull(draft.startWeek),
            endWeek = requireNotNull(draft.endWeek),
            weekParity = draft.weekParity,
            colorArgb = draft.colorArgb,
        )
    }

}
