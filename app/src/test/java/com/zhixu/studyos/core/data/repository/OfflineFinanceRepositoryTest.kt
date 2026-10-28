package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.FinanceCategoryEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.finance.FinanceCalculator
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineFinanceRepositoryTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), zoneId)
    private lateinit var database: StudyOSDatabase
    private lateinit var repository: OfflineFinanceRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = OfflineFinanceRepository(database, database.financeDao())
        PRESET_FINANCE_CATEGORIES.forEach { category ->
            database.financeDao().insertCategory(
                FinanceCategoryEntity(
                    id = category.id,
                    name = category.name,
                    isPreset = true,
                    sortOrder = category.sortOrder,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun transactionCrud_persistsExactFenAndTrimsNotes() = runTest {
        val created = repository.saveTransaction(
            transaction(amountFen = 1_234, note = "  午饭  ", categoryId = 1L),
            clock.instant(),
        )
        assertEquals(1_234L, created.amountFen)
        assertEquals("午饭", created.note)
        assertNotNull(created.createdAt)

        val income = repository.saveTransaction(
            transaction(
                date = LocalDate.of(2026, 10, 26),
                type = FinanceTransactionType.INCOME,
                amountFen = 50_000,
                categoryId = 1L,
                note = "兼职工资",
            ),
            clock.instant(),
        )
        assertNull(income.categoryId)

        val updated = repository.saveTransaction(
            created.copy(amountFen = 1_500, note = "午饭加饮料"),
            clock.instant(),
        )
        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)

        val snapshot = repository.observeSnapshot().first()
        assertEquals(2, snapshot.transactions.size)
        val stored = snapshot.transactions.first { it.id == created.id }
        assertEquals(1_500L, stored.amountFen)
        assertEquals("午饭加饮料", stored.note)
        assertEquals(1L, stored.categoryId)
        assertNull(snapshot.transactions.first { it.id == income.id }.categoryId)

        repository.deleteTransaction(created.id)
        val afterDelete = repository.getSnapshot()
        assertEquals(1, afterDelete.transactions.size)
        assertEquals(income.id, afterDelete.transactions.single().id)
    }

    @Test
    fun negativeZeroAndInvalidAmountsAreRejectedWithoutWriting() = runTest {
        listOf(-1L, 0L, Money.MAX_AMOUNT_FEN + 1L).forEach { amount ->
            val result = runCatching {
                repository.saveTransaction(transaction(amountFen = amount), clock.instant())
            }
            assertTrue("金额 $amount 应被拒绝", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        val noCategory = runCatching {
            repository.saveTransaction(transaction(categoryId = null), clock.instant())
        }
        assertTrue(noCategory.isFailure)
        assertEquals("请选择分类", noCategory.exceptionOrNull()?.message)

        val missingCategory = runCatching {
            repository.saveTransaction(transaction(categoryId = 999L), clock.instant())
        }
        assertTrue(missingCategory.isFailure)
        assertEquals("所选分类不存在，可能已被删除", missingCategory.exceptionOrNull()?.message)
        assertTrue(repository.getSnapshot().transactions.isEmpty())
    }

    @Test
    fun refundRequiresCategoryAndIncomeAlwaysHasNoCategory() = runTest {
        val refundWithoutCategory = runCatching {
            repository.saveTransaction(
                transaction(type = FinanceTransactionType.REFUND, categoryId = null),
                clock.instant(),
            )
        }
        assertTrue(refundWithoutCategory.isFailure)

        val refund = repository.saveTransaction(
            transaction(type = FinanceTransactionType.REFUND, amountFen = 500, categoryId = 1L),
            clock.instant(),
        )
        assertEquals(1L, refund.categoryId)

        val income = repository.saveTransaction(
            transaction(type = FinanceTransactionType.INCOME, amountFen = 20_000, categoryId = null),
            clock.instant(),
        )
        assertNull(income.categoryId)
    }

    @Test
    fun crossMonthAndRefunds_arePersistedByRecordDate() = runTest {
        repository.saveTransaction(
            transaction(date = LocalDate.of(2026, 1, 31), amountFen = 10_000),
            clock.instant(),
        )
        repository.saveTransaction(
            transaction(date = LocalDate.of(2026, 2, 1), amountFen = 5_000),
            clock.instant(),
        )
        repository.saveTransaction(
            transaction(
                date = LocalDate.of(2026, 2, 2),
                type = FinanceTransactionType.REFUND,
                amountFen = 2_000,
            ),
            clock.instant(),
        )
        repository.saveMonthlyBudget(YearMonth.of(2026, 2), 4_000, clock.instant())

        val snapshot = repository.getSnapshot()
        val january = FinanceCalculator.monthSummary(
            snapshot,
            YearMonth.of(2026, 1),
            LocalDate.of(2026, 2, 10),
        )
        val february = FinanceCalculator.monthSummary(
            snapshot,
            YearMonth.of(2026, 2),
            LocalDate.of(2026, 2, 10),
        )
        assertEquals(10_000L, january.netSpentFen)
        assertEquals(3_000L, february.netSpentFen)
        assertEquals(1_000L, february.remainingFen)
        assertEquals(BudgetStatus.HEALTHY, february.status)
    }

    @Test
    fun monthlyBudget_isPerMonthAndSupportsAdjustmentAndClearing() = runTest {
        repository.saveMonthlyBudget(YearMonth.of(2026, 10), 100_000, clock.instant())
        repository.saveMonthlyBudget(YearMonth.of(2026, 9), 80_000, clock.instant())
        assertEquals(2, repository.getSnapshot().monthlyBudgets.size)

        repository.saveMonthlyBudget(YearMonth.of(2026, 10), 60_000, clock.instant())
        assertEquals(
            60_000L,
            repository.getSnapshot().monthlyBudgets.first { it.month == YearMonth.of(2026, 10) }.totalBudgetFen,
        )

        repository.saveMonthlyBudget(YearMonth.of(2026, 10), null, clock.instant())
        val remaining = repository.getSnapshot().monthlyBudgets
        assertEquals(1, remaining.size)
        assertEquals(YearMonth.of(2026, 9), remaining.single().month)

        listOf(0L, -1L, Money.MAX_AMOUNT_FEN + 1L).forEach { amount ->
            val result = runCatching {
                repository.saveMonthlyBudget(YearMonth.of(2026, 10), amount, clock.instant())
            }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun categoryBudget_isPerMonthAndRequiresExistingPositiveCategory() = runTest {
        repository.saveCategoryBudget(YearMonth.of(2026, 10), 1L, 30_000, clock.instant())
        repository.saveCategoryBudget(YearMonth.of(2026, 9), 1L, 20_000, clock.instant())
        assertEquals(2, repository.getSnapshot().categoryBudgets.size)

        repository.saveCategoryBudget(YearMonth.of(2026, 10), 1L, 25_000, clock.instant())
        assertEquals(
            25_000L,
            repository.getSnapshot().categoryBudgets
                .first { it.month == YearMonth.of(2026, 10) && it.categoryId == 1L }
                .limitFen,
        )

        repository.saveCategoryBudget(YearMonth.of(2026, 10), 1L, null, clock.instant())
        assertEquals(1, repository.getSnapshot().categoryBudgets.size)

        val missing = runCatching {
            repository.saveCategoryBudget(YearMonth.of(2026, 10), 999L, 1_000, clock.instant())
        }
        assertTrue(missing.isFailure)
        val zero = runCatching {
            repository.saveCategoryBudget(YearMonth.of(2026, 10), 1L, 0, clock.instant())
        }
        assertTrue(zero.isFailure)
    }

    @Test
    fun customCategories_supportAddRenameDeleteAndRejectPresetChanges() = runTest {
        val custom = repository.addCategory("  文具  ")
        assertEquals("文具", custom.name)
        assertFalse(custom.isPreset)

        val duplicate = runCatching { repository.addCategory(" 文具 ") }
        assertTrue(duplicate.isFailure)
        assertEquals("该分类已存在", duplicate.exceptionOrNull()?.message)

        repository.renameCategory(custom.id, "学习文具")
        assertEquals(
            "学习文具",
            repository.getSnapshot().categories.first { it.id == custom.id }.name,
        )

        val presetRename = runCatching { repository.renameCategory(1L, "吃饭") }
        assertTrue(presetRename.isFailure)
        val presetDelete = runCatching { repository.deleteCategory(1L) }
        assertTrue(presetDelete.isFailure)

        repository.deleteCategory(custom.id)
        assertTrue(repository.getSnapshot().categories.none { it.id == custom.id })
    }

    @Test
    fun categoryWithTransactionsCannotBeDeletedAndEmptyCategoryCascadesBudget() = runTest {
        val used = repository.addCategory("零食")
        repository.saveCategoryBudget(YearMonth.of(2026, 10), used.id, 10_000, clock.instant())
        repository.saveTransaction(transaction(categoryId = used.id), clock.instant())

        val rejected = runCatching { repository.deleteCategory(used.id) }
        assertTrue(rejected.isFailure)
        assertEquals(
            "该分类已有记账记录，无法删除；可以继续使用或新建分类",
            rejected.exceptionOrNull()?.message,
        )

        val empty = repository.addCategory("闲置分类")
        repository.saveCategoryBudget(YearMonth.of(2026, 10), empty.id, 5_000, clock.instant())
        repository.deleteCategory(empty.id)
        val snapshot = repository.getSnapshot()
        assertTrue(snapshot.categories.none { it.id == empty.id })
        assertTrue(snapshot.categoryBudgets.none { it.categoryId == empty.id })
    }

    @Test
    fun inputValidation_rejectsLongNotesAndNames() = runTest {
        val longNote = runCatching {
            repository.saveTransaction(transaction(note = "记".repeat(201)), clock.instant())
        }
        assertTrue(longNote.isFailure)
        repository.saveTransaction(transaction(note = "记".repeat(200)), clock.instant())

        val longName = runCatching { repository.addCategory("分".repeat(13)) }
        assertTrue(longName.isFailure)
        val blankName = runCatching { repository.addCategory("   ") }
        assertTrue(blankName.isFailure)
        assertEquals("请输入分类名称", blankName.exceptionOrNull()?.message)
    }

    @Test
    fun amountsStayExactThroughDatabaseAggregation() = runTest {
        repeat(3) {
            repository.saveTransaction(
                transaction(date = LocalDate.of(2026, 10, 25), amountFen = 1),
                clock.instant(),
            )
        }
        val summary = FinanceCalculator.monthSummary(
            repository.getSnapshot(),
            YearMonth.of(2026, 10),
            LocalDate.of(2026, 10, 25),
        )
        assertEquals(3L, summary.expenseFen)
        assertEquals("¥0.03", Money.formatFen(summary.expenseFen))
    }

    private fun transaction(
        date: LocalDate = LocalDate.of(2026, 10, 25),
        type: FinanceTransactionType = FinanceTransactionType.EXPENSE,
        amountFen: Long = 1_000,
        categoryId: Long? = 1L,
        note: String = "",
    ) = FinanceTransaction(
        date = date,
        type = type,
        amountFen = amountFen,
        categoryId = categoryId,
        note = note,
    )
}
