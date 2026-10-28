package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.BudgetReminderLevel
import com.zhixu.studyos.core.model.BudgetReminderState
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.MonthFinanceSummary
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetReminderPolicyTest {
    private val month = YearMonth.of(2026, 10)

    @Test
    fun levelFor_mapsOnlyNearOrExceededToReminders() {
        assertEquals(
            BudgetReminderLevel.NONE,
            BudgetReminderPolicy.levelFor(summary(BudgetStatus.NOT_SET, remaining = null)),
        )
        assertEquals(
            BudgetReminderLevel.NONE,
            BudgetReminderPolicy.levelFor(summary(BudgetStatus.HEALTHY, remaining = 5_000)),
        )
        assertEquals(
            BudgetReminderLevel.NEAR_LIMIT,
            BudgetReminderPolicy.levelFor(summary(BudgetStatus.NEAR_LIMIT, remaining = 800)),
        )
        assertEquals(
            BudgetReminderLevel.EXCEEDED,
            BudgetReminderPolicy.levelFor(summary(BudgetStatus.EXCEEDED, remaining = -2_000)),
        )
    }

    @Test
    fun shouldNotify_respectsMonthAndLevelLadder() {
        assertFalse(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.NONE,
                month,
                BudgetReminderState(),
            ),
        )
        assertTrue(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.NEAR_LIMIT,
                month,
                BudgetReminderState(),
            ),
        )
        assertFalse(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.NEAR_LIMIT,
                month,
                BudgetReminderState(month, BudgetReminderLevel.NEAR_LIMIT),
            ),
        )
        assertTrue(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.EXCEEDED,
                month,
                BudgetReminderState(month, BudgetReminderLevel.NEAR_LIMIT),
            ),
        )
        assertFalse(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.NEAR_LIMIT,
                month,
                BudgetReminderState(month, BudgetReminderLevel.EXCEEDED),
            ),
        )
        assertTrue(
            BudgetReminderPolicy.shouldNotify(
                BudgetReminderLevel.NEAR_LIMIT,
                month.plusMonths(1),
                BudgetReminderState(month, BudgetReminderLevel.EXCEEDED),
            ),
        )
    }

    @Test
    fun notificationContent_isNeutralAndShowsTheThreshold() {
        val near = BudgetReminderPolicy.notificationContent(
            summary(BudgetStatus.NEAR_LIMIT, remaining = 1_800, usagePercent = 82, remainingDays = 7),
            BudgetReminderLevel.NEAR_LIMIT,
        )
        assertEquals("本月预算提醒", near?.first)
        assertTrue(near?.second.orEmpty().contains("82%"))
        assertTrue(near?.second.orEmpty().contains("¥18.00"))
        assertTrue(near?.second.orEmpty().contains("还有 7 天"))
        assertTrue(near?.second.orEmpty().contains("按自己的节奏安排即可"))

        val exceeded = BudgetReminderPolicy.notificationContent(
            summary(BudgetStatus.EXCEEDED, remaining = -2_000, usagePercent = 120),
            BudgetReminderLevel.EXCEEDED,
        )
        assertEquals("本月预算已超出", exceeded?.first)
        assertTrue(exceeded?.second.orEmpty().contains("¥20.00"))
        assertTrue(exceeded?.second.orEmpty().contains("可以调整预算"))

        assertNull(
            BudgetReminderPolicy.notificationContent(
                summary(BudgetStatus.NOT_SET, remaining = null),
                BudgetReminderLevel.NEAR_LIMIT,
            ),
        )
    }

    @Test
    fun initialDelay_aimsForNextNineOClockCheck() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(
            30 * 60_000L,
            BudgetReminderPolicy.initialDelayMillis(
                ZonedDateTime.of(2026, 10, 25, 8, 30, 0, 0, zone),
            ),
        )
        assertEquals(
            23 * 60 * 60_000L + 30 * 60_000L,
            BudgetReminderPolicy.initialDelayMillis(
                ZonedDateTime.of(2026, 10, 25, 9, 30, 0, 0, zone),
            ),
        )
        assertEquals(
            24 * 60 * 60_000L,
            BudgetReminderPolicy.initialDelayMillis(
                ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone),
            ),
        )
    }

    private fun summary(
        status: BudgetStatus,
        remaining: Long?,
        usagePercent: Int = if (status == BudgetStatus.NEAR_LIMIT) 82 else 0,
        remainingDays: Int? = 7,
    ) = MonthFinanceSummary(
        month = month,
        totalBudgetFen = if (status == BudgetStatus.NOT_SET) null else 10_000,
        expenseFen = 8_200,
        refundFen = 0,
        incomeFen = 0,
        netSpentFen = 8_200,
        remainingFen = remaining,
        status = status,
        usagePercent = usagePercent,
        remainingDays = remainingDays,
        dailyAllowanceFen = remaining?.coerceAtLeast(0L)?.div(7),
    )
}
