package com.zhixu.studyos.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 把旧 `study_tasks` 中仍能确定日期的任务复制成 V3.1 的极简待办。
 *
 * 只做单向复制，不修改或删除旧表。数据库升级与“恢复旧版备份”都会调用，
 * 保证换机恢复后旧任务仍能在新界面看到。
 */
object DailyLogBackfill {
    fun run(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO `daily_tasks` (
                `dateEpochDay`, `title`, `isCompleted`, `completedAtEpochMillis`,
                `sortOrder`, `createdAtEpochMillis`, `updatedAtEpochMillis`
            )
            SELECT
                COALESCE(
                    `dueDateEpochDay`,
                    (SELECT MIN(`dateEpochDay`) FROM `task_sessions` WHERE `taskId` = `study_tasks`.`id`)
                ),
                `title`,
                CASE WHEN `status` = 'COMPLETED' THEN 1 ELSE 0 END,
                `completedAtEpochMillis`,
                `sortOrder`,
                `createdAtEpochMillis`,
                `updatedAtEpochMillis`
            FROM `study_tasks`
            WHERE COALESCE(
                `dueDateEpochDay`,
                (SELECT MIN(`dateEpochDay`) FROM `task_sessions` WHERE `taskId` = `study_tasks`.`id`)
            ) IS NOT NULL
            """.trimIndent(),
        )
    }
}
