package com.zhixu.studyos.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `semester_settings` (`id` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT, `classroom` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `startWeek` INTEGER NOT NULL, `endWeek` INTEGER NOT NULL, `weekParity` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_dayOfWeek` ON `courses` (`dayOfWeek`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `course_exceptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `courseId` INTEGER NOT NULL, `originalDateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `targetDateEpochDay` INTEGER, `targetStartMinute` INTEGER, `targetEndMinuteExclusive` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_course_exceptions_courseId_originalDateEpochDay` ON `course_exceptions` (`courseId`, `originalDateEpochDay`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_course_exceptions_originalDateEpochDay` ON `course_exceptions` (`originalDateEpochDay`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `one_off_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `location` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_one_off_events_dateEpochDay` ON `one_off_events` (`dateEpochDay`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `routine_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `repeatType` TEXT NOT NULL, `dayOfWeek` INTEGER, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isPreset` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subjects_name` ON `subjects` (`name`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `study_timing_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `taskId` INTEGER, `dateEpochDay` INTEGER NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, `plannedDurationMinutes` INTEGER, `accumulatedMillis` INTEGER NOT NULL, `runningSinceEpochMillis` INTEGER, `interruptionCount` INTEGER NOT NULL, `lastInterruptionReason` TEXT, `startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER, `correctedAtEpochMillis` INTEGER, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`taskId`) REFERENCES `study_tasks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_subjectId` ON `study_timing_sessions` (`subjectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_taskId` ON `study_timing_sessions` (`taskId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_status` ON `study_timing_sessions` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_dateEpochDay` ON `study_timing_sessions` (`dateEpochDay`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `textbook` TEXT NOT NULL, `chapter` TEXT NOT NULL, `problemNumber` TEXT NOT NULL, `knowledgePoint` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `independentThinkingMinutes` INTEGER NOT NULL, `usedAiHint` INTEGER NOT NULL, `blockerReason` TEXT, `isMistake` INTEGER NOT NULL, `isDifficult` INTEGER NOT NULL, `independentCompleted` INTEGER, `retryCount` INTEGER NOT NULL, `secondAttemptIndependent` INTEGER, `completedAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problems_subjectId` ON `problems` (`subjectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problems_status` ON `problems` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problems_isDifficult` ON `problems` (`isDifficult`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problems_isMistake` ON `problems` (`isMistake`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `self_control_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `procrastinationReason` TEXT, `voluntaryInterruption` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_self_control_entries_dateEpochDay` ON `self_control_entries` (`dateEpochDay`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `learning_goals` (`id` INTEGER NOT NULL, `dailyStudyMinutes` INTEGER, `weeklyStudyMinutes` INTEGER, `dailyTaskTarget` INTEGER, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        com.zhixu.studyos.core.model.PRESET_SUBJECTS.forEach { subject ->
            db.execSQL(
                "INSERT OR IGNORE INTO `subjects` (`id`, `name`, `isPreset`) VALUES (${subject.id}, '${subject.name}', 1)",
            )
        }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `study_tasks` RENAME TO `study_tasks_legacy`")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `study_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `subject` TEXT NOT NULL, `priority` TEXT NOT NULL, `estimatedMinutes` INTEGER NOT NULL, `dueDateEpochDay` INTEGER, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `seriesId` TEXT, `recurrenceType` TEXT NOT NULL, `recurrenceInterval` INTEGER NOT NULL, `recurrenceUntilEpochDay` INTEGER, `sortOrder` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL(
            """INSERT INTO `study_tasks` (`id`, `title`, `description`, `subject`, `priority`, `estimatedMinutes`, `dueDateEpochDay`, `status`, `completedAtEpochMillis`, `seriesId`, `recurrenceType`, `recurrenceInterval`, `recurrenceUntilEpochDay`, `sortOrder`, `createdAtEpochMillis`, `updatedAtEpochMillis`)
               SELECT `id`, `title`, '', '', 'MEDIUM', CASE WHEN `estimatedMinutes` > 0 THEN `estimatedMinutes` ELSE 60 END, NULL,
                      CASE `isCompleted` WHEN 1 THEN 'COMPLETED' ELSE 'SCHEDULED' END,
                      CASE `isCompleted` WHEN 1 THEN `updatedAtEpochMillis` ELSE NULL END,
                      NULL, 'NONE', 1, NULL, `id`, `createdAtEpochMillis`, `updatedAtEpochMillis`
               FROM `study_tasks_legacy`""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `task_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`taskId`) REFERENCES `study_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL(
            """INSERT INTO `task_sessions` (`taskId`, `dateEpochDay`, `startMinute`, `endMinuteExclusive`, `createdAtEpochMillis`, `updatedAtEpochMillis`)
               SELECT `id`, `dateEpochDay`, 480,
                      CASE WHEN `estimatedMinutes` < 1 THEN 540 ELSE MIN(480 + `estimatedMinutes`, 1439) END,
                      `createdAtEpochMillis`, `updatedAtEpochMillis`
               FROM `study_tasks_legacy`""",
        )
        db.execSQL("DROP TABLE `study_tasks_legacy`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_status` ON `study_tasks` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_seriesId` ON `study_tasks` (`seriesId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_dueDateEpochDay` ON `study_tasks` (`dueDateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_sessions_taskId` ON `task_sessions` (`taskId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_sessions_dateEpochDay` ON `task_sessions` (`dateEpochDay`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `finance_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isPreset` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_categories_name` ON `finance_categories` (`name`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `finance_transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `amountFen` INTEGER NOT NULL, `categoryId` INTEGER, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `finance_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_transactions_dateEpochDay` ON `finance_transactions` (`dateEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_transactions_categoryId` ON `finance_transactions` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_transactions_type` ON `finance_transactions` (`type`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `finance_monthly_budgets` (`monthKey` TEXT NOT NULL, `totalBudgetFen` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`monthKey`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `finance_category_budgets` (`monthKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `limitFen` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`monthKey`, `categoryId`), FOREIGN KEY(`categoryId`) REFERENCES `finance_categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_category_budgets_categoryId` ON `finance_category_budgets` (`categoryId`)")
        com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES.forEach { category ->
            db.execSQL(
                "INSERT OR IGNORE INTO `finance_categories` (`id`, `name`, `isPreset`, `sortOrder`) " +
                    "VALUES (${category.id}, '${category.name}', 1, ${category.sortOrder})",
            )
        }
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `task_sessions` ADD COLUMN `isLocked` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `scheduling_settings` (`id` INTEGER NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `bufferMinutes` INTEGER NOT NULL, `studyStartMinute` INTEGER NOT NULL, `studyEndMinuteExclusive` INTEGER NOT NULL, `planReminderEnabled` INTEGER NOT NULL, `planReminderHour` INTEGER NOT NULL, `planReminderMinute` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `scheduling_subject_preferences` (`subjectName` TEXT NOT NULL, `level` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`subjectName`))""",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `displayName` TEXT NOT NULL, `type` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `originalName` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `contentSha256` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_document_pages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `text` TEXT NOT NULL, `textSource` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `problem_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_document_pages_documentId_pageIndex` ON `problem_document_pages` (`documentId`, `pageIndex`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_import_batches` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, `status` TEXT NOT NULL, `totalPages` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `problem_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problem_import_batches_documentId` ON `problem_import_batches` (`documentId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_import_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `batchId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `draftJson` TEXT, FOREIGN KEY(`batchId`) REFERENCES `problem_import_batches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_import_items_batchId_pageIndex` ON `problem_import_items` (`batchId`, `pageIndex`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_bank_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `courseId` INTEGER, `courseName` TEXT NOT NULL, `textbook` TEXT NOT NULL, `chapter` TEXT NOT NULL, `problemNumber` TEXT NOT NULL, `statement` TEXT NOT NULL, `estimatedDifficulty` INTEGER, `difficultySource` TEXT NOT NULL, `estimatedMinutes` INTEGER, `actualMinutes` INTEGER, `completionStatus` TEXT NOT NULL, `reviewStatus` TEXT NOT NULL, `learningSuggestion` TEXT, `suggestionSource` TEXT, `dependsOn` TEXT NOT NULL, `importBatchId` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `problem_documents`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problem_bank_entries_documentId` ON `problem_bank_entries` (`documentId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_bank_entries_documentId_chapter_problemNumber` ON `problem_bank_entries` (`documentId`, `chapter`, `problemNumber`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problem_bank_entries_completionStatus` ON `problem_bank_entries` (`completionStatus`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problem_bank_entries_reviewStatus` ON `problem_bank_entries` (`reviewStatus`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_problem_bank_entries_courseId` ON `problem_bank_entries` (`courseId`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_bank_formulas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `latex` TEXT NOT NULL, `confidence` REAL NOT NULL, `needsReview` INTEGER NOT NULL, `originalSnippet` TEXT NOT NULL, FOREIGN KEY(`problemId`) REFERENCES `problem_bank_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_bank_formulas_problemId_orderIndex` ON `problem_bank_formulas` (`problemId`, `orderIndex`)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `problem_bank_knowledge_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `problemId` INTEGER NOT NULL, `name` TEXT NOT NULL, `source` TEXT NOT NULL, FOREIGN KEY(`problemId`) REFERENCES `problem_bank_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_problem_bank_knowledge_points_problemId_name` ON `problem_bank_knowledge_points` (`problemId`, `name`)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `study_tasks` ADD COLUMN `postponeCount` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_usage_daily` (`dateKey` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `estimatedCostMicros` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`dateKey`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_analysis_cache` (`cacheKey` TEXT NOT NULL, `model` TEXT NOT NULL, `planJson` TEXT NOT NULL, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `costMicros` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_plan_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `model` TEXT NOT NULL, `usedCache` INTEGER NOT NULL, `planJson` TEXT NOT NULL, `appliedTaskCount` INTEGER NOT NULL, `costMicros` INTEGER NOT NULL)""",
        )
    }
}
