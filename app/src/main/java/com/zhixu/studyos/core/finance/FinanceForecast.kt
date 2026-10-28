package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

enum class BudgetRisk { NO_BUDGET, SAFE, WATCH, LIKELY_OVER, OVER }

data class FinanceForecast(
    val month: YearMonth,
    val today: LocalDate,
    val budgetFen: Long?,
    val spentFen: Long,
    val remainingFen: Long?,
    val elapsedDays: Int,
    val remainingDays: Int,
    val averageDailySpendFen: Long?,
    val plannedFixedFen: Long,
    val projectedTotalFen: Long,
    val projectedRemainingFen: Long?,
    val safeDailyAllowanceFen: Long?,
    val risk: BudgetRisk,
    val explanation: String,
)

data class CategoryAnomaly(
    val categoryName: String,
    val transactionId: Long,
    val amountFen: Long,
    val date: LocalDate,
    val categoryAverageFen: Long,
    val categorySampleCount: Int,
    val thresholdFen: Long,
    val note: String,
    val message: String,
)

data class SavingsSuggestion(
    val title: String,
    val detail: String,
    val severity: BudgetRisk,
)

/**
 * 生活费预测与消费分析。
 *
 * 全部使用整数分；预测是基于历史平均的线性推算，不是承诺或精确账单。
 * 异常消费只在“明显高于该分类历史习惯且样本足够”时提示，允许用户标记为正常特殊开支；
 * 金额高本身不等于浪费，也不使用羞辱或制造焦虑的措辞。
 */
object FinanceForecastEngine {
    private const val MIN_ANOMALY_SAMPLES = 3
    private const val LOOKBACK_MONTHS = 3
    private const val FIXED_SHARE_WARNING_PERCENT = 50L

    fun forecast(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        today: LocalDate,
    ): FinanceForecast {
        val isCurrentMonth = YearMonth.from(today) == month
        val elapsedDays = when {
            today.isBefore(month.atDay(1)) -> 0
            today.isAfter(month.atEndOfMonth()) -> month.lengthOfMonth()
            else -> today.dayOfMonth
        }
        val remainingDays = when {
            !isCurrentMonth -> 0
            else -> (month.lengthOfMonth() - today.dayOfMonth).coerceAtLeast(0)
        }
        var spent = 0L
        snapshot.transactions.forEach { transaction ->
            if (YearMonth.from(transaction.date) == month && !transaction.date.isAfter(today)) {
                when (transaction.type) {
                    FinanceTransactionType.EXPENSE -> spent += transaction.amountFen
                    FinanceTransactionType.REFUND -> spent -= transaction.amountFen
                    FinanceTransactionType.INCOME -> Unit
                }
            }
        }
        val budget = snapshot.monthlyBudgets.firstOrNull { it.month == month }?.totalBudgetFen
        val plannedFixed = snapshot.categoryBudgets.filter { it.month == month }.sumOf { it.limitFen }
        val averageDaily = if (elapsedDays > 0) spent.coerceAtLeast(0) / elapsedDays else null
        val projectedExtra = if (averageDaily != null && remainingDays > 0) {
            averageDaily * remainingDays
        } else {
            0L
        }
        val projectedTotal = spent + projectedExtra
        val projectedRemaining = budget?.let { it - projectedTotal }
        val safeDailyAllowance = if (budget != null && isCurrentMonth && remainingDays > 0) {
            (budget - spent).coerceAtLeast(0L) / (remainingDays + 1)
        } else {
            null
        }
        val risk = when {
            budget == null -> BudgetRisk.NO_BUDGET
            spent > budget -> BudgetRisk.OVER
            projectedTotal > budget -> BudgetRisk.LIKELY_OVER
            safeDailyAllowance != null && averageDaily != null && averageDaily > safeDailyAllowance -> BudgetRisk.WATCH
            else -> BudgetRisk.SAFE
        }
        val explanation = buildString {
            if (budget == null) {
                append("本月尚未设置预算，无法判断预计余额；下方仍显示真实支出与历史日均。")
            } else {
                append("按本月前 $elapsedDays 天的真实净支出（")
                append(Money.formatFen(spent.coerceAtLeast(0)))
                append("）推算，剩余 $remainingDays 天按日均 ")
                append(averageDaily?.let(Money::formatFen) ?: "0.00 元")
                append(" 预计再花 ")
                append(Money.formatFen(projectedExtra))
                append("，月底预计总支出 ")
                append(Money.formatFen(projectedTotal))
                append("。这是线性估算，不含尚未记录的临时开支。")
            }
            if (plannedFixed > 0L) {
                append(" 分类预算合计 ")
                append(Money.formatFen(plannedFixed))
                append("，可作为计划内固定/专项支出的参考。")
            }
        }
        return FinanceForecast(
            month = month,
            today = today,
            budgetFen = budget,
            spentFen = spent,
            remainingFen = budget?.let { it - spent },
            elapsedDays = elapsedDays,
            remainingDays = remainingDays,
            averageDailySpendFen = averageDaily,
            plannedFixedFen = plannedFixed,
            projectedTotalFen = projectedTotal,
            projectedRemainingFen = projectedRemaining,
            safeDailyAllowanceFen = safeDailyAllowance,
            risk = risk,
            explanation = explanation,
        )
    }

