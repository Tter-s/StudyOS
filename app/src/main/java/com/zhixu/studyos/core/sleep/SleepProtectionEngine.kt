package com.zhixu.studyos.core.sleep

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 睡眠保护引擎：把用户设置的作息转换为排程使用的硬占用区间。
 *
 * 关键规则（V2.0 第 2.1 节）：
 * - 支持跨午夜睡眠，凌晨入睡不会把一个晚上拆成两天；
 * - 睡前缓冲属于保护时间，任务不得排入；
 * - 特殊日期覆盖 > 考试周作息 > 周末作息 > 工作日作息；
 * - 与固定课程冲突时返回冲突，由 UI 提示用户处理，不静默覆盖；
 * - 不修改 Android 系统免打扰设置，也不声称能绕过权限。
 */
object SleepProtectionEngine {

    private const val WEEKEND_NIGHT_START_HOUR = 12

    fun effectiveForNight(
        nightDate: LocalDate,
        schedule: SleepSchedule,
        overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    ): EffectiveSleep {
        overrides[nightDate]?.let { override ->
            return EffectiveSleep(
                nightDate = nightDate,
                bedMinute = override.bedMinute,
                wakeMinute = override.wakeMinute,
                source = SleepSource.DATE_OVERRIDE,
            )
        }
        val inExam = isInExamPeriod(nightDate, schedule)
        if (inExam && schedule.examBedMinute != null && schedule.examWakeMinute != null) {
            return EffectiveSleep(
                nightDate = nightDate,
                bedMinute = schedule.examBedMinute,
                wakeMinute = schedule.examWakeMinute,
                source = SleepSource.EXAM_PERIOD,
            )
        }
        val weekend = isWeekendNight(nightDate)
        return EffectiveSleep(
            nightDate = nightDate,
            bedMinute = if (weekend) schedule.weekendBedMinute else schedule.weekdayBedMinute,
            wakeMinute = if (weekend) schedule.weekendWakeMinute else schedule.weekdayWakeMinute,
            source = if (weekend) SleepSource.WEEKEND else SleepSource.WEEKDAY,
        )
    }

    fun isInExamPeriod(date: LocalDate, schedule: SleepSchedule): Boolean {
        val start = schedule.examPeriodStart ?: return false
        val end = schedule.examPeriodEnd ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    /**
     * 周末作息的“夜晚”从周五晚开始，到周日早结束：
     * 周五、周六夜使用周末设置，周日晚上回到工作日设置。
     */
    fun isWeekendNight(date: LocalDate): Boolean = when (date.dayOfWeek) {
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> true
        else -> false
    }

    /** 睡前缓冲也属于受保护时段。 */
    fun protectedStartMinute(effective: EffectiveSleep, schedule: SleepSchedule): Int =
        effective.bedMinute - schedule.preSleepBufferMinutes

    /** 该夜晚的绝对分钟区间（含缓冲），结束为排他边界，可能超过一天。 */
    fun nightInterval(
        nightDate: LocalDate,
        schedule: SleepSchedule,
        effective: EffectiveSleep = effectiveForNight(nightDate, schedule),
    ): LongRange {
        // nightDate 表示“这个夜晚的日期”（睡前/入睡所在的那天）。
        // 00:10 这类凌晨入睡必须落到 nightDate 的次日凌晨，否则周末/考试周/
        // 特殊日期的设置会整体错位一天。
        val bedDayOffset = if (effective.bedMinute < 12 * 60) 1L else 0L
        val bedDayEpoch = nightDate.toEpochDay() + bedDayOffset
        val bedDayStart = bedDayEpoch * MINUTES_PER_DAY
        val start = bedDayStart + effective.bedMinute - schedule.preSleepBufferMinutes
        val rawEnd = if (effective.wakeMinute <= effective.bedMinute) {
            (bedDayEpoch + 1) * MINUTES_PER_DAY + effective.wakeMinute
        } else {
            bedDayStart + effective.wakeMinute
        }
        return start until rawEnd
    }

    /** 某个自然日内被睡眠（含缓冲与午休）占用的所有分钟区间，已裁剪到 00:00–24:00。 */
    fun intervalsOn(
        date: LocalDate,
        schedule: SleepSchedule,
        overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    ): List<SleepInterval> {
        val dayStart = date.toEpochDay() * MINUTES_PER_DAY
        val dayEnd = dayStart + MINUTES_PER_DAY
        val result = mutableListOf<SleepInterval>()
        listOf(date.minusDays(1), date, date.plusDays(1)).forEach { night ->
            val interval = nightInterval(night, schedule, effectiveForNight(night, schedule, overrides))
            val start = maxOf(interval.first, dayStart)
            val end = minOf(interval.last + 1, dayEnd)
            if (end > start) {
                result += SleepInterval(
                    startMinute = (start - dayStart).toInt(),
                    endMinuteExclusive = (end - dayStart).toInt(),
                )
            }
        }
        if (schedule.napEnabled && schedule.napEndMinute != schedule.napStartMinute) {
            val napStart = minOf(schedule.napStartMinute, schedule.napEndMinute)
            val napEnd = maxOf(schedule.napStartMinute, schedule.napEndMinute)
            result += SleepInterval(startMinute = napStart, endMinuteExclusive = napEnd)
        }
        return result
            .sortedBy(SleepInterval::startMinute)
            .fold(mutableListOf<SleepInterval>()) { merged, current ->
                val last = merged.lastOrNull()
                if (last != null && current.startMinute <= last.endMinuteExclusive) {
                    merged[merged.lastIndex] = last.copy(
                        endMinuteExclusive = maxOf(last.endMinuteExclusive, current.endMinuteExclusive),
                    )
                } else {
                    merged += current
                }
                merged
            }
    }

    /**
     * 睡眠状态。免打扰/睡前模式只是应用内提示，不会调用系统 DND。
     */
    fun statusAt(
        date: LocalDate,
        minuteOfDay: Int,
        schedule: SleepSchedule,
        overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    ): SleepStatus {
        val normalizedMinute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY)
        val napStart = minOf(schedule.napStartMinute, schedule.napEndMinute)
        val napEnd = maxOf(schedule.napStartMinute, schedule.napEndMinute)
        val effectiveTonight = effectiveForNight(date, schedule, overrides)
        if (schedule.napEnabled && normalizedMinute in napStart until napEnd) {
            return SleepStatus(
                phase = SleepPhase.NAP,
                bedMinute = effectiveTonight.bedMinute,
                wakeMinute = effectiveTonight.wakeMinute,
                source = effectiveTonight.source,
                message = "午休时段，建议放下学习任务",
            )
        }
        // 用绝对分钟判断属于“睡前缓冲”还是“已入睡”，正确处理跨午夜与凌晨入睡。
        val todayEpoch = date.toEpochDay()
        val nowAbsolute = todayEpoch * MINUTES_PER_DAY + normalizedMinute
        val windows = listOf(date.minusDays(1), date, date.plusDays(1)).mapNotNull { night ->
            val effectiveNight = effectiveForNight(night, schedule, overrides)
            val interval = nightInterval(night, schedule, effectiveNight)
            if (nowAbsolute < interval.first || nowAbsolute > interval.last) {
                null
            } else {
                val bedDayOffset = if (effectiveNight.bedMinute < 12 * 60) 1L else 0L
                Triple(
                    night,
                    effectiveNight,
                    (night.toEpochDay() + bedDayOffset) * MINUTES_PER_DAY + effectiveNight.bedMinute,
                )
            }
        }
        windows.firstOrNull()?.let { (_, effectiveNight, bedAbsolute) ->
            val isWindDown = nowAbsolute < bedAbsolute
            return SleepStatus(
                phase = if (isWindDown) SleepPhase.WIND_DOWN else SleepPhase.ASLEEP,
                bedMinute = effectiveNight.bedMinute,
                wakeMinute = effectiveNight.wakeMinute,
                source = effectiveNight.source,
                message = if (isWindDown) {
                    "进入睡前缓冲，建议结束当前学习"
                } else {
                    "睡眠保护中，不安排学习任务"
                },
            )
        }
        return SleepStatus(
            phase = SleepPhase.AWAKE,
            bedMinute = effectiveTonight.bedMinute,
            wakeMinute = effectiveTonight.wakeMinute,
            source = effectiveTonight.source,
            message = "当前不在睡眠保护时段",
        )
    }

