package com.zhixu.studyos.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhixu.studyos.core.model.CategoryBudget
import com.zhixu.studyos.core.model.FinanceCategory
import com.zhixu.studyos.core.model.FinanceTransaction
import com.zhixu.studyos.core.model.FinanceTransactionType
import com.zhixu.studyos.core.model.MonthlyBudget
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Entity(
    tableName = "finance_categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class FinanceCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPreset: Boolean,
    val sortOrder: Int,
)

@Entity(
    tableName = "finance_transactions",
    foreignKeys = [
        ForeignKey(
            entity = FinanceCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["dateEpochDay"]),
        Index(value = ["categoryId"]),
        Index(value = ["type"]),
    ],
)
data class FinanceTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val type: String,
    val amountFen: Long,
    val categoryId: Long?,
    val note: String,
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
    /** 用户标记为正常的大额特殊开支；异常检测不会再次把它当作异常。 */
    @ColumnInfo(defaultValue = "0") val isExpectedExpense: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "finance_monthly_budgets")
data class FinanceMonthlyBudgetEntity(
    @PrimaryKey val monthKey: String,
    val totalBudgetFen: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "finance_category_budgets",
    primaryKeys = ["monthKey", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = FinanceCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class FinanceCategoryBudgetEntity(
    val monthKey: String,
    val categoryId: Long,
    val limitFen: Long,
    val updatedAtEpochMillis: Long,
)

fun FinanceCategoryEntity.toDomainModel() = FinanceCategory(
    id = id,
    name = name,
    isPreset = isPreset,
    sortOrder = sortOrder,
)

fun FinanceTransactionEntity.toDomainModel() = FinanceTransaction(
    id = id,
    date = LocalDate.ofEpochDay(dateEpochDay),
    type = FinanceTransactionType.valueOf(type),
    amountFen = amountFen,
    categoryId = categoryId,
    note = note,
    isExpectedExpense = isExpectedExpense,
    revision = revision,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

fun FinanceMonthlyBudgetEntity.toDomainModel() = MonthlyBudget(
    month = YearMonth.parse(monthKey),
    totalBudgetFen = totalBudgetFen,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

fun FinanceCategoryBudgetEntity.toDomainModel() = CategoryBudget(
    month = YearMonth.parse(monthKey),
    categoryId = categoryId,
    limitFen = limitFen,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)
