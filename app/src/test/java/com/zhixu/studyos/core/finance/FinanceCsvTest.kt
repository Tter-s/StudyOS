package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceCsvTest {
    @Test
    fun export_hasBomHeaderAndChronologicalRows() {
        val csv = FinanceCsv.encode(snapshot())
        assertTrue(csv.startsWith(FinanceCsv.UTF8_BOM))
        assertTrue(csv.startsWith("${FinanceCsv.UTF8_BOM}日期,类型,分类,金额(元),备注,记录ID\r\n"))
        assertTrue(
            csv.contains(
                "\"2026-01-31\",\"收入\",\"\",\"100.00\",\"生活费\",\"1\"",
            ),
        )
        assertTrue(
            csv.contains(
                "\"2026-02-01\",\"支出\",\"餐饮\",\"12.30\",\"午饭, \"\"套餐\"\"\n第二行\",\"2\"",
            ),
        )
        assertTrue(
            csv.contains(
                "\"2026-02-01\",\"退款\",\"学习资料\",\"5.00\",\"\",\"3\"",
            ),
        )
        val incomeIndex = csv.indexOf("\"2026-01-31\"")
        val expenseIndex = csv.indexOf("\"2026-02-01\",\"支出\"")
        val refundIndex = csv.indexOf("\"2026-02-01\",\"退款\"")
        assertTrue(incomeIndex in 1 until expenseIndex)
        assertTrue(expenseIndex < refundIndex)
    }

    @Test
    fun export_usesPlainYuanWithoutGroupingOrFloatingPoint() {
        val csv = FinanceCsv.encode(
            FinanceSnapshot(
                categories = PRESET_FINANCE_CATEGORIES,
                transactions = listOf(
                    FinanceTransaction(
                        id = 7,
                        date = LocalDate.of(2026, 3, 9),
                        type = FinanceTransactionType.EXPENSE,
                        amountFen = 123_456,
                        categoryId = 2,
                        note = "文具",
                    ),
                ),
            ),
        )
        assertTrue(csv.contains("\"1234.56\""))
        assertTrue(csv.contains("\"日用品\""))
    }

    @Test
    fun emptyLedger_stillExportsHeader() {
        val csv = FinanceCsv.encode(snapshot(emptyList()))
        assertEquals(
            "${FinanceCsv.UTF8_BOM}日期,类型,分类,金额(元),备注,记录ID\r\n",
            csv,
        )
    }

    @Test
    fun typeLabels_areStableChineseLabels() {
        assertEquals("支出", FinanceCsv.typeLabel(FinanceTransactionType.EXPENSE))
        assertEquals("收入", FinanceCsv.typeLabel(FinanceTransactionType.INCOME))
        assertEquals("退款", FinanceCsv.typeLabel(FinanceTransactionType.REFUND))
    }

    private fun snapshot(
        transactions: List<FinanceTransaction> = listOf(
            FinanceTransaction(
                id = 2,
                date = LocalDate.of(2026, 2, 1),
                type = FinanceTransactionType.EXPENSE,
                amountFen = 1_230,
                categoryId = 1,
                note = "午饭, \"套餐\"\n第二行",
            ),
            FinanceTransaction(
                id = 1,
                date = LocalDate.of(2026, 1, 31),
                type = FinanceTransactionType.INCOME,
                amountFen = 10_000,
                categoryId = null,
                note = "生活费",
            ),
            FinanceTransaction(
                id = 3,
                date = LocalDate.of(2026, 2, 1),
                type = FinanceTransactionType.REFUND,
                amountFen = 500,
                categoryId = 3,
                note = "",
            ),
        ),
    ) = FinanceSnapshot(
        categories = PRESET_FINANCE_CATEGORIES,
        transactions = transactions,
    )
}
