package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthlyBudget
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceForecastTest {
    private val month = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 10)
    private val category = FinanceCategory(id = 1, name = "餐饮", isPreset = true, sortOrder = 1)

    private fun expense(day: Int, amount: Long, month: YearMonth = this.month, expected: Boolean = false) =
        FinanceTransaction(
            date = month.atDay(day.coerceAtMost(month.lengthOfMonth())),
            type = FinanceTransactionType.EXPENSE,
            amountFen = amount,
            categoryId = 1,
            isExpectedExpense = expected,
        )

    @Test
    fun forecast_projectsMonthEndAndFlagsLikelyOverBudget() {
        val snapshot = FinanceSnapshot(
            categories = listOf(category),
            transactions = listOf(expense(5, 50_000)),
            monthlyBudgets = listOf(
                MonthlyBudget(month = month, totalBudgetFen = 100_000, updatedAt = Instant.EPOCH),
            ),
        )
        val forecast = FinanceForecastEngine.forecast(snapshot, month, today)
        assertEquals(10, forecast.elapsedDays)
        assertEquals(21, forecast.remainingDays)
        assertEquals(5_000L, forecast.averageDailySpendFen)
        assertEquals(155_000L, forecast.projectedTotalFen)
        assertEquals(-55_000L, forecast.projectedRemainingFen)
        assertEquals(BudgetRisk.LIKELY_OVER, forecast.risk)
        assertTrue(forecast.explanation.contains("线性估算"))
    }

    @Test
    fun noBudget_isExplicitlyNoBudgetNotZero() {
        val forecast = FinanceForecastEngine.forecast(
            FinanceSnapshot(categories = listOf(category)),
            month,
            today,
        )
        assertEquals(BudgetRisk.NO_BUDGET, forecast.risk)
        assertEquals(null, forecast.projectedRemainingFen)
        assertTrue(forecast.explanation.contains("尚未设置预算"))
    }

    @Test
    fun anomaly_requiresEnoughCategoryHistoryAndCanBeMarkedNormal() {
        val history = (7..9).flatMap { m ->
            listOf(expense(5, 10_000, YearMonth.of(2026, m)), expense(15, 10_000, YearMonth.of(2026, m)))
        }
        val current = expense(9, 80_000)
        val snapshot = FinanceSnapshot(
            categories = listOf(category),
            transactions = history + current,
        )
        val anomalies = FinanceForecastEngine.detectAnomalies(snapshot, month, today)
        assertEquals(1, anomalies.size)
        assertEquals(80_000L, anomalies.single().amountFen)
        assertTrue(anomalies.single().message.contains("可标记为正常"))

        val marked = FinanceForecastEngine.detectAnomalies(
            snapshot.copy(transactions = history + current.copy(isExpectedExpense = true)),
            month,
            today,
        )
        assertTrue(marked.isEmpty())
    }

    @Test
    fun anomaly_isSkippedWhenHistoryIsTooSmall() {
        val snapshot = FinanceSnapshot(
            categories = listOf(category),
            transactions = listOf(
                expense(1, 5_000, YearMonth.of(2026, 9)),
                expense(9, 80_000),
            ),
        )
        assertTrue(FinanceForecastEngine.detectAnomalies(snapshot, month, today).isEmpty())
    }

    @Test
    fun savingsSuggestions_areNeutralAndExplainFixedShare() {
        val snapshot = FinanceSnapshot(
            categories = listOf(category),
            transactions = listOf(expense(1, 90_000)),
            monthlyBudgets = listOf(
                MonthlyBudget(month = month, totalBudgetFen = 100_000, updatedAt = Instant.EPOCH),
            ),
        )
        val forecast = FinanceForecastEngine.forecast(snapshot, month, today)
        val suggestions = FinanceForecastEngine.savingsSuggestions(forecast, snapshot, month)
        assertTrue(suggestions.isNotEmpty())
        assertFalse(suggestions.any { it.detail.contains("浪费") || it.detail.contains("可耻") })
    }

    @Test
    fun dailySeries_includesZeroDaysAndRefunds() {
        val snapshot = FinanceSnapshot(
            categories = listOf(category),
            transactions = listOf(
                expense(8, 3_000),
                FinanceTransaction(
                    date = month.atDay(8),
                    type = FinanceTransactionType.REFUND,
                    amountFen = 1_000,
                    categoryId = 1,
                ),
            ),
        )
        val series = FinanceForecastEngine.dailyNetSeries(
            snapshot,
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 9),
        )
        assertEquals(3, series.size)
        assertEquals(0L, series[0].second)
        assertEquals(2_000L, series[1].second)
        assertEquals(0L, series[2].second)
        assertNotNull(series[1].first)
    }
}