    /**
     * 与固定安排的冲突。只报告，不修改任何安排。
     */
    fun findConflicts(
        date: LocalDate,
        schedule: SleepSchedule,
        busy: List<SleepBusyItem>,
        overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    ): List<SleepConflict> {
        val sleepIntervals = intervalsOn(date, schedule, overrides)
        return sleepIntervals.flatMap { sleep ->
            busy.mapNotNull { item ->
                val start = maxOf(sleep.startMinute, item.startMinute)
                val end = minOf(sleep.endMinuteExclusive, item.endMinuteExclusive)
                if (end > start) {
                    SleepConflict(
                        date = date,
                        title = item.title,
                        startMinute = item.startMinute,
                        endMinuteExclusive = item.endMinuteExclusive,
                        conflictStartMinute = start,
                        conflictEndMinuteExclusive = end,
                    )
                } else {
                    null
                }
            }
        }.distinctBy { listOf(it.date, it.title, it.conflictStartMinute, it.conflictEndMinuteExclusive) }
    }

    fun sleepDurationMinutes(effective: EffectiveSleep): Int {
        var duration = effective.wakeMinute - effective.bedMinute
        if (duration <= 0) duration += MINUTES_PER_DAY
        return duration
    }

    /**
     * 每日睡眠摘要，供首页/报告使用。
     */
    fun describeDay(
        date: LocalDate,
        schedule: SleepSchedule,
        overrides: Map<LocalDate, SleepDateOverride> = emptyMap(),
    ): String {
        val effective = effectiveForNight(date, schedule, overrides)
        val sourceLabel = when (effective.source) {
            SleepSource.WEEKDAY -> "工作日作息"
            SleepSource.WEEKEND -> "周末作息"
            SleepSource.EXAM_PERIOD -> "考试周作息"
            SleepSource.DATE_OVERRIDE -> "特殊日期作息"
        }
        return "$sourceLabel · ${formatSleepMinute(effective.bedMinute)} 入睡，" +
            "${formatSleepMinute(effective.wakeMinute)} 起床，" +
            "睡眠 ${sleepDurationMinutes(effective) / 60} 小时 ${sleepDurationMinutes(effective) % 60} 分"
    }
}

data class SleepBusyItem(
    val title: String,
    val startMinute: Int,
    val endMinuteExclusive: Int,
)
