package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceTransaction

/**
 * 本地记录搜索。匹配备注、分类名、类型、日期，以及带/不带货币符号的金额文本。
 * 忽略大小写与首尾空白；空查询匹配全部记录。
 */
object FinanceSearch {
    fun matches(
        transaction: FinanceTransaction,
        categoryName: String?,
        typeLabel: String,
        query: String,
    ): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        val candidates = listOf(
            transaction.note,
            categoryName.orEmpty(),
            typeLabel,
            transaction.date.toString(),
            Money.formatPlainYuan(transaction.amountFen),
            Money.formatFen(transaction.amountFen),
        )
        return candidates.any { it.lowercase().contains(needle) }
    }
}
