package com.zhixu.studyos.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * V3.1：新增极简的每日待办、每日总结与娱乐时间三张表。
 *
 * 旧表（学习任务、科目、计时、题库、报告等）全部保留，仅停止在界面中使用；
 * 迁移会把旧任务中仍能确定日期的一条记录复制为 V3.1 待办，作为可浏览的历史，
 * 不会删除或改写任何旧数据。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_tasks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`dateEpochDay` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`isCompleted` INTEGER NOT NULL, " +
                "`completedAtEpochMillis` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, " +
                "`updatedAtEpochMillis` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_daily_tasks_dateEpochDay` " +
                "ON `daily_tasks` (`dateEpochDay`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_summaries` (" +
                "`dateEpochDay` INTEGER NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, " +
                "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`dateEpochDay`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `entertainment_records` (" +
                "`dateEpochDay` INTEGER NOT NULL, " +
                "`minutes` INTEGER NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, " +
                "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`dateEpochDay`))",
        )
        DailyLogBackfill.run(db)
    }
}
