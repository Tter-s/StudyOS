package com.zhixu.studyos.core.finance

import com.zhixu.studyos.core.model.FinanceSnapshot
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 财务模块的逻辑备份。使用可读 JSON 保存全部分类、记账记录、月度预算和分类预算，
 * 金额单位为分。格式带版本号与数据库版本，便于未来实现恢复和迁移。
 *
 * 当前版本只提供导出，尚未实现从备份恢复。备份范围仅限财务模块，不包含课表、任务与学习数据。
 */
object FinanceBackup {
    const val FORMAT = "zhixu-studyos-finance-backup"
    const val FORMAT_VERSION = 1
    const val MIME_TYPE = "application/json"

    fun encode(
        snapshot: FinanceSnapshot,
        exportedAt: Instant,
        zoneId: ZoneId,
        appVersion: String,
        databaseVersion: Int,
    ): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("databaseVersion", databaseVersion)
            .put("appVersion", appVersion)
            .put("currency", "CNY")
            .put("amountUnit", "fen")
            .put("exportedAt", exportedAt.toString())
            .put("timeZone", zoneId.id)
            .put(
                "counts",
                JSONObject()
                    .put("categories", snapshot.categories.size)
                    .put("transactions", snapshot.transactions.size)
                    .put("monthlyBudgets", snapshot.monthlyBudgets.size)
                    .put("categoryBudgets", snapshot.categoryBudgets.size),
            )

        val categories = JSONArray()
        snapshot.categories.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { category ->
            categories.put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
                    .put("isPreset", category.isPreset)
                    .put("sortOrder", category.sortOrder),
            )
        }
        root.put("categories", categories)

        val transactions = JSONArray()
        snapshot.transactions.sortedWith(compareBy({ it.date }, { it.id })).forEach { transaction ->
            transactions.put(
                JSONObject()
                    .put("id", transaction.id)
                    .put("date", transaction.date.toString())
                    .put("type", transaction.type.name)
                    .put("amountFen", transaction.amountFen)
                    .put("categoryId", transaction.categoryId ?: JSONObject.NULL)
                    .put("note", transaction.note)
                    .put("createdAtEpochMillis", transaction.createdAt?.toEpochMilli() ?: JSONObject.NULL)
                    .put("updatedAtEpochMillis", transaction.updatedAt?.toEpochMilli() ?: JSONObject.NULL),
            )
        }
        root.put("transactions", transactions)

        val monthlyBudgets = JSONArray()
        snapshot.monthlyBudgets.sortedBy { it.month }.forEach { budget ->
            monthlyBudgets.put(
                JSONObject()
                    .put("month", budget.month.toString())
                    .put("totalBudgetFen", budget.totalBudgetFen)
                    .put("updatedAtEpochMillis", budget.updatedAt.toEpochMilli()),
            )
        }
        root.put("monthlyBudgets", monthlyBudgets)

        val categoryBudgets = JSONArray()
        snapshot.categoryBudgets
            .sortedWith(compareBy({ it.month }, { it.categoryId }))
            .forEach { budget ->
                categoryBudgets.put(
                    JSONObject()
                        .put("month", budget.month.toString())
                        .put("categoryId", budget.categoryId)
                        .put("limitFen", budget.limitFen)
                        .put("updatedAtEpochMillis", budget.updatedAt.toEpochMilli()),
                )
            }
        root.put("categoryBudgets", categoryBudgets)

        return root.toString(2)
    }
}
