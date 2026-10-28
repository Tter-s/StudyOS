package com.zhixu.studyos.core.backup

import android.database.Cursor
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用 SQL 快照编解码：按表导出所有列为 JSON，恢复时按当前 Schema 求交集插入。
 *
 * 这样做的原因（V1.0 数据库可靠性）：
 * - 不需要为每个 Entity 手写序列化，新增列不会漏掉；
 * - 恢复时以「当前数据库的列」为准，旧备份缺少的新列使用数据库默认值；
 * - 备份版本高于当前应用时拒绝恢复，避免把未知结构写入旧 Schema；
 * - 插入/删除顺序按外键依赖排列，并在事务内用实际行数校验，任何异常整体回滚。
 *
 * 该编解码器只处理业务表；AI 表与系统表不在 [BackupTables.ordered] 中。
 */
class BackupCodec {

    data class TableValidation(
        val columns: List<String>,
        val requiredColumns: Set<String>,
        val rowCount: Int,
    )

    data class ValidationReport(
        val databaseVersion: Int,
        val tableValidations: Map<String, TableValidation>,
        val warnings: List<String>,
    ) {
        val counts: Map<String, Int> get() = tableValidations.mapValues { it.value.rowCount }
    }

    fun exportTables(db: SupportSQLiteDatabase): JSONObject {
        val tables = JSONObject()
        BackupTables.ordered.forEach { table ->
            tables.put(table, exportTable(db, table))
        }
        return JSONObject()
            .put("format", DATA_FORMAT)
            .put("databaseVersion", db.version)
            .put("tables", tables)
    }

    fun readDatabaseVersion(data: JSONObject): Int {
        requireFormat(data)
        return data.optInt("databaseVersion", -1)
    }

    /** 只读校验：表结构、必填列、版本兼容性与行数。 */
    fun validate(db: SupportSQLiteDatabase, data: JSONObject): ValidationReport {
        val format = data.optString("format")
        if (format != DATA_FORMAT) {
            throw BackupValidationException("备份数据格式不受支持（$format）")
        }
        val backupVersion = data.optInt("databaseVersion", -1)
        if (backupVersion < 1) throw BackupValidationException("备份缺少有效的数据库版本")
        if (backupVersion > db.version) {
            throw BackupValidationException(
                "备份由更新版本创建（数据库 v$backupVersion，当前 v${db.version}），请先升级应用",
            )
        }
        val tables = data.optJSONObject("tables")
            ?: throw BackupValidationException("备份缺少数据表内容")
        val warnings = mutableListOf<String>()
        if (backupVersion < db.version) {
            warnings += "备份由较旧版本创建（数据库 v$backupVersion，当前 v${db.version}），缺失字段将使用默认值"
        }
        val validations = mutableMapOf<String, TableValidation>()
        BackupTables.ordered.forEach { table ->
            val rows = tables.optJSONArray(table)
            if (rows == null) {
                if (backupVersion < db.version && BackupTables.mayBeAbsentInOlderBackups(table)) {
                    warnings += "旧版备份不包含表 `$table`，恢复时该表为空"
                    val columns = currentColumns(db, table)
                    validations[table] = TableValidation(
                        columns = columns,
                        requiredColumns = requiredColumns(db, table),
                        rowCount = 0,
                    )
                    return@forEach
                }
                throw BackupValidationException("备份缺少表 `$table`")
            }
            val columns = currentColumns(db, table)
            if (columns.isEmpty()) {
                throw BackupValidationException("当前数据库缺少表 `$table`，无法恢复")
            }
            val required = requiredColumns(db, table)
            val firstRow = rows.optJSONObject(0)
            if (firstRow != null) {
                val rowKeys = firstRow.keys().asSequence().toSet()
                val missingRequired = required - rowKeys
                if (missingRequired.isNotEmpty()) {
                    throw BackupValidationException(
                        "备份表 `$table` 缺少必填列：${missingRequired.joinToString("、")}",
                    )
                }
                val unknown = rowKeys - columns.toSet()
                if (unknown.isNotEmpty()) {
                    warnings += "表 `$table` 中未识别的列已忽略：${unknown.joinToString("、")}"
                }
            }
            validations[table] = TableValidation(
                columns = columns,
                requiredColumns = required,
                rowCount = rows.length(),
            )
        }
        return ValidationReport(
            databaseVersion = backupVersion,
            tableValidations = validations,
            warnings = warnings.distinct(),
        )
    }

