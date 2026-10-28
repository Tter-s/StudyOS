package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.BudgetReminderLevel
import com.zhixu.studyos.core.model.BudgetReminderState
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.MonthFinanceSummary
import java.time.Duration
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime

/**
 * 可选预算提醒的纯逻辑：
 * - 只在接近上限（≥80%）或已超支时提醒；
 * - 同一个月同一级别最多提醒一次，升级到超支时再提醒一次；
 * - 新月份自动重置，最多两次/月，不做每日推送。
 */
object BudgetReminderPolicy {
    const val CHECK_HOUR = 9

    fun levelFor(summary: MonthFinanceSummary): BudgetReminderLevel = when (summary.status) {
        BudgetStatus.NOT_SET, BudgetStatus.HEALTHY -> BudgetReminderLevel.NONE
        BudgetStatus.NEAR_LIMIT -> BudgetReminderLevel.NEAR_LIMIT
        BudgetStatus.EXCEEDED -> BudgetReminderLevel.EXCEEDED
    }

    fun shouldNotify(
        level: BudgetReminderLevel,
        month: YearMonth,
        state: BudgetReminderState,
    ): Boolean {
        if (level == BudgetReminderLevel.NONE) return false
        if (state.lastMonth != month) return true
        return level.rank > (state.lastLevel?.rank ?: 0)
    }

    fun notificationContent(
        summary: MonthFinanceSummary,
        level: BudgetReminderLevel,
    ): Pair<String, String>? {
        if (level == BudgetReminderLevel.NONE) return null
        val remaining = summary.remainingFen ?: return null
        val title = if (level == BudgetReminderLevel.EXCEEDED) "本月预算已超出" else "本月预算提醒"
        val body = when (level) {
            BudgetReminderLevel.NEAR_LIMIT -> buildString {
                append("本月生活费预算已使用 ${summary.usagePercent}%")
                append("，剩余 ")
                append(Money.formatFen(remaining.coerceAtLeast(0L)))
                if (summary.remainingDays != null) append("，本月还有 ${summary.remainingDays} 天")
                append("。按自己的节奏安排即可。")
            }
            BudgetReminderLevel.EXCEEDED -> buildString {
                append("本月净支出已超出预算 ")
                append(Money.formatFen((-remaining).coerceAtLeast(0L)))
                append("。可以调整预算，或查看本月明细。")
            }
            BudgetReminderLevel.NONE -> ""
        }
        return title to body
    }

    /** 下一次检查固定在本地时间 09:00；若今天已过 09:00 则安排到明天。 */
    fun initialDelayMillis(now: ZonedDateTime): Long {
        val todayAtCheck = now.toLocalDate().atTime(LocalTime.of(CHECK_HOUR, 0)).atZone(now.zone)
        val target = if (now.isBefore(todayAtCheck)) todayAtCheck else todayAtCheck.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }
}
