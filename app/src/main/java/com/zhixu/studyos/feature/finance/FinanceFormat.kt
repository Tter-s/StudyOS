package com.zhixu.studyos.feature.finance

import androidx.compose.ui.graphics.Color
import com.zhixu.studyos.core.finance.FinanceCsv
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import java.time.YearMonth
import java.util.Locale

enum class FinanceSection {
    RECORDS,
    ANALYTICS,
    CATEGORIES,
}

enum class FinanceTransactionFilter {
    ALL,
    EXPENSE,
    INCOME,
    REFUND,
    ;

    fun matches(type: FinanceTransactionType): Boolean = when (this) {
        ALL -> true
        EXPENSE -> type == FinanceTransactionType.EXPENSE
        INCOME -> type == FinanceTransactionType.INCOME
        REFUND -> type == FinanceTransactionType.REFUND
    }
}

val FinanceSection.label: String
    get() = when (this) {
        FinanceSection.RECORDS -> "记录"
        FinanceSection.ANALYTICS -> "统计"
        FinanceSection.CATEGORIES -> "分类"
    }

val FinanceTransactionFilter.label: String
    get() = when (this) {
        FinanceTransactionFilter.ALL -> "全部"
        FinanceTransactionFilter.EXPENSE -> "支出"
        FinanceTransactionFilter.INCOME -> "收入"
        FinanceTransactionFilter.REFUND -> "退款"
    }

fun financeTypeLabel(type: FinanceTransactionType): String = FinanceCsv.typeLabel(type)

val BudgetStatus.label: String
    get() = when (this) {
        BudgetStatus.NOT_SET -> "未设置"
        BudgetStatus.HEALTHY -> "正常"
        BudgetStatus.NEAR_LIMIT -> "接近上限"
        BudgetStatus.EXCEEDED -> "已超支"
    }

fun formatFinanceMonth(month: YearMonth): String = "${month.year}年${month.monthValue}月"

fun financeAmountText(transaction: FinanceTransaction): String = when (transaction.type) {
    FinanceTransactionType.EXPENSE -> "-${Money.formatFen(transaction.amountFen)}"
    FinanceTransactionType.INCOME, FinanceTransactionType.REFUND -> "+${Money.formatFen(transaction.amountFen)}"
}

fun financeTransactionTitle(transaction: FinanceTransaction, categoryName: String?): String = when (transaction.type) {
    FinanceTransactionType.INCOME -> "收入"
    FinanceTransactionType.EXPENSE -> categoryName ?: "未分类"
    FinanceTransactionType.REFUND -> "${categoryName ?: "未分类"}退款"
}

fun financeTransactionSubtitle(transaction: FinanceTransaction): String =
    transaction.note.ifBlank { financeTypeLabel(transaction.type) }

fun financeWeekday(date: java.time.LocalDate): String =
    date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE)

private val categoryPalette = listOf(
    Color(0xFF4A79C4),
    Color(0xFF4E9A84),
    Color(0xFFD1913C),
    Color(0xFFC06C7C),
    Color(0xFF7E6FB5),
    Color(0xFF4E93A8),
    Color(0xFF8AA65C),
    Color(0xFF848B98),
)

fun financeCategoryColor(categoryId: Long): Color {
    val size = categoryPalette.size.toLong()
    val index = ((categoryId - 1L) % size + size) % size
    return categoryPalette[index.toInt()]
}
