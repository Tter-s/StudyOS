package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceSearchTest {
    private val transaction = FinanceTransaction(
        id = 1L,
        date = LocalDate.of(2026, 10, 5),
        type = FinanceTransactionType.EXPENSE,
        amountFen = 12345L,
        categoryId = 1L,
        note = "Lunch with Alice, 套餐",
    )

    @Test
    fun emptyQuery_matchesEverything() {
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", ""))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "   "))
    }

    @Test
    fun matchesNoteCategoryTypeDateAndAmount() {
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "alice"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "套餐"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "餐饮"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "支出"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "2026-10-05"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "123.45"))
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "¥123.45"))
        assertFalse(FinanceSearch.matches(transaction, "餐饮", "支出", "收入"))
    }

    @Test
    fun trimsQueryAndRejectsNonMatches() {
        assertTrue(FinanceSearch.matches(transaction, "餐饮", "支出", "  ALICE  "))
        assertFalse(FinanceSearch.matches(transaction, "餐饮", "支出", "晚餐"))
        assertFalse(FinanceSearch.matches(transaction, "餐饮", "支出", "2025-10-05"))
    }
}