    fun detectAnomalies(
        snapshot: FinanceSnapshot,
        month: YearMonth,
        today: LocalDate,
    ): List<CategoryAnomaly> {
        val target = snapshot.transactions.filter {
            YearMonth.from(it.date) == month &&
                it.type == FinanceTransactionType.EXPENSE &&
                it.date <= today
        }
        if (target.isEmpty()) return emptyList()
        val historyStart = month.minusMonths(LOOKBACK_MONTHS.toLong()).atDay(1)
        val historyEnd = month.minusMonths(1).atEndOfMonth()
        val history = snapshot.transactions.filter {
            it.type == FinanceTransactionType.EXPENSE &&
                !it.date.isBefore(historyStart) &&
                !it.date.isAfter(historyEnd)
        }.groupBy { it.categoryId }
        val categoryNames = snapshot.categories.associate { it.id to it.name }
        return target.mapNotNull { transaction ->
            if (transaction.isExpectedExpense) return@mapNotNull null
            val samples = history[transaction.categoryId].orEmpty()
            if (samples.size < MIN_ANOMALY_SAMPLES) return@mapNotNull null
            val average = samples.sumOf { it.amountFen } / samples.size
            val variance = samples.sumOf { sample ->
                val diff = (sample.amountFen - average).toDouble()
                diff * diff
            } / samples.size
            val deviation = sqrt(variance).toLong()
            val threshold = maxOf(average * 2, average + deviation * 2)
            if (transaction.amountFen <= threshold) return@mapNotNull null
            val categoryName = categoryNames[transaction.categoryId] ?: "未分类"
            CategoryAnomaly(
                categoryName = categoryName,
                transactionId = transaction.id,
                amountFen = transaction.amountFen,
                date = transaction.date,
                categoryAverageFen = average,
                categorySampleCount = samples.size,
                thresholdFen = threshold,
                note = transaction.note,
                message = "这笔${Money.formatFen(transaction.amountFen)}的「$categoryName」支出高于近 $LOOKBACK_MONTHS 个月同类平均" +
                    "（${Money.formatFen(average)}）。可能是一次正常的大额开支，可标记为正常。",
            )
        }.sortedByDescending { it.amountFen }
    }

