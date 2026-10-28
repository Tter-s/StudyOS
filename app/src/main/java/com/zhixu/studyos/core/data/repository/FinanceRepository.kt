package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.FinanceCategoryBudgetEntity
import com.zhixu.studyos.core.data.local.FinanceCategoryEntity
import com.zhixu.studyos.core.data.local.FinanceDao
import com.zhixu.studyos.core.data.local.FinanceMonthlyBudgetEntity
import com.zhixu.studyos.core.data.local.FinanceTransactionEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceSnapshot
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface FinanceRepository {
    fun observeSnapshot(): Flow<FinanceSnapshot>

    suspend fun getSnapshot(): FinanceSnapshot

    suspend fun saveTransaction(transaction: FinanceTransaction, now: Instant): FinanceTransaction

    suspend fun deleteTransaction(transactionId: Long)

    /** 用户把异常检测提示的某笔大额支出标记为正常特殊开支；不会再反复提示。 */
    suspend fun setExpectedExpense(transactionId: Long, expected: Boolean, now: Instant) {
        // 测试用替身可以实现为空；生产实现见 OfflineFinanceRepository。
    }

    suspend fun saveMonthlyBudget(month: YearMonth, totalBudgetFen: Long?, now: Instant)

    suspend fun saveCategoryBudget(month: YearMonth, categoryId: Long, limitFen: Long?, now: Instant)

    suspend fun addCategory(name: String): FinanceCategory

    suspend fun renameCategory(categoryId: Long, name: String)

    suspend fun deleteCategory(categoryId: Long)
}

