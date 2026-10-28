package com.zhixu.studyos.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 记账类型。金额本身始终保存为正数；类型决定它对预算的影响。
 * EXPENSE 增加净支出，REFUND 减少净支出，INCOME 不计入生活费预算。
 */
enum class FinanceTransactionType {
    EXPENSE,
    INCOME,
    REFUND,
}

data class FinanceCategory(
    val id: Long = 0,
    val name: String,
    val isPreset: Boolean,
    val sortOrder: Int,
)

/**
 * 预设分类使用固定 ID，数据库创建和迁移都幂等写入，保证分类顺序稳定。
 */
val PRESET_FINANCE_CATEGORIES: List<FinanceCategory> = listOf(
    FinanceCategory(id = 1, name = "餐饮", isPreset = true, sortOrder = 1),
    FinanceCategory(id = 2, name = "日用品", isPreset = true, sortOrder = 2),
    FinanceCategory(id = 3, name = "学习资料", isPreset = true, sortOrder = 3),
    FinanceCategory(id = 4, name = "交通", isPreset = true, sortOrder = 4),
    FinanceCategory(id = 5, name = "娱乐", isPreset = true, sortOrder = 5),
    FinanceCategory(id = 6, name = "住宿", isPreset = true, sortOrder = 6),
    FinanceCategory(id = 7, name = "其他", isPreset = true, sortOrder = 7),
)

/**
 * 记账日期是用户选择的本地日历日期，以 epochDay 持久化；
 * 月度和周度归属只由该日期决定，不依赖记录创建时的时区或时刻。
 */
data class FinanceTransaction(
    val id: Long = 0,
    val date: LocalDate,
    val type: FinanceTransactionType,
    val amountFen: Long,
    val categoryId: Long? = null,
    val note: String = "",
    /** 用户标记为正常的大额特殊开支，异常检测会跳过。 */
    val isExpectedExpense: Boolean = false,
    val revision: Int = 1,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class MonthlyBudget(
    val month: YearMonth,
    val totalBudgetFen: Long,
    val updatedAt: Instant,
)

data class CategoryBudget(
    val month: YearMonth,
    val categoryId: Long,
    val limitFen: Long,
    val updatedAt: Instant,
)

data class FinanceSnapshot(
    val categories: List<FinanceCategory> = emptyList(),
    val transactions: List<FinanceTransaction> = emptyList(),
    val monthlyBudgets: List<MonthlyBudget> = emptyList(),
    val categoryBudgets: List<CategoryBudget> = emptyList(),
) {
    fun categoryById(id: Long?): FinanceCategory? = categories.firstOrNull { it.id == id }
}

enum class BudgetStatus {
    NOT_SET,
    HEALTHY,
    NEAR_LIMIT,
    EXCEEDED,
}

/**
 * 单月财务汇总。金额单位全部为分。
 * remainingFen、remainingDays 和 dailyAllowanceFen 只在能给出确定口径时非空。
 */
data class MonthFinanceSummary(
    val month: YearMonth,
    val totalBudgetFen: Long?,
    val expenseFen: Long,
    val refundFen: Long,
    val incomeFen: Long,
    val netSpentFen: Long,
    val remainingFen: Long?,
    val status: BudgetStatus,
    val usagePercent: Int,
    val remainingDays: Int?,
    val dailyAllowanceFen: Long?,
)

data class CategorySpending(
    val category: FinanceCategory,
    val expenseFen: Long,
    val refundFen: Long,
    val netSpentFen: Long,
    val limitFen: Long?,
    val limitUsagePercent: Int?,
    val sharePercent: Int,
    val transactionCount: Int,
)

data class FinanceTrendBucket(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val label: String,
    val netSpentFen: Long,
)

enum class BudgetReminderLevel(val rank: Int) {
    NONE(0),
    NEAR_LIMIT(1),
    EXCEEDED(2),
}

data class BudgetReminderState(
    val lastMonth: YearMonth? = null,
    val lastLevel: BudgetReminderLevel? = null,
)
