package com.zhixu.studyos.feature.study

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.scheduling.FixedBlockKind
import java.time.LocalDate

fun parseScheduleTimeInput(value: String, allowMidnightEnd: Boolean = false): Int? {
    val trimmed = value.trim()
    if (allowMidnightEnd && trimmed == "24:00") return MINUTES_PER_DAY
    val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").matchEntire(trimmed) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

fun formatScheduleMinute(minute: Int): String {
    if (minute == MINUTES_PER_DAY) return "24:00"
    return "%02d:%02d".format(Math.floorMod(minute, MINUTES_PER_DAY) / 60, Math.floorMod(minute, MINUTES_PER_DAY) % 60)
}

fun formatScheduleRange(startMinute: Int, endMinuteExclusive: Int): String =
    "${formatScheduleMinute(startMinute)}–${formatScheduleMinute(endMinuteExclusive)}"

fun scheduleDateHeader(date: LocalDate, today: LocalDate): String =
    if (date == today) "今天 · ${formatTaskDate(date)}" else formatTaskDate(date)

fun subjectPreferenceLabel(preference: SubjectPreference): String = when (preference) {
    SubjectPreference.LOWER -> "降低"
    SubjectPreference.NORMAL -> "普通"
    SubjectPreference.HIGHER -> "提高"
}

fun fixedBlockKindLabel(kind: FixedBlockKind): String = when (kind) {
    FixedBlockKind.COURSE -> "课程"
    FixedBlockKind.EVENT -> "日程"
    FixedBlockKind.ROUTINE -> "作息"
    FixedBlockKind.TASK -> "任务"
}
