package com.zhixu.studyos.core.reminder

import java.time.Duration
import java.time.ZonedDateTime

object PlanReminderPolicy {
    /** 下一次提醒的延迟；正好在目标时刻时安排到明天，避免立即重复。 */
    fun nextDelayMillis(now: ZonedDateTime, hour: Int, minute: Int): Long {
        require(hour in 0..23) { "提醒小时无效" }
        require(minute in 0..59) { "提醒分钟无效" }
        val todayTarget = now.toLocalDate()
            .atTime(hour, minute)
            .atZone(now.zone)
        val target = if (now.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1L)
        return Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }
}
