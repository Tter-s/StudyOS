package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType

/**
 * CSV 导出。金额输出为不含千分位的元、保留两位小数；所有字段按 RFC 4180 加引号，
 * 并带 UTF-8 BOM 以便中文 Excel 正确识别编码。支出、收入、退款金额都为正数，由类型区分。
 */
object FinanceCsv {
    const val UTF8_BOM = "\uFEFF"
    private const val HEADER = "日期,类型,分类,金额(元),备注,记录ID"

    fun encode(snapshot: FinanceSnapshot): String {
        val categoryNames = snapshot.categories.associate { it.id to it.name }
        val rows = snapshot.transactions
            .sortedWith(compareBy<FinanceTransaction>({ it.date }, { it.id }))
            .map { transaction ->
                listOf(
                    transaction.date.toString(),
                    typeLabel(transaction.type),
                    transaction.categoryId?.let(categoryNames::get).orEmpty(),
                    Money.formatPlainYuan(transaction.amountFen),
                    transaction.note,
                    transaction.id.toString(),
                ).joinToString(",") { field -> quote(field) }
            }
        return buildString {
            append(UTF8_BOM)
            append(HEADER)
            append("\r\n")
            rows.forEach {
                append(it)
                append("\r\n")
            }
        }
    }

    fun typeLabel(type: FinanceTransactionType): String = when (type) {
        FinanceTransactionType.EXPENSE -> "支出"
        FinanceTransactionType.INCOME -> "收入"
        FinanceTransactionType.REFUND -> "退款"
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
