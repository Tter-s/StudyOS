package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    @Query("SELECT * FROM finance_categories ORDER BY sortOrder, id")
    fun observeCategories(): Flow<List<FinanceCategoryEntity>>

    @Query("SELECT * FROM finance_categories ORDER BY sortOrder, id")
    suspend fun getCategories(): List<FinanceCategoryEntity>

    @Query("SELECT * FROM finance_categories WHERE id = :id LIMIT 1")
    suspend fun getCategory(id: Long): FinanceCategoryEntity?

    @Query("SELECT * FROM finance_categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): FinanceCategoryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM finance_categories")
    suspend fun maxCategorySortOrder(): Int

    @Insert
    suspend fun insertCategory(category: FinanceCategoryEntity): Long

    @Update
    suspend fun updateCategory(category: FinanceCategoryEntity)

    @Query("DELETE FROM finance_categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("SELECT COUNT(*) FROM finance_transactions WHERE categoryId = :categoryId")
    suspend fun countTransactionsForCategory(categoryId: Long): Int

    @Query("SELECT * FROM finance_transactions ORDER BY dateEpochDay DESC, id DESC")
    fun observeTransactions(): Flow<List<FinanceTransactionEntity>>

    @Query("SELECT * FROM finance_transactions ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getTransactions(): List<FinanceTransactionEntity>

    @Query(
        "SELECT * FROM finance_transactions WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY dateEpochDay DESC, id DESC",
    )
    suspend fun getTransactionsBetween(startEpochDay: Long, endEpochDay: Long): List<FinanceTransactionEntity>

    @Query("SELECT * FROM finance_transactions WHERE id = :id LIMIT 1")
    suspend fun getTransaction(id: Long): FinanceTransactionEntity?

    @Insert
    suspend fun insertTransaction(transaction: FinanceTransactionEntity): Long

    @Update
    suspend fun updateTransaction(transaction: FinanceTransactionEntity)

    @Query("DELETE FROM finance_transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("UPDATE finance_transactions SET isExpectedExpense = :expected, revision = revision + 1, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun setExpectedExpense(id: Long, expected: Boolean, updatedAt: Long): Int

    @Query("SELECT * FROM finance_monthly_budgets ORDER BY monthKey")
    fun observeMonthlyBudgets(): Flow<List<FinanceMonthlyBudgetEntity>>

    @Query("SELECT * FROM finance_monthly_budgets ORDER BY monthKey")
    suspend fun getMonthlyBudgets(): List<FinanceMonthlyBudgetEntity>

    @Query("SELECT * FROM finance_monthly_budgets WHERE monthKey = :monthKey LIMIT 1")
    suspend fun getMonthlyBudget(monthKey: String): FinanceMonthlyBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonthlyBudget(budget: FinanceMonthlyBudgetEntity)

    @Query("DELETE FROM finance_monthly_budgets WHERE monthKey = :monthKey")
    suspend fun deleteMonthlyBudget(monthKey: String)

    @Query("SELECT * FROM finance_category_budgets ORDER BY monthKey, categoryId")
    fun observeCategoryBudgets(): Flow<List<FinanceCategoryBudgetEntity>>

    @Query("SELECT * FROM finance_category_budgets ORDER BY monthKey, categoryId")
    suspend fun getCategoryBudgets(): List<FinanceCategoryBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryBudget(budget: FinanceCategoryBudgetEntity)

    @Query("DELETE FROM finance_category_budgets WHERE monthKey = :monthKey AND categoryId = :categoryId")
    suspend fun deleteCategoryBudget(monthKey: String, categoryId: Long)
}
