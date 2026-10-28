package com.zhixu.studyos.core.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.model.CategoryBudget
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthlyBudget
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FinanceBackupTest {
    @Test
    fun backup_containsMetadataCategoriesTransactionsAndBudgetsInFen() {
        val exportedAt = Instant.parse("2026-10-25T02:30:00Z")
        val snapshot = FinanceSnapshot(
            categories = PRESET_FINANCE_CATEGORIES,
            transactions = listOf(
                FinanceTransaction(
                    id = 1,
                    date = LocalDate.of(2026, 10, 5),
                    type = FinanceTransactionType.EXPENSE,
                    amountFen = 1_234,
                    categoryId = 1,
                    note = "午饭",
                    createdAt = Instant.parse("2026-10-05T03:00:00Z"),
                    updatedAt = Instant.parse("2026-10-05T03:00:00Z"),
                ),
                FinanceTransaction(
                    id = 2,
                    date = LocalDate.of(2026, 10, 6),
                    type = FinanceTransactionType.INCOME,
                    amountFen = 50_000,
                    categoryId = null,
                    note = "兼职工资",
                    createdAt = Instant.parse("2026-10-06T03:00:00Z"),
                    updatedAt = Instant.parse("2026-10-06T03:00:00Z"),
                ),
            ),
            monthlyBudgets = listOf(
                MonthlyBudget(YearMonth.of(2026, 10), 100_000, exportedAt),
            ),
            categoryBudgets = listOf(
                CategoryBudget(YearMonth.of(2026, 10), 1, 30_000, exportedAt),
            ),
        )

        val json = JSONObject(
            FinanceBackup.encode(
                snapshot = snapshot,
                exportedAt = exportedAt,
                zoneId = ZoneId.of("Asia/Shanghai"),
                appVersion = "0.5.0",
                databaseVersion = 5,
            ),
        )

        assertEquals(FinanceBackup.FORMAT, json.getString("format"))
        assertEquals(FinanceBackup.FORMAT_VERSION, json.getInt("formatVersion"))
        assertEquals(5, json.getInt("databaseVersion"))
        assertEquals("0.5.0", json.getString("appVersion"))
        assertEquals("CNY", json.getString("currency"))
        assertEquals("fen", json.getString("amountUnit"))
        assertEquals("2026-10-25T02:30:00Z", json.getString("exportedAt"))
        assertEquals("Asia/Shanghai", json.getString("timeZone"))

        val counts = json.getJSONObject("counts")
        assertEquals(7, counts.getInt("categories"))
        assertEquals(2, counts.getInt("transactions"))
        assertEquals(1, counts.getInt("monthlyBudgets"))
        assertEquals(1, counts.getInt("categoryBudgets"))

        val categories = json.getJSONArray("categories")
        assertEquals(7, categories.length())
        assertEquals("餐饮", categories.getJSONObject(0).getString("name"))
        assertTrue(categories.getJSONObject(0).getBoolean("isPreset"))

        val transactions = json.getJSONArray("transactions")
        assertEquals(2, transactions.length())
        val expense = transactions.getJSONObject(0)
        assertEquals("2026-10-05", expense.getString("date"))
        assertEquals("EXPENSE", expense.getString("type"))
        assertEquals(1_234L, expense.getLong("amountFen"))
        assertEquals(1L, expense.getLong("categoryId"))
        assertEquals("午饭", expense.getString("note"))
        assertEquals(1_791_169_200_000L, expense.getLong("createdAtEpochMillis"))
        val income = transactions.getJSONObject(1)
        assertTrue(income.isNull("categoryId"))

        val budgets = json.getJSONArray("monthlyBudgets")
        assertEquals("2026-10", budgets.getJSONObject(0).getString("month"))
        assertEquals(100_000L, budgets.getJSONObject(0).getLong("totalBudgetFen"))

        val categoryBudgets = json.getJSONArray("categoryBudgets")
        assertEquals(30_000L, categoryBudgets.getJSONObject(0).getLong("limitFen"))
        assertEquals(1L, categoryBudgets.getJSONObject(0).getLong("categoryId"))
    }

    @Test
    fun emptyBackup_stillHasCountsAndArrays() {
        val json = JSONObject(
            FinanceBackup.encode(
                snapshot = FinanceSnapshot(),
                exportedAt = Instant.EPOCH,
                zoneId = ZoneId.of("UTC"),
                appVersion = "0.5.0",
                databaseVersion = 5,
            ),
        )
        val counts = json.getJSONObject("counts")
        assertEquals(0, counts.getInt("categories"))
        assertEquals(0, counts.getInt("transactions"))
        assertEquals(0, counts.getInt("monthlyBudgets"))
        assertEquals(0, counts.getInt("categoryBudgets"))
        assertEquals(0, json.getJSONArray("categories").length())
    }
}