    /**
     * 恢复：必须在调用方的 Room 事务内执行。先按逆序清空业务表，再按正序插入并核对行数。
     * 任一步失败抛异常，由 Room 事务回滚。
     */
    fun importTables(db: SupportSQLiteDatabase, data: JSONObject, report: ValidationReport) {
        val tables = data.optJSONObject("tables")
            ?: throw BackupValidationException("备份缺少数据表内容")
        BackupTables.ordered.asReversed().forEach { table ->
            db.execSQL("DELETE FROM `$table`")
        }
        BackupTables.ordered.forEach { table ->
            val rows = tables.optJSONArray(table) ?: JSONArray()
            val validation = report.tableValidations[table]
                ?: throw BackupValidationException("备份表 `$table` 未通过校验")
            val usableColumns = validation.columns
            val insertColumns = rows.optJSONObject(0)?.let { first ->
                val rowKeys = first.keys().asSequence().toSet()
                usableColumns.filter { it in rowKeys }
            } ?: emptyList()
            if (rows.length() > 0 && insertColumns.isEmpty()) {
                throw BackupValidationException("备份表 `$table` 没有可写入的列")
            }
            if (rows.length() > 0) {
                val placeholders = insertColumns.joinToString(",") { "?" }
                val columnList = insertColumns.joinToString(",") { "`$it`" }
                val statement = db.compileStatement(
                    "INSERT INTO `$table` ($columnList) VALUES ($placeholders)",
                )
                try {
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index)
                            ?: throw BackupValidationException("备份表 `$table` 第 ${index + 1} 行不是对象")
                        statement.clearBindings()
                        insertColumns.forEachIndexed { columnIndex, column ->
                            if (!row.has(column) || row.isNull(column)) {
                                statement.bindNull(columnIndex + 1)
                            } else {
                                bindValue(statement, columnIndex + 1, row.get(column))
                            }
                        }
                        statement.executeInsert()
                    }
                } finally {
                    statement.close()
                }
            }
            val actual = countRows(db, table)
            if (actual != rows.length()) {
                throw BackupValidationException(
                    "表 `$table` 恢复后行数不一致（期望 ${rows.length()}，实际 $actual）",
                )
            }
        }
    }

    fun countRows(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** 当前数据库各业务表行数，用于备份前展示与恢复后核对。 */
    fun currentCounts(db: SupportSQLiteDatabase): Map<String, Int> =
        BackupTables.ordered.associateWith { table -> countRows(db, table) }

    private fun exportTable(db: SupportSQLiteDatabase, table: String): JSONArray {
        val rows = JSONArray()
        db.query("SELECT * FROM `$table`").use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (index in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(index)
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB -> row.put(
                            name,
                            JSONObject().put(
                                BLOB_KEY,
                                Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP),
                            ),
                        )
                        else -> row.put(name, cursor.getString(index))
                    }
                }
                rows.put(row)
            }
        }
        return rows
    }

    private fun currentColumns(db: SupportSQLiteDatabase, table: String): List<String> =
        tableInfo(db, table).map { it.name }

    private fun requiredColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        tableInfo(db, table)
            .filter { info ->
                info.pk > 0 || (info.notNull && info.defaultValue == null)
            }
            .map { it.name }
            .toSet()

    private data class ColumnInfo(
        val name: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int,
    )

    private fun tableInfo(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            val defaultValueIndex = cursor.getColumnIndex("dflt_value")
            val pkIndex = cursor.getColumnIndex("pk")
            while (cursor.moveToNext()) {
                columns += ColumnInfo(
                    name = cursor.getString(nameIndex),
                    notNull = cursor.getInt(notNullIndex) != 0,
                    defaultValue = if (cursor.isNull(defaultValueIndex)) {
                        null
                    } else {
                        cursor.getString(defaultValueIndex)
                    },
                    pk = cursor.getInt(pkIndex),
                )
            }
        }
        return columns
    }

    private fun bindValue(statement: androidx.sqlite.db.SupportSQLiteStatement, index: Int, value: Any) {
        when (value) {
            is Int -> statement.bindLong(index, value.toLong())
            is Long -> statement.bindLong(index, value)
            is Double -> statement.bindDouble(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
            is String -> statement.bindString(index, value)
            is JSONObject -> {
                val encoded = value.optString(BLOB_KEY, "")
                statement.bindBlob(index, Base64.decode(encoded, Base64.NO_WRAP))
            }
            else -> throw BackupValidationException("备份包含不支持的数据类型：${value::class.java.simpleName}")
        }
    }

    private fun requireFormat(data: JSONObject) {
        if (data.optString("format") != DATA_FORMAT) {
            throw BackupValidationException("备份数据格式不受支持")
        }
    }

    companion object {
        const val DATA_FORMAT = "studyos-sql-v1"
        private const val BLOB_KEY = "\$blob"
    }
}