class OfflineFinanceRepository(
    private val database: StudyOSDatabase,
    private val financeDao: FinanceDao,
) : FinanceRepository {
    override fun observeSnapshot(): Flow<FinanceSnapshot> = combine(
        financeDao.observeCategories(),
        financeDao.observeTransactions(),
        financeDao.observeMonthlyBudgets(),
        financeDao.observeCategoryBudgets(),
    ) { categories, transactions, monthlyBudgets, categoryBudgets ->
        FinanceSnapshot(
            categories = categories.map { it.toDomainModel() },
            transactions = transactions.map { it.toDomainModel() },
            monthlyBudgets = monthlyBudgets.map { it.toDomainModel() },
            categoryBudgets = categoryBudgets.map { it.toDomainModel() },
        )
    }

    override suspend fun getSnapshot(): FinanceSnapshot = FinanceSnapshot(
        categories = financeDao.getCategories().map { it.toDomainModel() },
        transactions = financeDao.getTransactions().map { it.toDomainModel() },
        monthlyBudgets = financeDao.getMonthlyBudgets().map { it.toDomainModel() },
        categoryBudgets = financeDao.getCategoryBudgets().map { it.toDomainModel() },
    )

    override suspend fun saveTransaction(
        transaction: FinanceTransaction,
        now: Instant,
    ): FinanceTransaction {
        require(transaction.amountFen in 1L..Money.MAX_AMOUNT_FEN) {
            "金额必须大于 0，且不超过 ${Money.formatFen(Money.MAX_AMOUNT_FEN)}；退款请选择“退款”并填写正数"
        }
        require(transaction.date.year in 1900..2999) { "记账日期超出支持范围" }
        val note = transaction.note.trim()
        require(note.length <= MAX_NOTE_LENGTH) { "备注不能超过 $MAX_NOTE_LENGTH 个字符" }
        val categoryId = when (transaction.type) {
            FinanceTransactionType.INCOME -> null
            FinanceTransactionType.EXPENSE, FinanceTransactionType.REFUND -> {
                val id = transaction.categoryId ?: throw IllegalArgumentException("请选择分类")
                require(financeDao.getCategory(id) != null) { "所选分类不存在，可能已被删除" }
                id
            }
        }
        val existing = if (transaction.id == 0L) {
            null
        } else {
            financeDao.getTransaction(transaction.id)
                ?: throw IllegalArgumentException("记账记录不存在，可能已被删除")
        }
        val nowMillis = now.toEpochMilli()
        val entity = FinanceTransactionEntity(
            id = transaction.id,
            dateEpochDay = transaction.date.toEpochDay(),
            type = transaction.type.name,
            amountFen = transaction.amountFen,
            categoryId = categoryId,
            note = note,
            revision = (existing?.revision ?: 0) + 1,
            isExpectedExpense = existing?.isExpectedExpense ?: false,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: nowMillis,
            updatedAtEpochMillis = nowMillis,
        )
        val savedId = if (existing == null) {
            financeDao.insertTransaction(entity)
        } else {
            financeDao.updateTransaction(entity)
            transaction.id
        }
        return entity.copy(id = savedId).toDomainModel()
    }

    override suspend fun deleteTransaction(transactionId: Long) {
        financeDao.deleteTransaction(transactionId)
    }

    override suspend fun setExpectedExpense(transactionId: Long, expected: Boolean, now: Instant) {
        val updated = financeDao.setExpectedExpense(transactionId, expected, now.toEpochMilli())
        require(updated == 1) { "记账记录不存在，可能已被删除" }
    }

    override suspend fun saveMonthlyBudget(
        month: YearMonth,
        totalBudgetFen: Long?,
        now: Instant,
    ) {
        if (totalBudgetFen == null) {
            financeDao.deleteMonthlyBudget(month.toString())
            return
        }
        require(totalBudgetFen in 1L..Money.MAX_AMOUNT_FEN) {
            "月度预算必须大于 0，且不超过 ${Money.formatFen(Money.MAX_AMOUNT_FEN)}"
        }
        financeDao.upsertMonthlyBudget(
            FinanceMonthlyBudgetEntity(
                monthKey = month.toString(),
                totalBudgetFen = totalBudgetFen,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun saveCategoryBudget(
        month: YearMonth,
        categoryId: Long,
        limitFen: Long?,
        now: Instant,
    ) {
        require(financeDao.getCategory(categoryId) != null) { "分类不存在，可能已被删除" }
        if (limitFen == null) {
            financeDao.deleteCategoryBudget(month.toString(), categoryId)
            return
        }
        require(limitFen in 1L..Money.MAX_AMOUNT_FEN) {
            "分类预算必须大于 0，且不超过 ${Money.formatFen(Money.MAX_AMOUNT_FEN)}"
        }
        financeDao.upsertCategoryBudget(
            FinanceCategoryBudgetEntity(
                monthKey = month.toString(),
                categoryId = categoryId,
                limitFen = limitFen,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun addCategory(name: String): FinanceCategory {
        val trimmed = validCategoryName(name)
        val categories = financeDao.getCategories()
        require(categories.none { it.name.equals(trimmed, ignoreCase = true) }) { "该分类已存在" }
        val sortOrder = financeDao.maxCategorySortOrder() + 1
        val id = financeDao.insertCategory(
            FinanceCategoryEntity(
                name = trimmed,
                isPreset = false,
                sortOrder = sortOrder,
            ),
        )
        return FinanceCategory(id = id, name = trimmed, isPreset = false, sortOrder = sortOrder)
    }

    override suspend fun renameCategory(categoryId: Long, name: String) {
        val entity = financeDao.getCategory(categoryId)
            ?: throw IllegalArgumentException("分类不存在，可能已被删除")
        require(!entity.isPreset) { "预设分类不能重命名" }
        val trimmed = validCategoryName(name)
        val categories = financeDao.getCategories()
        require(
            categories.none { it.id != categoryId && it.name.equals(trimmed, ignoreCase = true) },
        ) { "该分类已存在" }
        financeDao.updateCategory(entity.copy(name = trimmed))
    }

    override suspend fun deleteCategory(categoryId: Long) {
        database.withTransaction {
            val entity = financeDao.getCategory(categoryId)
                ?: throw IllegalArgumentException("分类不存在，可能已被删除")
            require(!entity.isPreset) { "预设分类不能删除" }
            require(financeDao.countTransactionsForCategory(categoryId) == 0) {
                "该分类已有记账记录，无法删除；可以继续使用或新建分类"
            }
            financeDao.deleteCategory(categoryId)
        }
    }

    private fun validCategoryName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入分类名称" }
        require(trimmed.length <= MAX_CATEGORY_NAME_LENGTH) {
            "分类名称不能超过 $MAX_CATEGORY_NAME_LENGTH 个字符"
        }
        return trimmed
    }

    private companion object {
        const val MAX_CATEGORY_NAME_LENGTH = 12
        const val MAX_NOTE_LENGTH = 200
    }
}
