package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.CategorySpending
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.FinanceTrendBucket
import com.zhixu.studyos.core.model.MonthFinanceSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * 财务汇总与趋势计算。所有金额运算使用 Long 分，不使用浮点数。
 *
 * 口径说明：
 * - 净支出 = 支出 - 退款；收入不参与生活费预算。
 * - 退款计入记录当月的预算和分类消费，不追溯修改原支出月份。
 * - 月度归属只由用户选择的记账日期决定。
 * - 周趋势按周一至周日切分，首尾周裁剪到所选月份内。
 */
object FinanceCalculator {
    const val NEAR_LIMIT_PERCENT = 80
    private const val MAX_DISPLAY_PERCENT = 9999

    fun monthSummary(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        today: LocalDate,
    ): MonthFinanceSummary {
        var expense = 0L
        var refund = 0L
        var income = 0L
        snapshot.transactions.forEach { transaction ->
            if (YearMonth.from(transaction.date) != month) return@forEach
            // 未来日期的记账不改变“已经花掉/已用预算”，只作为未来计划展示。
            if (transaction.date.isAfter(today)) return@forEach
            when (transaction.type) {
                FinanceTransactionType.EXPENSE -> expense += transaction.amountFen
                FinanceTransactionType.REFUND -> refund += transaction.amountFen
                FinanceTransactionType.INCOME -> income += transaction.amountFen
            }
        }
        val netSpent = expense - refund
        val budget = snapshot.monthlyBudgets.firstOrNull { it.month == month }?.totalBudgetFen
        val remaining = budget?.let { it - netSpent }
        val usagePercent = if (budget == null || budget <= 0L || netSpent <= 0L) {
            0
        } else {
            ((netSpent * 100L) / budget).coerceAtMost(MAX_DISPLAY_PERCENT.toLong()).toInt()
        }
        val status = when {
            budget == null -> BudgetStatus.NOT_SET
            netSpent > budget -> BudgetStatus.EXCEEDED
            usagePercent >= NEAR_LIMIT_PERCENT -> BudgetStatus.NEAR_LIMIT
            else -> BudgetStatus.HEALTHY
        }
        val remainingDays = if (YearMonth.from(today) == month) {
            month.lengthOfMonth() - today.dayOfMonth + 1
        } else {
            null
        }
        val dailyAllowance = if (budget != null && remaining != null && remainingDays != null) {
            remaining.coerceAtLeast(0L) / remainingDays
        } else {
            null
        }
        return MonthFinanceSummary(
            month = month,
            totalBudgetFen = budget,
            expenseFen = expense,
            refundFen = refund,
            incomeFen = income,
            netSpentFen = netSpent,
            remainingFen = remaining,
            status = status,
            usagePercent = usagePercent,
            remainingDays = remainingDays,
            dailyAllowanceFen = dailyAllowance,
        )
    }

    /**
     * 返回全部分类的当月净支出、预算、占比与记录数，按分类顺序排列。
     * 占比只在净支出为正的分类之间计算，退款超过支出的分类占比为 0。
     */
    fun categoryBreakdown(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        today: LocalDate? = null,
    ): List<CategorySpending> {
        val monthTransactions = snapshot.transactions.filter {
            YearMonth.from(it.date) == month && (today == null || !it.date.isAfter(today))
        }
        val positiveNetTotal = snapshot.categories.sumOf { category ->
            val net = monthTransactions
                .filter { it.categoryId == category.id && it.type != FinanceTransactionType.INCOME }
                .sumOf { if (it.type == FinanceTransactionType.EXPENSE) it.amountFen else -it.amountFen }
            net.coerceAtLeast(0L)
        }
        return snapshot.categories.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { category ->
            val relevant = monthTransactions.filter { it.categoryId == category.id }
            val expense = relevant
                .filter { it.type == FinanceTransactionType.EXPENSE }
                .sumOf { it.amountFen }
            val refund = relevant
                .filter { it.type == FinanceTransactionType.REFUND }
                .sumOf { it.amountFen }
            val net = expense - refund
            val limit = snapshot.categoryBudgets
                .firstOrNull { it.month == month && it.categoryId == category.id }
                ?.limitFen
            val limitUsagePercent = if (limit != null && limit > 0L) {
                if (net <= 0L) {
                    0
                } else {
                    ((net * 100L) / limit)
                        .coerceAtMost(MAX_DISPLAY_PERCENT.toLong())
                        .toInt()
                }
            } else {
                null
            }
            val sharePercent = if (net > 0L && positiveNetTotal > 0L) {
                ((net * 100L + positiveNetTotal / 2L) / positiveNetTotal).toInt()
            } else {
                0
            }
            CategorySpending(
                category = category,
                expenseFen = expense,
                refundFen = refund,
                netSpentFen = net,
                limitFen = limit,
                limitUsagePercent = limitUsagePercent,
                sharePercent = sharePercent,
                transactionCount = relevant.size,
            )
        }
    }

    /** 所选月份内的自然周（周一至周日），首尾周裁剪到月份边界。 */
    fun weeklyTrend(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        today: LocalDate? = null,
    ): List<FinanceTrendBucket> {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val actualEnd = if (today != null && YearMonth.from(today) == month) minOf(monthEnd, today) else monthEnd
        var weekStart = monthStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val buckets = mutableListOf<FinanceTrendBucket>()
        while (!weekStart.isAfter(monthEnd)) {
            val start = maxOf(weekStart, monthStart)
            val end = minOf(weekStart.plusDays(6), actualEnd)
            buckets += FinanceTrendBucket(
                startDate = start,
                endDate = end,
                label = rangeLabel(start, end),
                netSpentFen = netSpentBetween(snapshot.transactions, start, end),
            )
            weekStart = weekStart.plusWeeks(1)
        }
        return buckets
    }

    /** 以所选月份为终点，返回连续若干个月的净支出趋势。 */
    fun monthlyTrend(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        monthCount: Int = 6,
    ): List<FinanceTrendBucket> {
        require(monthCount > 0) { "月份数量必须大于 0" }
        val byMonth = snapshot.transactions.groupBy { YearMonth.from(it.date) }
        return (monthCount - 1 downTo 0).map { offset ->
            val target = month.minusMonths(offset.toLong())
            val transactions = byMonth[target].orEmpty()
            FinanceTrendBucket(
                startDate = target.atDay(1),
                endDate = target.atEndOfMonth(),
                label = "${target.monthValue}月",
                netSpentFen = netSpent(transactions),
            )
        }
    }

    fun previousMonthBudgetFen(snapshot: FinanceSnapshot, month: YearMonth): Long? =
        snapshot.monthlyBudgets.firstOrNull { it.month == month.minusMonths(1) }?.totalBudgetFen

    private fun netSpentBetween(
        transactions: List<FinanceTransaction>,
        start: LocalDate,
        end: LocalDate,
    ): Long = netSpent(transactions.filter { !it.date.isBefore(start) && !it.date.isAfter(end) })

    private fun netSpent(transactions: List<FinanceTransaction>): Long {
        var result = 0L
        transactions.forEach { transaction ->
            when (transaction.type) {
                FinanceTransactionType.EXPENSE -> result += transaction.amountFen
                FinanceTransactionType.REFUND -> result -= transaction.amountFen
                FinanceTransactionType.INCOME -> Unit
            }
        }
        return result
    }

    private fun rangeLabel(start: LocalDate, end: LocalDate): String =
        if (start == end) {
            "${start.monthValue}/${start.dayOfMonth}"
        } else {
            "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
        }
}
