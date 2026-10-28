package com.zhixu.studyos.core.sleep

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 睡眠保护设置。所有时间都是当地民用分钟（0..1439）。 */
data class SleepSchedule(
    val weekdayBedMinute: Int = 23 * 60,
    val weekdayWakeMinute: Int = 7 * 60,
    val weekendBedMinute: Int = 23 * 60 + 30,
    val weekendWakeMinute: Int = 8 * 60,
    val earliestWakeMinute: Int = 6 * 60,
    val latestBedMinute: Int = 30,
    val preSleepBufferMinutes: Int = 30,
    val napEnabled: Boolean = false,
    val napStartMinute: Int = 13 * 60,
    val napEndMinute: Int = 14 * 60,
    val examPeriodStart: LocalDate? = null,
    val examPeriodEnd: LocalDate? = null,
    val examBedMinute: Int? = null,
    val examWakeMinute: Int? = null,
    /** 可选睡前模式：到睡前缓冲开始时发一条本地通知，不控制系统免打扰。 */
    val bedtimeModeEnabled: Boolean = false,
    val updatedAtEpochMillis: Long = 0,
) {
    companion object {
        val Default = SleepSchedule()
        const val MIN_SLEEP_MINUTES = 240
        const val MAX_SLEEP_MINUTES = 720
    }

    fun validationError(): String? = when {
        !validMinute(weekdayBedMinute) || !validMinute(weekdayWakeMinute) ->
            "工作日睡觉/起床时间无效"
        !validMinute(weekendBedMinute) || !validMinute(weekendWakeMinute) ->
            "周末睡觉/起床时间无效"
        !validMinute(earliestWakeMinute) -> "最早起床时间无效"
        !validMinute(latestBedMinute) -> "最晚睡觉时间无效"
        preSleepBufferMinutes !in 0..120 -> "睡前缓冲时间应在 0–120 分钟之间"
        napEnabled && (!validMinute(napStartMinute) || !validMinute(napEndMinute)) ->
            "午休时间无效"
        napEnabled && napStartMinute == napEndMinute -> "午休开始和结束时间不能相同"
        sleepMinutes(weekdayBedMinute, weekdayWakeMinute) !in MIN_SLEEP_MINUTES..MAX_SLEEP_MINUTES ->
            "工作日睡眠时长应在 4–12 小时之间"
        sleepMinutes(weekendBedMinute, weekendWakeMinute) !in MIN_SLEEP_MINUTES..MAX_SLEEP_MINUTES ->
            "周末睡眠时长应在 4–12 小时之间"
        (examPeriodStart == null) != (examPeriodEnd == null) -> "考试周起止日期需要同时填写"
        examPeriodStart != null && examPeriodEnd != null && examPeriodEnd.isBefore(examPeriodStart) ->
            "考试周结束日期不能早于开始日期"
        (examBedMinute == null) != (examWakeMinute == null) -> "考试周作息需要同时填写睡觉和起床时间"
        examBedMinute != null && (!validMinute(examBedMinute) || !validMinute(examWakeMinute ?: 0)) ->
            "考试周作息时间无效"
        else -> null
    }

    fun requireValid() {
        require(validationError() == null) { validationError() ?: "睡眠设置无效" }
    }

    /** 晚上 23:00 与 00:30 属于同一个“夜晚”，因此把午夜后的时间平移到第二天再比较。 */
    fun isLaterThanLatestBed(bedMinute: Int): Boolean {
        val bedEvening = if (bedMinute < 12 * 60) bedMinute + MINUTES_PER_DAY else bedMinute
        val latestEvening = if (latestBedMinute < 12 * 60) latestBedMinute + MINUTES_PER_DAY else latestBedMinute
        return bedEvening > latestEvening
    }

    fun isEarlierThanEarliestWake(wakeMinute: Int): Boolean {
        val wakeMorning = if (wakeMinute >= 12 * 60) wakeMinute - MINUTES_PER_DAY else wakeMinute
        return wakeMorning < earliestWakeMinute
    }

    private fun validMinute(minute: Int) = minute in 0 until MINUTES_PER_DAY

    private fun sleepMinutes(bed: Int, wake: Int): Int {
        var duration = wake - bed
        if (duration <= 0) duration += MINUTES_PER_DAY
        return duration
    }
}