    fun savingsSuggestions(
        forecast: FinanceForecast,
        snapshot: FinanceSnapshot,
        month: YearMonth,
    ): List<SavingsSuggestion> {
        val suggestions = mutableListOf<SavingsSuggestion>()
        if (forecast.risk == BudgetRisk.OVER || forecast.risk == BudgetRisk.LIKELY_OVER) {
            suggestions += SavingsSuggestion(
                title = "预算可能不足",
                detail = if (forecast.risk == BudgetRisk.OVER) {
                    "当前净支出已超过预算；可以检查是否有可以推迟的非必要开支，或调整预算。"
                } else {
                    "按当前日均支出，月底可能超出预算。可优先安排必要支出，并为零星开支留一点余量。"
                },
                severity = BudgetRisk.LIKELY_OVER,
            )
        }
        if (forecast.budgetFen != null && forecast.budgetFen > 0) {
            val share = forecast.plannedFixedFen * 100 / forecast.budgetFen
            if (share >= FIXED_SHARE_WARNING_PERCENT) {
                suggestions += SavingsSuggestion(
                    title = "计划性支出占比较高",
                    detail = "分类预算合计占月度预算 $share%。这是计划内的固定/专项支出，不是浪费；" +
                        "如需要更多弹性，可以调整分类预算，而不是压缩真实必要开支。",
                    severity = BudgetRisk.WATCH,
                )
            }
        }
        snapshot.categoryBudgets.filter { it.month == month }.forEach { budget ->
            val net = snapshot.transactions.filter {
                YearMonth.from(it.date) == month && it.categoryId == budget.categoryId &&
                    it.type != FinanceTransactionType.INCOME
            }.sumOf { if (it.type == FinanceTransactionType.EXPENSE) it.amountFen else -it.amountFen }
            if (net > budget.limitFen) {
                val name = snapshot.categories.firstOrNull { it.id == budget.categoryId }?.name ?: "该分类"
                suggestions += SavingsSuggestion(
                    title = "「$name」超过分类预算",
                    detail = "已用 ${Money.formatFen(net)}，分类预算为 ${Money.formatFen(budget.limitFen)}。" +
                        "可以在后续记录中留意这个分类，也可以调整预算以反映真实需要。",
                    severity = BudgetRisk.WATCH,
                )
            }
        }
        if (forecast.safeDailyAllowanceFen != null && forecast.safeDailyAllowanceFen > 0) {
            suggestions += SavingsSuggestion(
                title = "剩余时间的日均可用金额",
                detail = "在不超出预算的前提下，剩余时间每天大约可用 ${Money.formatFen(forecast.safeDailyAllowanceFen)}。" +
                    "这个数字只是参考，不是消费目标。",
                severity = BudgetRisk.SAFE,
            )
        }
        return suggestions.distinctBy { it.title }
    }

    /** 最近 N 天/自定义区间的按日净支出，供趋势图使用。 */
    fun dailyNetSeries(
        snapshot: FinanceSnapshot,
        start: LocalDate,
        end: LocalDate,
    ): List<Pair<LocalDate, Long>> {
        if (end.isBefore(start)) return emptyList()
        val byDate = snapshot.transactions.groupBy { it.date }
        val days = ChronoUnit.DAYS.between(start, end).toInt()
        return (0..days).map { offset ->
            val date = start.plusDays(offset.toLong())
            val net = byDate[date].orEmpty().sumOf { transaction ->
                when (transaction.type) {
                    FinanceTransactionType.EXPENSE -> transaction.amountFen
                    FinanceTransactionType.REFUND -> -transaction.amountFen
                    FinanceTransactionType.INCOME -> 0L
                }
            }
            date to net
        }
    }

    fun netSpentInRange(
        transactions: List<FinanceTransaction>,
        start: LocalDate,
        end: LocalDate,
    ): Long = transactions.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
        .sumOf { transaction ->
            when (transaction.type) {
                FinanceTransactionType.EXPENSE -> transaction.amountFen
                FinanceTransactionType.REFUND -> -transaction.amountFen
                FinanceTransactionType.INCOME -> 0L
            }
        }
}
