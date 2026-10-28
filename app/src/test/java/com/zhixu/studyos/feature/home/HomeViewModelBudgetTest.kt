package com.zhixu.studyos.feature.home

import com.zhixu.studyos.core.data.repository.FinanceRepository
import com.zhixu.studyos.core.data.repository.HomeRepository
import com.zhixu.studyos.core.model.BudgetStatus
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthlyBudget
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import com.zhixu.studyos.core.model.StudyTask
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelBudgetTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-24T02:00:00Z"), zoneId)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun budgetSummary_exposesRemainingNearLimitAndDailyAllowance() = runTest {
        val month = YearMonth.of(2026, 10)
        val finance = FakeFinanceRepository(
            FinanceSnapshot(
                categories = PRESET_FINANCE_CATEGORIES,
                transactions = listOf(
                    FinanceTransaction(
                        date = LocalDate.of(2026, 10, 20),
                        type = FinanceTransactionType.EXPENSE,
                        amountFen = 85_000,
                        categoryId = 1,
                    ),
                ),
                monthlyBudgets = listOf(
                    MonthlyBudget(month = month, totalBudgetFen = 100_000, updatedAt = Instant.EPOCH),
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = EmptyHomeRepository(),
            clock = clock,
            financeRepository = finance,
        )
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        val summary = viewModel.uiState.value.budgetSummary
        assertNotNull(summary)
        assertEquals(15_000L, summary?.remainingFen)
        assertEquals(BudgetStatus.NEAR_LIMIT, summary?.status)
        assertEquals(85, summary?.usagePercent)
        assertEquals(8, summary?.remainingDays)
        assertEquals(1_875L, summary?.dailyAllowanceFen)
        collection.cancel()
    }

    @Test
    fun missingFinanceRepository_keepsBudgetUnset() = runTest {
        val viewModel = HomeViewModel(EmptyHomeRepository(), clock)
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.budgetSummary)
        collection.cancel()
    }

    @Test
    fun financeReadFailure_isSurfacedWithoutBlockingHomeTasks() = runTest {
        val viewModel = HomeViewModel(
            repository = EmptyHomeRepository(),
            clock = clock,
            financeRepository = FakeFinanceRepository(FinanceSnapshot(), fail = true),
        )
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.budgetLoadFailed)
        assertNull(state.budgetSummary)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        collection.cancel()
    }

    private class EmptyHomeRepository : HomeRepository {
        override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> = flowOf(emptyList())

        override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = Unit
    }

    private class FakeFinanceRepository(
        private val snapshot: FinanceSnapshot,
        private val fail: Boolean = false,
    ) : FinanceRepository {
        override fun observeSnapshot(): Flow<FinanceSnapshot> = if (fail) {
            flow { throw IllegalStateException("budget read failed") }
        } else {
            flowOf(snapshot)
        }

        override suspend fun getSnapshot(): FinanceSnapshot = snapshot

        override suspend fun saveTransaction(
            transaction: FinanceTransaction,
            now: Instant,
        ): FinanceTransaction = transaction

        override suspend fun deleteTransaction(transactionId: Long) = Unit

        override suspend fun saveMonthlyBudget(
            month: YearMonth,
            totalBudgetFen: Long?,
            now: Instant,
        ) = Unit

        override suspend fun saveCategoryBudget(
            month: YearMonth,
            categoryId: Long,
            limitFen: Long?,
            now: Instant,
        ) = Unit

        override suspend fun addCategory(name: String): FinanceCategory =
            throw UnsupportedOperationException("not used")

        override suspend fun renameCategory(categoryId: Long, name: String) = Unit

        override suspend fun deleteCategory(categoryId: Long) = Unit
    }
}