const val MINUTES_PER_DAY = 24 * 60

data class SleepDateOverride(
    val date: LocalDate,
    val bedMinute: Int,
    val wakeMinute: Int,
    val note: String = "",
) {
    fun validationError(): String? = when {
        bedMinute !in 0 until MINUTES_PER_DAY || wakeMinute !in 0 until MINUTES_PER_DAY -> "时间无效"
        bedMinute == wakeMinute -> "睡觉和起床时间不能相同"
        else -> null
    }
}

data class SleepRecord(
    val date: LocalDate,
    val bedAt: Instant? = null,
    val wakeAt: Instant? = null,
    val quality: Int? = null,
    val nextDayEnergy: Int? = null,
    val note: String = "",
) {
    val durationMinutes: Long?
        get() {
            val bed = bedAt ?: return null
            val wake = wakeAt ?: return null
            val minutes = java.time.Duration.between(bed, wake).toMinutes()
            return minutes.takeIf { it > 0 }
        }

    fun validationError(): String? {
        if (bedAt == null && wakeAt == null && quality == null && nextDayEnergy == null && note.isBlank()) {
            return "请至少填写一项睡眠记录"
        }
        if (quality != null && quality !in 1..5) return "睡眠质量应为 1–5"
        if (nextDayEnergy != null && nextDayEnergy !in 1..5) return "次日精神状态应为 1–5"
        val minutes = durationMinutes
        if (bedAt != null && wakeAt != null && minutes == null) return "起床时间必须晚于入睡时间"
        if (minutes != null && minutes > 24 * 60) return "睡眠时长不能超过 24 小时"
        return null
    }
}

/** 一个具体日期的有效睡眠时段；结束分钟可能超过 1440（跨午夜）。 */
data class EffectiveSleep(
    val nightDate: LocalDate,
    val bedMinute: Int,
    val wakeMinute: Int,
    val source: SleepSource,
) {
    val crossesMidnight: Boolean get() = wakeMinute <= bedMinute
}

enum class SleepSource { WEEKDAY, WEEKEND, EXAM_PERIOD, DATE_OVERRIDE }

/** 某个日期内被睡眠保护占用的分钟区间（半开区间，已裁剪到当天）。 */
data class SleepInterval(
    val startMinute: Int,
    val endMinuteExclusive: Int,
) {
    val durationMinutes: Int get() = endMinuteExclusive - startMinute
}

enum class SleepPhase { AWAKE, WIND_DOWN, ASLEEP, NAP }

data class SleepStatus(
    val phase: SleepPhase,
    val bedMinute: Int,
    val wakeMinute: Int,
    val source: SleepSource,
    val message: String,
)

data class SleepConflict(
    val date: LocalDate,
    val title: String,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val conflictStartMinute: Int,
    val conflictEndMinuteExclusive: Int,
) {
    val message: String
        get() = "「$title」与睡眠时段重叠 ${formatMinute(conflictStartMinute)}–${formatMinute(conflictEndMinuteExclusive)}"
}

fun formatSleepMinute(minute: Int): String {
    val normalized = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

private fun formatMinute(minute: Int) = formatSleepMinute(minute)

/** 把 Instant 转为当地分钟；睡眠记录与设置都按设备时区解释。 */
fun instantToLocalMinute(instant: Instant, zoneId: ZoneId): Int {
    val time: LocalTime = instant.atZone(zoneId).toLocalTime()
    return time.hour * 60 + time.minute
}

fun DayOfWeek.isWeekend(): Boolean = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

/** 入睡日日期：凌晨 0–4 点的入睡记录按前一天归集，避免跨午夜时分成两天。 */
fun sleepDateFor(instant: Instant, zoneId: ZoneId, wakeHourThreshold: Int = 4): LocalDate {
    val zoned = instant.atZone(zoneId)
    return if (zoned.hour < wakeHourThreshold) zoned.toLocalDate().minusDays(1) else zoned.toLocalDate()
}
