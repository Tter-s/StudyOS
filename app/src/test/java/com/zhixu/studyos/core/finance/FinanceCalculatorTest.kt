package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.CategoryBudget
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthlyBudget
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceCalculatorTest {
    private val today = LocalDate.of(2026, 10, 24)

    @Test
    fun crossMonth_andRefund_areAttributedToTheirRecordDatesOnly() {
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 1, 10), 10_000),
                expense(LocalDate.of(2026, 2, 5), 5_000),
                refund(LocalDate.of(2026, 2, 8), 2_000),
            ),
            budgets = listOf(budget(YearMonth.of(2026, 2), 4_000)),
        )

        val january = FinanceCalculator.monthSummary(snapshot, YearMonth.of(2026, 1), today)
        val february = FinanceCalculator.monthSummary(
            snapshot,
            YearMonth.of(2026, 2),
            LocalDate.of(2026, 2, 10),
        )

        assertEquals(10_000L, january.expenseFen)
        assertEquals(0L, january.refundFen)
        assertEquals(10_000L, january.netSpentFen)
        assertNull(january.remainingDays)

        assertEquals(5_000L, february.expenseFen)
        assertEquals(2_000L, february.refundFen)
        assertEquals(3_000L, february.netSpentFen)
        assertEquals(1_000L, february.remainingFen)
        assertEquals(19, february.remainingDays)
        assertEquals(52L, february.dailyAllowanceFen)
    }

    @Test
    fun futureDatedTransactions_areNotCountedAsAlreadySpent() {
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 10, 2), 10_000),
                expense(LocalDate.of(2026, 10, 30), 90_000),
            ),
            budgets = listOf(budget(YearMonth.of(2026, 10), 100_000)),
        )

        val summary = FinanceCalculator.monthSummary(
            snapshot,
            YearMonth.of(2026, 10),
            LocalDate.of(2026, 10, 10),
        )

        assertEquals(10_000L, summary.expenseFen)
        assertEquals(90_000L, summary.remainingFen)
        assertEquals(10, summary.usagePercent)
        val breakdown = FinanceCalculator.categoryBreakdown(
            snapshot,
            YearMonth.of(2026, 10),
            LocalDate.of(2026, 10, 10),
        )
        assertEquals(10_000L, breakdown.sumOf { it.expenseFen })
    }

    @Test
    fun refund_reducesSameMonthCategoryNetAndShare() {
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 10, 2), 10_000, categoryId = 1),
                refund(LocalDate.of(2026, 10, 3), 2_000, categoryId = 1),
                expense(LocalDate.of(2026, 10, 4), 8_000, categoryId = 3),
            ),
        )

        val breakdown = FinanceCalculator.categoryBreakdown(snapshot, YearMonth.of(2026, 10))
        val dining = breakdown.first { it.category.id == 1L }
        val materials = breakdown.first { it.category.id == 3L }

        assertEquals(10_000L, dining.expenseFen)
        assertEquals(2_000L, dining.refundFen)
        assertEquals(8_000L, dining.netSpentFen)
        assertEquals(2, dining.transactionCount)
        assertEquals(8_000L, materials.netSpentFen)
        assertEquals(50, dining.sharePercent)
        assertEquals(50, materials.sharePercent)
        assertEquals(0, breakdown.first { it.category.id == 2L }.sharePercent)
    }

    @Test
    fun income_isTrackedButDoesNotOffsetBudgetOrSpending() {
        val snapshot = snapshot(
            transactions = listOf(
                income(LocalDate.of(2026, 10, 1), 100_000),
                expense(LocalDate.of(2026, 10, 2), 5_000),
            ),
            budgets = listOf(budget(YearMonth.of(2026, 10), 10_000)),
        )

        val summary = FinanceCalculator.monthSummary(snapshot, YearMonth.of(2026, 10), today)
        assertEquals(100_000L, summary.incomeFen)
        assertEquals(5_000L, summary.netSpentFen)
        assertEquals(5_000L, summary.remainingFen)
        assertEquals(50, summary.usagePercent)
        assertEquals(BudgetStatus.HEALTHY, summary.status)
    }

    @Test
    fun budgetAdjustment_recomputesRemainingAndStatusWithoutTouchingTransactions() {
        val transactions = listOf(expense(LocalDate.of(2026, 10, 2), 8_500))
        val month = YearMonth.of(2026, 10)
        val near = FinanceCalculator.monthSummary(
            snapshot(transactions, budgets = listOf(budget(month, 10_000))),
            month,
            today,
        )
        val healthy = FinanceCalculator.monthSummary(
            snapshot(transactions, budgets = listOf(budget(month, 20_000))),
            month,
            today,
        )
        val exceeded = FinanceCalculator.monthSummary(
            snapshot(transactions, budgets = listOf(budget(month, 8_000))),
            month,
            today,
        )

        assertEquals(BudgetStatus.NEAR_LIMIT, near.status)
        assertEquals(1_500L, near.remainingFen)
        assertEquals(85, near.usagePercent)
        assertEquals(BudgetStatus.HEALTHY, healthy.status)
        assertEquals(11_500L, healthy.remainingFen)
        assertEquals(42, healthy.usagePercent)
        assertEquals(BudgetStatus.EXCEEDED, exceeded.status)
        assertEquals(-500L, exceeded.remainingFen)
        assertEquals(0L, exceeded.dailyAllowanceFen)
        assertEquals(106, exceeded.usagePercent)
    }

    @Test
    fun dailyAllowance_countsTodayInclusiveAndFloorsDivision() {
        val month = YearMonth.of(2026, 10)
        val snapshot = snapshot(budgets = listOf(budget(month, 10_000)))

        val on25th = FinanceCalculator.monthSummary(snapshot, month, LocalDate.of(2026, 10, 25))
        assertEquals(7, on25th.remainingDays)
        assertEquals(1_428L, on25th.dailyAllowanceFen)

        val on31st = FinanceCalculator.monthSummary(snapshot, month, LocalDate.of(2026, 10, 31))
        assertEquals(1, on31st.remainingDays)
        assertEquals(10_000L, on31st.dailyAllowanceFen)

        val pastMonth = FinanceCalculator.monthSummary(
            snapshot,
            month,
            LocalDate.of(2026, 11, 5),
        )
        assertNull(pastMonth.remainingDays)
        assertNull(pastMonth.dailyAllowanceFen)
    }

    @Test
    fun noBudget_hasNoRemainingOrDailyAllowance() {
        val summary = FinanceCalculator.monthSummary(
            snapshot(transactions = listOf(expense(LocalDate.of(2026, 10, 2), 5_000))),
            YearMonth.of(2026, 10),
            today,
        )
        assertEquals(BudgetStatus.NOT_SET, summary.status)
        assertNull(summary.totalBudgetFen)
        assertNull(summary.remainingFen)
        assertNull(summary.dailyAllowanceFen)
        assertEquals(0, summary.usagePercent)
    }

    @Test
    fun budgetStatus_usesFlooredDisplayedUsageAndExactOverrun() {
        val month = YearMonth.of(2026, 10)
        fun statusFor(fen: Long): BudgetStatus = FinanceCalculator.monthSummary(
            snapshot(
                transactions = listOf(expense(LocalDate.of(2026, 10, 2), fen)),
                budgets = listOf(budget(month, 10_000)),
            ),
            month,
            today,
        ).status

        assertEquals(BudgetStatus.HEALTHY, statusFor(7_999))
        assertEquals(BudgetStatus.NEAR_LIMIT, statusFor(8_000))
        assertEquals(BudgetStatus.NEAR_LIMIT, statusFor(10_000))
        assertEquals(BudgetStatus.EXCEEDED, statusFor(10_001))
    }

    @Test
    fun refundSurplus_canRaiseRemainingAboveBudgetButNotUsage() {
        val month = YearMonth.of(2026, 10)
        val summary = FinanceCalculator.monthSummary(
            snapshot(
                transactions = listOf(refund(LocalDate.of(2026, 10, 2), 3_000)),
                budgets = listOf(budget(month, 10_000)),
            ),
            month,
            today,
        )
        assertEquals(-3_000L, summary.netSpentFen)
        assertEquals(13_000L, summary.remainingFen)
        assertEquals(0, summary.usagePercent)
        assertEquals(BudgetStatus.HEALTHY, summary.status)
    }

    @Test
    fun weeklyTrend_splitsMondayToSundayAndClipsTheMonth() {
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 10, 1), 100),
                expense(LocalDate.of(2026, 10, 4), 50),
                expense(LocalDate.of(2026, 10, 5), 200),
                expense(LocalDate.of(2026, 10, 31), 300),
                expense(LocalDate.of(2026, 11, 1), 999),
            ),
        )
        val buckets = FinanceCalculator.weeklyTrend(snapshot, YearMonth.of(2026, 10))

        assertEquals(5, buckets.size)
        assertEquals("10/1–10/4", buckets[0].label)
        assertEquals(LocalDate.of(2026, 10, 1), buckets[0].startDate)
        assertEquals(LocalDate.of(2026, 10, 4), buckets[0].endDate)
        assertEquals(150L, buckets[0].netSpentFen)
        assertEquals("10/5–10/11", buckets[1].label)
        assertEquals(200L, buckets[1].netSpentFen)
        assertEquals("10/26–10/31", buckets[4].label)
        assertEquals(LocalDate.of(2026, 10, 31), buckets[4].endDate)
        assertEquals(300L, buckets[4].netSpentFen)
        assertTrue(buckets.none { it.startDate.isAfter(LocalDate.of(2026, 10, 31)) })
    }

    @Test
    fun monthlyTrend_returnsSixMonthsEndingAtSelectionAndIncludesRefunds() {
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 8, 2), 1_000),
                expense(LocalDate.of(2026, 9, 2), 2_000),
                expense(LocalDate.of(2026, 10, 2), 3_000),
                refund(LocalDate.of(2026, 10, 3), 500),
                income(LocalDate.of(2026, 10, 4), 9_999),
            ),
        )
        val buckets = FinanceCalculator.monthlyTrend(snapshot, YearMonth.of(2026, 10))

        assertEquals(6, buckets.size)
        assertEquals("5月", buckets.first().label)
        assertEquals(LocalDate.of(2026, 5, 1), buckets.first().startDate)
        assertEquals("10月", buckets.last().label)
        assertEquals(LocalDate.of(2026, 10, 31), buckets.last().endDate)
        assertEquals(1_000L, buckets.first { it.label == "8月" }.netSpentFen)
        assertEquals(2_000L, buckets.first { it.label == "9月" }.netSpentFen)
        assertEquals(2_500L, buckets.first { it.label == "10月" }.netSpentFen)
        assertEquals(0L, buckets.first { it.label == "7月" }.netSpentFen)
    }

    @Test
    fun categoryBudgets_reportUsageAndNegativeNetCategoriesDoNotGetShare() {
        val month = YearMonth.of(2026, 10)
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 10, 1), 9_500, categoryId = 1),
                expense(LocalDate.of(2026, 10, 2), 6_000, categoryId = 4),
                expense(LocalDate.of(2026, 10, 3), 1_000, categoryId = 5),
                refund(LocalDate.of(2026, 10, 4), 2_000, categoryId = 5),
            ),
            categoryBudgets = listOf(
                categoryBudget(month, 1, 10_000),
                categoryBudget(month, 4, 5_000),
                categoryBudget(month, 5, 500),
            ),
        )
        val breakdown = FinanceCalculator.categoryBreakdown(snapshot, month)
        val dining = breakdown.first { it.category.id == 1L }
        val transport = breakdown.first { it.category.id == 4L }
        val entertainment = breakdown.first { it.category.id == 5L }

        assertEquals(95, dining.limitUsagePercent)
        assertEquals(120, transport.limitUsagePercent)
        assertEquals(9_500L, dining.netSpentFen)
        assertEquals(6_000L, transport.netSpentFen)
        assertEquals(0, entertainment.sharePercent)
        assertEquals(-1_000L, entertainment.netSpentFen)
        assertEquals(0, entertainment.limitUsagePercent)
    }

    @Test
    fun categoryShares_areRoundedAcrossPositiveNetOnly() {
        val month = YearMonth.of(2026, 10)
        val snapshot = snapshot(
            transactions = listOf(
                expense(LocalDate.of(2026, 10, 1), 100, categoryId = 1),
                expense(LocalDate.of(2026, 10, 2), 50, categoryId = 3),
                expense(LocalDate.of(2026, 10, 3), 100, categoryId = 4),
                refund(LocalDate.of(2026, 10, 4), 150, categoryId = 4),
            ),
        )
        val breakdown = FinanceCalculator.categoryBreakdown(snapshot, month)
        assertEquals(67, breakdown.first { it.category.id == 1L }.sharePercent)
        assertEquals(33, breakdown.first { it.category.id == 3L }.sharePercent)
        assertEquals(0, breakdown.first { it.category.id == 4L }.sharePercent)
    }

    @Test
    fun previousMonthBudget_isOnlyTheImmediatelyPrecedingMonth() {
        val snapshot = snapshot(
            budgets = listOf(
                budget(YearMonth.of(2026, 8), 5_000),
                budget(YearMonth.of(2026, 9), 6_000),
            ),
        )
        assertEquals(6_000L, FinanceCalculator.previousMonthBudgetFen(snapshot, YearMonth.of(2026, 10)))
        assertEquals(5_000L, FinanceCalculator.previousMonthBudgetFen(snapshot, YearMonth.of(2026, 9)))
        assertNull(FinanceCalculator.previousMonthBudgetFen(snapshot, YearMonth.of(2026, 8)))
    }

    private fun snapshot(
        transactions: List<FinanceTransaction> = emptyList(),
        budgets: List<MonthlyBudget> = emptyList(),
        categoryBudgets: List<CategoryBudget> = emptyList(),
    ) = FinanceSnapshot(
        categories = PRESET_FINANCE_CATEGORIES,
        transactions = transactions,
        monthlyBudgets = budgets,
        categoryBudgets = categoryBudgets,
    )

    private fun expense(date: LocalDate, fen: Long, categoryId: Long = 1L) =
        FinanceTransaction(
            date = date,
            type = FinanceTransactionType.EXPENSE,
            amountFen = fen,
            categoryId = categoryId,
        )

    private fun refund(date: LocalDate, fen: Long, categoryId: Long = 1L) =
        FinanceTransaction(
            date = date,
            type = FinanceTransactionType.REFUND,
            amountFen = fen,
            categoryId = categoryId,
        )

    private fun income(date: LocalDate, fen: Long) = FinanceTransaction(
        date = date,
        type = FinanceTransactionType.INCOME,
        amountFen = fen,
        categoryId = null,
    )

    private fun budget(month: YearMonth, fen: Long) = MonthlyBudget(
        month = month,
        totalBudgetFen = fen,
        updatedAt = Instant.EPOCH,
    )

    private fun categoryBudget(month: YearMonth, categoryId: Long, fen: Long) = CategoryBudget(
        month = month,
        categoryId = categoryId,
        limitFen = fen,
        updatedAt = Instant.EPOCH,
    )
}
