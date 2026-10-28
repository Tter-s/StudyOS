package com.zhixu.studyos.feature.study

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.TaskStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val taskDateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
private val shortTaskDateFormat = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)

fun parseTaskMinute(value: String): Int? {
    val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(value.trim()) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

fun taskEndMinute(startMinute: Int, endInput: String): Int? {
    val displayedEnd = parseTaskMinute(endInput) ?: return null
    if (displayedEnd == startMinute) return null
    return if (displayedEnd < startMinute) displayedEnd + MINUTES_PER_DAY else displayedEnd
}

fun formatTaskMinute(minute: Int): String {
    if (minute == MINUTES_PER_DAY) return "24:00"
    return "%02d:%02d".format(Math.floorMod(minute, MINUTES_PER_DAY) / 60, Math.floorMod(minute, MINUTES_PER_DAY) % 60)
}

fun formatTaskTimeRange(startMinute: Int, endMinuteExclusive: Int): String =
    "${formatTaskMinute(startMinute)}–${formatTaskMinute(endMinuteExclusive)}${if (endMinuteExclusive > MINUTES_PER_DAY) "（次日）" else ""}"

fun formatTaskDate(date: LocalDate): String = date.format(taskDateFormat)

fun formatShortTaskDate(date: LocalDate): String = date.format(shortTaskDateFormat)

fun formatTaskDuration(minutes: Long): String {
    if (minutes <= 0L) return "无"
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return when {
        hours == 0L -> "$remainder 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "$hours 小时 $remainder 分钟"
    }
}

fun TaskStatus.taskStatusLabel(): String = when (this) {
    TaskStatus.BACKLOG -> "待安排"
    TaskStatus.SCHEDULED -> "已安排"
    TaskStatus.IN_PROGRESS -> "进行中"
    TaskStatus.COMPLETED -> "已完成"
}
