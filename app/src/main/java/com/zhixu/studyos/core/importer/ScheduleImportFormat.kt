package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.WeekParity

object ScheduleImportFormat {
    private val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekDay(dayOfWeek: Int): String =
        weekDays.getOrElse(dayOfWeek - 1) { "星期$dayOfWeek" }

    fun clock(minute: Int): String {
        val normalized = Math.floorMod(minute, MINUTES_PER_DAY)
        return "%02d:%02d".format(normalized / 60, normalized % 60)
    }

    fun weeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return "无"
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = sorted.first()
        sorted.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
            } else {
                parts += if (start == previous) "$start" else "$start–$previous"
                start = week
                previous = week
            }
        }
        parts += if (start == previous) "$start" else "$start–$previous"
        return parts.joinToString("、")
    }

    fun courseWeeks(startWeek: Int?, endWeek: Int?, parity: WeekParity): String {
        if (startWeek == null || endWeek == null) return "待确认"
        val parityLabel = when (parity) {
            WeekParity.ALL -> "每周"
            WeekParity.ODD -> "单周"
            WeekParity.EVEN -> "双周"
        }
        return "第 $startWeek–$endWeek 周 · $parityLabel"
    }

    fun periodRange(startPeriod: Int?, endPeriod: Int?): String = when {
        startPeriod == null || endPeriod == null -> "待确认"
        startPeriod == endPeriod -> "第 $startPeriod 节"
        else -> "第 $startPeriod–$endPeriod 节"
    }

    fun overlap(overlap: CourseScheduleOverlap): String {
        val prefix = if (overlap.sameName) "已存在同名课程" else "时间冲突"
        return "$prefix「${overlap.otherName}」：${weekDay(overlap.dayOfWeek)} " +
            "${clock(overlap.startMinute)}–${clock(overlap.endMinuteExclusive)}，" +
            "重叠第 ${weeks(overlap.weeks)} 周"
    }
}
