package com.zhixu.studyos.feature.schedule

import com.zhixu.studyos.core.model.FreeTimeInterval
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import java.time.format.DateTimeFormatter
import java.util.Locale

val courseColorPalette = listOf(
    0xFF64B5F6.toInt(),
    0xFF81C784.toInt(),
    0xFFBA68C8.toInt(),
    0xFFFFB74D.toInt(),
    0xFF4DB6AC.toInt(),
    0xFFF06292.toInt(),
    0xFF7986CB.toInt(),
    0xFFA1887F.toInt(),
)

val weekDayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

fun parseClockMinute(value: String): Int? {
    val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(value.trim()) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

fun endMinuteFromInput(startMinute: Int, endInput: String): Int? {
    val displayedEnd = parseClockMinute(endInput) ?: return null
    if (displayedEnd == startMinute) return null
    return if (displayedEnd < startMinute) displayedEnd + MINUTES_PER_DAY else displayedEnd
}

fun formatMinute(minute: Int): String = "%02d:%02d".format(
    Math.floorMod(minute, MINUTES_PER_DAY) / 60,
    Math.floorMod(minute, MINUTES_PER_DAY) % 60,
)

fun formatTimeRange(startMinute: Int, endMinuteExclusive: Int): String {
    val nextDay = endMinuteExclusive > MINUTES_PER_DAY
    return "${formatMinute(startMinute)}–${formatMinute(endMinuteExclusive)}${if (nextDay) "（次日）" else ""}"
}

fun formatDuration(minutes: Long): String {
    if (minutes <= 0L) return "无"
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return when {
        hours == 0L -> "$remainder 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "$hours 小时 $remainder 分钟"
    }
}

fun formatFreeTime(interval: FreeTimeInterval): String {
    val nextDay = interval.end.toLocalDate().isAfter(interval.start.toLocalDate())
    return "${interval.start.format(timeFormatter)}–${interval.end.format(timeFormatter)}${if (nextDay) "（次日）" else ""}"
}
