package com.zhixu.studyos.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地修改追踪与删除墓碑。
 *
 * 使用 SQLite 触发器而不是在每个 Repository 手写记录：
 * - 任何直接写库的代码路径都会留下同步队列记录；
 * - 删除墓碑独立于业务表，删除任务不会级联删除同步历史；
 * - 触发器只记录对象类型、ID、版本与时间，不复制敏感内容；
 * - 从备份恢复时由恢复逻辑清空同步队列与墓碑，避免把恢复数据当作本地新修改。
 *
 * 注意：云端同步未配置；这些记录用于真实的本地/手动加密同步包交换。
 */
object SyncTriggers {
    private data class TrackedTable(
        val table: String,
        val objectType: String,
    )

    private val tables = listOf(
        TrackedTable("study_tasks", "TASK"),
        TrackedTable("finance_transactions", "FINANCE"),
        TrackedTable("vocabulary_words", "VOCABULARY"),
        TrackedTable("knowledge_nodes", "KNOWLEDGE"),
        TrackedTable("exams", "EXAM"),
        TrackedTable("long_term_goals", "GOAL"),
    )

    fun install(db: SupportSQLiteDatabase) {
        tables.forEach { tracked ->
            val prefix = "sync_${tracked.table}"
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trigger_${prefix}_insert`
                AFTER INSERT ON `${tracked.table}`
                BEGIN
                    INSERT INTO `sync_operations`
                        (`objectType`, `objectId`, `operation`, `revision`, `payloadHash`, `payloadJson`, `occurredAtEpochMillis`, `deviceId`, `syncedAtEpochMillis`)
                    VALUES
                        ('${tracked.objectType}', NEW.`id`, 'INSERT', COALESCE(NEW.`revision`, 0), '', '{}',
                         CAST(strftime('%s','now') AS INTEGER) * 1000, 'local', NULL);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trigger_${prefix}_update`
                AFTER UPDATE ON `${tracked.table}`
                BEGIN
                    INSERT INTO `sync_operations`
                        (`objectType`, `objectId`, `operation`, `revision`, `payloadHash`, `payloadJson`, `occurredAtEpochMillis`, `deviceId`, `syncedAtEpochMillis`)
                    VALUES
                        ('${tracked.objectType}', NEW.`id`, 'UPDATE', COALESCE(NEW.`revision`, 0), '', '{}',
                         CAST(strftime('%s','now') AS INTEGER) * 1000, 'local', NULL);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trigger_${prefix}_delete`
                AFTER DELETE ON `${tracked.table}`
                BEGIN
                    INSERT OR REPLACE INTO `sync_tombstones`
                        (`objectType`, `objectId`, `deletedAtEpochMillis`, `originDeviceId`)
                    VALUES
                        ('${tracked.objectType}', OLD.`id`,
                         CAST(strftime('%s','now') AS INTEGER) * 1000, 'local');
                    INSERT INTO `sync_operations`
                        (`objectType`, `objectId`, `operation`, `revision`, `payloadHash`, `payloadJson`, `occurredAtEpochMillis`, `deviceId`, `syncedAtEpochMillis`)
                    VALUES
                        ('${tracked.objectType}', OLD.`id`, 'DELETE', 0, '', '{}',
                         CAST(strftime('%s','now') AS INTEGER) * 1000, 'local', NULL);
                END
                """.trimIndent(),
            )
        }
    }
}
