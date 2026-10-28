package com.zhixu.studyos.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * V1.0 → V2.0（数据库 v8 → v9）。
 *
 * 只新增表和带默认值的列，不删除、不重建任何 V1.0 业务表；
 * 任务新增全局 ID 并回填，旧任务全部保留。迁移在 Room 的单个事务中执行，
 * 任一步失败由 Room 回滚，数据库仍停留在 v8，不会留下半升级结构。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- V1.0 表扩展 ----
        db.execSQL("ALTER TABLE `study_tasks` ADD COLUMN `globalId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `study_tasks` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `study_tasks` ADD COLUMN `sourceType` TEXT NOT NULL DEFAULT 'USER'")
        db.execSQL("ALTER TABLE `study_tasks` ADD COLUMN `sourceId` TEXT")
        db.execSQL("UPDATE `study_tasks` SET `globalId` = 'task-' || `id` WHERE `globalId` = ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_globalId` ON `study_tasks` (`globalId`)")
        db.execSQL("ALTER TABLE `finance_transactions` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `finance_transactions` ADD COLUMN `isExpectedExpense` INTEGER NOT NULL DEFAULT 0")

        // ---- 任务历史 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `task_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `objectType` TEXT NOT NULL, `objectId` INTEGER NOT NULL, `eventType` TEXT NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `beforeJson` TEXT, `afterJson` TEXT, `source` TEXT NOT NULL, `reason` TEXT, `sessionId` INTEGER, `revision` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_objectType_objectId` ON `task_events` (`objectType`, `objectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_occurredAtEpochMillis` ON `task_events` (`occurredAtEpochMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_events_eventType` ON `task_events` (`eventType`)")

        // ---- 睡眠保护 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sleep_schedule` (`id` INTEGER NOT NULL, `weekdayBedMinute` INTEGER NOT NULL, `weekdayWakeMinute` INTEGER NOT NULL, `weekendBedMinute` INTEGER NOT NULL, `weekendWakeMinute` INTEGER NOT NULL, `earliestWakeMinute` INTEGER NOT NULL, `latestBedMinute` INTEGER NOT NULL, `preSleepBufferMinutes` INTEGER NOT NULL, `napEnabled` INTEGER NOT NULL, `napStartMinute` INTEGER NOT NULL, `napEndMinute` INTEGER NOT NULL, `examPeriodStartEpochDay` INTEGER, `examPeriodEndEpochDay` INTEGER, `examBedMinute` INTEGER, `examWakeMinute` INTEGER, `bedtimeModeEnabled` INTEGER NOT NULL DEFAULT 0, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sleep_date_overrides` (`dateEpochDay` INTEGER NOT NULL, `bedMinute` INTEGER NOT NULL, `wakeMinute` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`dateEpochDay`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sleep_records` (`dateEpochDay` INTEGER NOT NULL, `bedTimeEpochMillis` INTEGER, `wakeTimeEpochMillis` INTEGER, `quality` INTEGER, `nextDayEnergy` INTEGER, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`dateEpochDay`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sleep_change_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `changedAtEpochMillis` INTEGER NOT NULL, `beforeJson` TEXT NOT NULL, `afterJson` TEXT NOT NULL, `reason` TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_change_history_changedAtEpochMillis` ON `sleep_change_history` (`changedAtEpochMillis`)")

        // ---- 英语词汇 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `vocabulary_words` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalizedWord` TEXT NOT NULL, `word` TEXT NOT NULL, `phonetic` TEXT NOT NULL, `partOfSpeech` TEXT NOT NULL, `meaning` TEXT NOT NULL, `example` TEXT NOT NULL, `listName` TEXT NOT NULL, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `familiarity` INTEGER NOT NULL, `repetition` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `easeFactor` REAL NOT NULL, `dueDateEpochDay` INTEGER NOT NULL, `lastReviewedAtEpochMillis` INTEGER, `reviewCount` INTEGER NOT NULL, `lapseCount` INTEGER NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `revision` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_words_normalizedWord` ON `vocabulary_words` (`normalizedWord`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_words_dueDateEpochDay` ON `vocabulary_words` (`dueDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_words_status` ON `vocabulary_words` (`status`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `vocabulary_reviews` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `wordId` INTEGER NOT NULL, `reviewedAtEpochMillis` INTEGER NOT NULL, `rating` TEXT NOT NULL, `recalled` INTEGER NOT NULL, `previousIntervalDays` INTEGER NOT NULL, `newIntervalDays` INTEGER NOT NULL, `nextDueEpochDay` INTEGER NOT NULL, `responseTimeMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`wordId`) REFERENCES `vocabulary_words`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_reviews_wordId` ON `vocabulary_reviews` (`wordId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocabulary_reviews_reviewedAtEpochMillis` ON `vocabulary_reviews` (`reviewedAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `vocabulary_settings` (`id` INTEGER NOT NULL, `dailyNewLimit` INTEGER NOT NULL, `dailyReviewLimit` INTEGER NOT NULL, `dailyTimeBudgetMinutes` INTEGER NOT NULL, `examDateEpochDay` INTEGER, `taskGenerationEnabled` INTEGER NOT NULL, `lastTaskGeneratedEpochDay` INTEGER, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )

        // ---- 数学知识图谱 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `knowledge_nodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subject` TEXT NOT NULL, `chapter` TEXT NOT NULL, `name` TEXT NOT NULL, `definition` TEXT NOT NULL, `theorems` TEXT NOT NULL, `note` TEXT NOT NULL, `mastery` INTEGER NOT NULL, `masterySource` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `revision` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_nodes_subject_name` ON `knowledge_nodes` (`subject`, `name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_subject_chapter` ON `knowledge_nodes` (`subject`, `chapter`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `knowledge_dependencies` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromNodeId` INTEGER NOT NULL, `toNodeId` INTEGER NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`fromNodeId`) REFERENCES `knowledge_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`toNodeId`) REFERENCES `knowledge_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_dependencies_fromNodeId_toNodeId` ON `knowledge_dependencies` (`fromNodeId`, `toNodeId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_dependencies_toNodeId` ON `knowledge_dependencies` (`toNodeId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `knowledge_problem_links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nodeId` INTEGER NOT NULL, `problemId` INTEGER NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`nodeId`) REFERENCES `knowledge_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_problem_links_nodeId_problemId` ON `knowledge_problem_links` (`nodeId`, `problemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_problem_links_problemId` ON `knowledge_problem_links` (`problemId`)")

        // ---- 考试与长期目标 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exams` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `subject` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER, `durationMinutes` INTEGER, `scope` TEXT NOT NULL, `targetScore` TEXT NOT NULL, `materials` TEXT NOT NULL, `reminderEnabled` INTEGER NOT NULL, `reminderDaysBefore` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `revision` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exams_dateEpochDay` ON `exams` (`dateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exams_subject` ON `exams` (`subject`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `long_term_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `description` TEXT NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `targetDateEpochDay` INTEGER, `status` TEXT NOT NULL, `progressSource` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `revision` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_goals_status` ON `long_term_goals` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_goals_targetDateEpochDay` ON `long_term_goals` (`targetDateEpochDay`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `goal_milestones` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalId` INTEGER NOT NULL, `title` TEXT NOT NULL, `dueDateEpochDay` INTEGER, `weight` INTEGER NOT NULL, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `sortOrder` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`goalId`) REFERENCES `long_term_goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_milestones_goalId` ON `goal_milestones` (`goalId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_goal_milestones_dueDateEpochDay` ON `goal_milestones` (`dueDateEpochDay`)")

        // ---- 习题尝试、提示与能力 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exercise_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemType` TEXT NOT NULL, `problemId` INTEGER NOT NULL, `attemptIndex` INTEGER NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, `submittedAtEpochMillis` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL, `highestHintLevel` INTEGER NOT NULL, `independent` INTEGER NOT NULL, `correct` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_attempts_problemType_problemId` ON `exercise_attempts` (`problemType`, `problemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_attempts_submittedAtEpochMillis` ON `exercise_attempts` (`submittedAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exercise_hint_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemType` TEXT NOT NULL, `problemId` INTEGER NOT NULL, `level` INTEGER NOT NULL, `requestedAtEpochMillis` INTEGER NOT NULL, `content` TEXT NOT NULL, `usedCache` INTEGER NOT NULL, `model` TEXT NOT NULL, `costMicros` INTEGER NOT NULL, `createdProof` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_hint_records_problemType_problemId` ON `exercise_hint_records` (`problemType`, `problemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_hint_records_requestedAtEpochMillis` ON `exercise_hint_records` (`requestedAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exercise_profiles` (`problemType` TEXT NOT NULL, `problemId` INTEGER NOT NULL, `personalDifficulty` REAL, `difficultySampleCount` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `independentCount` INTEGER NOT NULL, `hintLevelSum` INTEGER NOT NULL, `avgActualMinutes` REAL NOT NULL, `estimatedMinutes` INTEGER, `mastery` REAL NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`problemType`, `problemId`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_profiles_updatedAtEpochMillis` ON `exercise_profiles` (`updatedAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exercise_saved_proofs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemType` TEXT NOT NULL, `problemId` INTEGER NOT NULL, `content` TEXT NOT NULL, `source` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_saved_proofs_problemType_problemId` ON `exercise_saved_proofs` (`problemType`, `problemId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `exercise_error_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemType` TEXT NOT NULL, `problemId` INTEGER NOT NULL, `hintLevel` INTEGER, `reason` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_error_reports_problemType_problemId` ON `exercise_error_reports` (`problemType`, `problemId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_error_reports_createdAtEpochMillis` ON `exercise_error_reports` (`createdAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ability_estimates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scope` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `ability` REAL NOT NULL, `sampleCount` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )

        // ---- AI 结果与周报 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_analysis_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskType` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `model` TEXT NOT NULL, `promptVersion` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `costMicros` INTEGER NOT NULL, `usedCache` INTEGER NOT NULL, `userAccepted` INTEGER, `note` TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_analysis_records_taskType` ON `ai_analysis_records` (`taskType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_analysis_records_createdAtEpochMillis` ON `ai_analysis_records` (`createdAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `weekly_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartEpochDay` INTEGER NOT NULL, `generatedAtEpochMillis` INTEGER NOT NULL, `reportJson` TEXT NOT NULL, `aiSummary` TEXT, `aiGeneratedAtEpochMillis` INTEGER, `aiModel` TEXT)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_weekly_reports_weekStartEpochDay` ON `weekly_reports` (`weekStartEpochDay`)")

        // ---- 本地同步 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_operations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `objectType` TEXT NOT NULL, `objectId` INTEGER NOT NULL, `operation` TEXT NOT NULL, `revision` INTEGER NOT NULL, `payloadHash` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_operations_objectType_objectId` ON `sync_operations` (`objectType`, `objectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_operations_occurredAtEpochMillis` ON `sync_operations` (`occurredAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_baselines` (`objectType` TEXT NOT NULL, `objectId` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `payloadHash` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`objectType`, `objectId`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_tombstones` (`objectType` TEXT NOT NULL, `objectId` INTEGER NOT NULL, `deletedAtEpochMillis` INTEGER NOT NULL, `originDeviceId` TEXT NOT NULL, PRIMARY KEY(`objectType`, `objectId`))""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tombstones_deletedAtEpochMillis` ON `sync_tombstones` (`deletedAtEpochMillis`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `lastSyncAtEpochMillis` INTEGER, `lastConflictCount` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )

        // ---- 首页卡片偏好 ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `home_card_preferences` (`cardId` TEXT NOT NULL, `visible` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`cardId`))""",
        )

        SyncTriggers.install(db)
    }
}
