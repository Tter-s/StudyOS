package com.zhixu.studyos.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StudyOSDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "studyos-migration-test.db"
    private var migratedDatabase: StudyOSDatabase? = null

    @After
    fun tearDown() {
        migratedDatabase?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration1To10_preservesStudyTasksAndCreatesAllTablesThroughV10() = runTest {
        createVersionOneDatabase()

        val database = Room.databaseBuilder(context, StudyOSDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        migratedDatabase = database
        database.openHelper.writableDatabase

        val tasks = database.studyTaskDao().observeByDate(20_000).first()
        assertEquals(1, tasks.size)
        assertEquals(10, database.openHelper.writableDatabase.version)
        assertEquals("迁移前的任务", tasks.single().task.title)
        assertEquals("SCHEDULED", tasks.single().task.status)
        assertEquals("task-${tasks.single().task.id}", tasks.single().task.globalId)
        assertEquals(1, tasks.single().task.revision)
        assertEquals(1, tasks.single().sessions.size)
        assertTrue(tasks.single().sessions.single().isLocked)
        assertEquals(5, database.learningDao().getSubjects().size)

        // V3.1：旧任务按原日期归档为极简待办，旧表本身保持不变。
        val dailyTasks = database.dailyLogDao().tasks(20_000)
        assertEquals(1, dailyTasks.size)
        assertEquals("迁移前的任务", dailyTasks.single().title)
        assertEquals(false, dailyTasks.single().isCompleted)
        database.dailyLogDao().insertSummary(
            DailySummaryEntity(
                dateEpochDay = 20_000,
                content = "迁移后的总结",
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        assertEquals("迁移后的总结", database.dailyLogDao().summary(20_000)?.content)

        val financeCategories = database.financeDao().getCategories()
        assertEquals(
            listOf("餐饮", "日用品", "学习资料", "交通", "娱乐", "住宿", "其他"),
            financeCategories.map { it.name },
        )
        assertTrue(financeCategories.all { it.isPreset })
        val financeCategoryIds = financeCategories.map { it.id }.sorted()
        assertEquals((1L..7L).toList(), financeCategoryIds)

        val expenseId = database.financeDao().insertTransaction(
            FinanceTransactionEntity(
                dateEpochDay = LocalDate.of(2026, 10, 25).toEpochDay(),
                type = "EXPENSE",
                amountFen = 1_234,
                categoryId = 1,
                note = "迁移后记录",
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        database.financeDao().upsertMonthlyBudget(
            FinanceMonthlyBudgetEntity(
                monthKey = "2026-10",
                totalBudgetFen = 100_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        database.financeDao().upsertCategoryBudget(
            FinanceCategoryBudgetEntity(
                monthKey = "2026-10",
                categoryId = 1,
                limitFen = 30_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        assertTrue(expenseId > 0L)
        assertEquals(1, database.financeDao().getTransactions().size)
        assertEquals(100_000L, database.financeDao().getMonthlyBudget("2026-10")?.totalBudgetFen)
        assertEquals(1, database.financeDao().getCategoryBudgets().size)

        database.schedulingDao().upsertSettings(
            SchedulingSettingsEntity(
                dailyLimitMinutes = 240,
                bufferMinutes = 10,
                studyStartMinute = 480,
                studyEndMinuteExclusive = 1_320,
                planReminderEnabled = false,
                planReminderHour = 7,
                planReminderMinute = 30,
                updatedAtEpochMillis = 2_000,
            ),
        )
        database.schedulingDao().upsertSubjectPreferences(
            listOf(
                SchedulingSubjectPreferenceEntity(
                    subjectName = "数学分析",
                    level = "HIGHER",
                    updatedAtEpochMillis = 2_000,
                ),
            ),
        )
        assertEquals(240, database.schedulingDao().getSettings()?.dailyLimitMinutes)
        assertEquals(1, database.schedulingDao().getSubjectPreferences().size)

        val scheduleDao = database.scheduleDao()
        scheduleDao.upsertSemester(
            SemesterEntity(
                startDateEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
                zoneId = "Asia/Shanghai",
                updatedAtEpochMillis = 2_000,
            ),
        )
        val courseId = scheduleDao.insertCourse(
            CourseEntity(
                name = "数学分析",
                teacher = null,
                classroom = "A101",
                dayOfWeek = 1,
                startMinute = 480,
                endMinuteExclusive = 540,
                startWeek = 1,
                endWeek = 16,
                weekParity = WeekParity.ALL.name,
                colorArgb = 0xFF64B5F6.toInt(),
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        scheduleDao.upsertException(
            CourseExceptionEntity(
                courseId = courseId,
                originalDateEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
                type = CourseExceptionType.CANCEL.name,
                targetDateEpochDay = null,
                targetStartMinute = null,
                targetEndMinuteExclusive = null,
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        scheduleDao.insertEvent(
            OneOffEventEntity(
                title = "班会",
                dateEpochDay = LocalDate.of(2026, 9, 8).toEpochDay(),
                startMinute = 1_080,
                endMinuteExclusive = 1_140,
                location = "",
                colorArgb = 0xFFFFB74D.toInt(),
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        scheduleDao.insertRoutine(
            RoutineBlockEntity(
                title = "睡眠",
                category = "SLEEP",
                repeatType = "DAILY",
                dayOfWeek = null,
                startMinute = 1_380,
                endMinuteExclusive = 1_860,
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )

        assertEquals(1, scheduleDao.observeCourses().first().size)
        assertEquals(1, scheduleDao.observeExceptions().first().size)
        assertEquals(1, scheduleDao.observeEvents().first().size)
        assertEquals(1, scheduleDao.observeRoutines().first().size)

        scheduleDao.deleteCourse(courseId)
        assertTrue(scheduleDao.observeExceptions().first().isEmpty())
    }

    @Test
    fun migration2To10_preservesVersionTwoTasksAndCourseData() = runTest {
        createVersionTwoDatabase()

        val database = Room.databaseBuilder(context, StudyOSDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        migratedDatabase = database
        database.openHelper.writableDatabase

        val tasks = database.studyTaskDao().observeByDate(20_000).first()
        assertEquals(1, tasks.size)
        assertEquals("V0.2 的任务", tasks.single().task.title)
        assertEquals("COMPLETED", tasks.single().task.status)
        assertEquals(42, tasks.single().sessions.single().endMinuteExclusive - tasks.single().sessions.single().startMinute)
        assertEquals(1, database.scheduleDao().observeCourses().first().size)
        assertEquals("数学分析", database.scheduleDao().observeCourses().first().single().name)
        assertEquals(5, database.learningDao().getSubjects().size)
        assertTrue(tasks.single().sessions.single().isLocked)
        assertEquals(7, database.financeDao().getCategories().size)
        assertEquals("餐饮", database.financeDao().getCategories().first().name)
        // v7/v8 表也可用，证明升级到当前版本完整
        assertEquals(0, database.problemBankDao().observeDocuments().first().size)
        assertEquals(null, database.aiDao().getUsage("2026-10-25"))
    }

    @Test
    fun migration6To10_createsProblemBankAndAiTablesAndKeepsExistingData() = runTest {
        createVersionSixDatabase()

        val database = Room.databaseBuilder(context, StudyOSDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        migratedDatabase = database

        // 既有数据保留
        val tasks = database.studyTaskDao().observeByDate(20_000).first()
        assertEquals(1, tasks.size)
        assertEquals("V0.6 的任务", tasks.single().task.title)
        assertTrue(tasks.single().sessions.single().isLocked)
        assertEquals("数学分析", database.scheduleDao().observeCourses().first().single().name)

        // 新的题库表可用
        val dao = database.problemBankDao()
        val documentId = dao.insertDocument(
            ProblemDocumentEntity(
                displayName = "迁移后的习题册",
                type = com.zhixu.studyos.core.model.ProblemDocumentType.PDF.name,
                relativePath = "problem_bank_documents/migrated.pdf",
                originalName = "migrated.pdf",
                sizeBytes = 10,
                contentSha256 = "sha-migration",
                pageCount = 1,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        val batchId = dao.insertBatch(
            ProblemImportBatchEntity(
                documentId = documentId,
                status = com.zhixu.studyos.core.model.ProblemImportBatchStatus.PAUSED.name,
                totalPages = 1,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        dao.insertItems(
            listOf(
                ProblemImportItemEntity(
                    batchId = batchId,
                    pageIndex = 0,
                    status = com.zhixu.studyos.core.model.ProblemImportItemStatus.PENDING.name,
                    attempts = 0,
                    lastError = null,
                    draftJson = null,
                ),
            ),
        )
        val problemId = dao.insertProblem(
            ProblemBankEntryEntity(
                documentId = documentId,
                pageIndex = 0,
                courseId = null,
                courseName = "数学分析",
                textbook = "教材",
                chapter = "第一章",
                problemNumber = "1",
                statement = "迁移验证题干",
                estimatedDifficulty = 2,
                difficultySource = com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC.name,
                estimatedMinutes = 12,
                actualMinutes = null,
                completionStatus = com.zhixu.studyos.core.model.ProblemCompletionStatus.NEW.name,
                reviewStatus = com.zhixu.studyos.core.model.ProblemReviewStatus.NOT_STARTED.name,
                learningSuggestion = null,
                suggestionSource = null,
                dependsOn = "",
                importBatchId = batchId,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        dao.insertFormulas(
            listOf(
                ProblemBankFormulaEntity(
                    problemId = problemId,
                    orderIndex = 0,
                    latex = "x^2",
                    confidence = 0.9,
                    needsReview = false,
                    originalSnippet = "x^2",
                ),
            ),
        )
        assertEquals(1, dao.countProblemsForDocument(documentId))
        assertEquals("x^2", dao.getProblemWithDetails(problemId)!!.formulas.single().latex)

        // v8：延期计数默认 0，AI 用量表可用
        assertEquals(0, tasks.single().task.postponeCount)
        val aiDao = database.aiDao()
        aiDao.upsertUsage(
            AiUsageDailyEntity(
                dateKey = LocalDate.of(2026, 10, 25).toString(),
                requestCount = 2,
                promptTokens = 100,
                completionTokens = 50,
                estimatedCostMicros = 1234,
                updatedAtEpochMillis = 2_000,
            ),
        )
        val usage = aiDao.getUsage(LocalDate.of(2026, 10, 25).toString())
        assertEquals(2, usage!!.requestCount)
        assertEquals(1234L, usage.estimatedCostMicros)
    }

    /** 由导出的 Schema 6 JSON 生成的 v6 完整建表语句，用于验证 MIGRATION_6_7。 */
    private fun createVersionSixDatabase() {
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `study_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `subject` TEXT NOT NULL, `priority` TEXT NOT NULL, `estimatedMinutes` INTEGER NOT NULL, `dueDateEpochDay` INTEGER, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `seriesId` TEXT, `recurrenceType` TEXT NOT NULL, `recurrenceInterval` INTEGER NOT NULL, `recurrenceUntilEpochDay` INTEGER, `sortOrder` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_tasks_status` ON `study_tasks` (`status`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_tasks_seriesId` ON `study_tasks` (`seriesId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_tasks_dueDateEpochDay` ON `study_tasks` (`dueDateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `task_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(`taskId`) REFERENCES `study_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_task_sessions_taskId` ON `task_sessions` (`taskId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_task_sessions_dateEpochDay` ON `task_sessions` (`dateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `semester_settings` (`id` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT, `classroom` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `startWeek` INTEGER NOT NULL, `endWeek` INTEGER NOT NULL, `weekParity` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_courses_dayOfWeek` ON `courses` (`dayOfWeek`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `course_exceptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `courseId` INTEGER NOT NULL, `originalDateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `targetDateEpochDay` INTEGER, `targetStartMinute` INTEGER, `targetEndMinuteExclusive` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_course_exceptions_courseId_originalDateEpochDay` ON `course_exceptions` (`courseId`, `originalDateEpochDay`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_course_exceptions_originalDateEpochDay` ON `course_exceptions` (`originalDateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `one_off_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `location` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_one_off_events_dateEpochDay` ON `one_off_events` (`dateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `routine_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `repeatType` TEXT NOT NULL, `dayOfWeek` INTEGER, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isPreset` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_subjects_name` ON `subjects` (`name`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `study_timing_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `taskId` INTEGER, `dateEpochDay` INTEGER NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, `plannedDurationMinutes` INTEGER, `accumulatedMillis` INTEGER NOT NULL, `runningSinceEpochMillis` INTEGER, `interruptionCount` INTEGER NOT NULL, `lastInterruptionReason` TEXT, `startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER, `correctedAtEpochMillis` INTEGER, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`taskId`) REFERENCES `study_tasks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_subjectId` ON `study_timing_sessions` (`subjectId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_taskId` ON `study_timing_sessions` (`taskId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_status` ON `study_timing_sessions` (`status`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_study_timing_sessions_dateEpochDay` ON `study_timing_sessions` (`dateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `problems` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subjectId` INTEGER NOT NULL, `textbook` TEXT NOT NULL, `chapter` TEXT NOT NULL, `problemNumber` TEXT NOT NULL, `knowledgePoint` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `independentThinkingMinutes` INTEGER NOT NULL, `usedAiHint` INTEGER NOT NULL, `blockerReason` TEXT, `isMistake` INTEGER NOT NULL, `isDifficult` INTEGER NOT NULL, `independentCompleted` INTEGER, `retryCount` INTEGER NOT NULL, `secondAttemptIndependent` INTEGER, `completedAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_problems_subjectId` ON `problems` (`subjectId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_problems_status` ON `problems` (`status`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_problems_isDifficult` ON `problems` (`isDifficult`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_problems_isMistake` ON `problems` (`isMistake`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `self_control_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `procrastinationReason` TEXT, `voluntaryInterruption` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_self_control_entries_dateEpochDay` ON `self_control_entries` (`dateEpochDay`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `learning_goals` (`id` INTEGER NOT NULL, `dailyStudyMinutes` INTEGER, `weeklyStudyMinutes` INTEGER, `dailyTaskTarget` INTEGER, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `isPreset` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_categories_name` ON `finance_categories` (`name`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `amountFen` INTEGER NOT NULL, `categoryId` INTEGER, `note` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `finance_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_finance_transactions_dateEpochDay` ON `finance_transactions` (`dateEpochDay`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_finance_transactions_categoryId` ON `finance_transactions` (`categoryId`)""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_finance_transactions_type` ON `finance_transactions` (`type`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_monthly_budgets` (`monthKey` TEXT NOT NULL, `totalBudgetFen` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`monthKey`))""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_category_budgets` (`monthKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `limitFen` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`monthKey`, `categoryId`), FOREIGN KEY(`categoryId`) REFERENCES `finance_categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_finance_category_budgets_categoryId` ON `finance_category_budgets` (`categoryId`)""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `scheduling_settings` (`id` INTEGER NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `bufferMinutes` INTEGER NOT NULL, `studyStartMinute` INTEGER NOT NULL, `studyEndMinuteExclusive` INTEGER NOT NULL, `planReminderEnabled` INTEGER NOT NULL, `planReminderHour` INTEGER NOT NULL, `planReminderMinute` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `scheduling_subject_preferences` (`subjectName` TEXT NOT NULL, `level` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`subjectName`))""",
            )
            db.execSQL(
                """INSERT INTO `study_tasks` (`title`, `description`, `subject`, `priority`, `estimatedMinutes`, `dueDateEpochDay`, `status`, `completedAtEpochMillis`, `seriesId`, `recurrenceType`, `recurrenceInterval`, `recurrenceUntilEpochDay`, `sortOrder`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES ('V0.6 的任务', '', '数学分析', 'MEDIUM', 42, NULL, 'SCHEDULED', NULL, NULL, 'NONE', 1, NULL, 1, 1000, 2000)""",
            )
            db.execSQL(
                """INSERT INTO `task_sessions` (`taskId`, `dateEpochDay`, `startMinute`, `endMinuteExclusive`, `createdAtEpochMillis`, `updatedAtEpochMillis`, `isLocked`) VALUES (1, 20000, 480, 522, 1000, 1000, 1)""",
            )
            db.execSQL(
                """INSERT INTO `courses` (`name`, `teacher`, `classroom`, `dayOfWeek`, `startMinute`, `endMinuteExclusive`, `startWeek`, `endWeek`, `weekParity`, `colorArgb`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES ('数学分析', NULL, 'A101', 1, 480, 600, 1, 16, 'ALL', -10000000, 1000, 1000)""",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f796de06b5ff3ef5e16668cbfd57a828')",
            )
            db.version = 6
        }
    }

    private fun createVersionTwoDatabase() {
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `study_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `estimatedMinutes` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_dateEpochDay` ON `study_tasks` (`dateEpochDay`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `semester_settings` (`id` INTEGER NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT, `classroom` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `startWeek` INTEGER NOT NULL, `endWeek` INTEGER NOT NULL, `weekParity` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_dayOfWeek` ON `courses` (`dayOfWeek`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `course_exceptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `courseId` INTEGER NOT NULL, `originalDateEpochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `targetDateEpochDay` INTEGER, `targetStartMinute` INTEGER, `targetEndMinuteExclusive` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_course_exceptions_courseId_originalDateEpochDay` ON `course_exceptions` (`courseId`, `originalDateEpochDay`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_exceptions_originalDateEpochDay` ON `course_exceptions` (`originalDateEpochDay`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `one_off_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `location` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_one_off_events_dateEpochDay` ON `one_off_events` (`dateEpochDay`)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `routine_blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `repeatType` TEXT NOT NULL, `dayOfWeek` INTEGER, `startMinute` INTEGER NOT NULL, `endMinuteExclusive` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                """INSERT INTO `study_tasks` (`title`, `dateEpochDay`, `estimatedMinutes`, `isCompleted`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES ('V0.2 的任务', 20000, 42, 1, 1000, 2000)""",
            )
            db.execSQL(
                """INSERT INTO `semester_settings` (`id`, `startDateEpochDay`, `zoneId`, `updatedAtEpochMillis`) VALUES (1, 20000, 'Asia/Shanghai', 1000)""",
            )
            db.execSQL(
                """INSERT INTO `courses` (`name`, `teacher`, `classroom`, `dayOfWeek`, `startMinute`, `endMinuteExclusive`, `startWeek`, `endWeek`, `weekParity`, `colorArgb`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES ('数学分析', NULL, 'A101', 1, 480, 600, 1, 16, 'ALL', -10000000, 1000, 1000)""",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd5fd78032a2c1d3bb9864ece71a36064')",
            )
            db.version = 2
        }
    }

    private fun createVersionOneDatabase() {
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `study_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `estimatedMinutes` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_dateEpochDay` ON `study_tasks` (`dateEpochDay`)")
            db.execSQL(
                """INSERT INTO `study_tasks` (`title`, `dateEpochDay`, `estimatedMinutes`, `isCompleted`, `createdAtEpochMillis`, `updatedAtEpochMillis`) VALUES ('迁移前的任务', 20000, 45, 0, 1000, 1000)""",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ad7d6c6db737f07a420e6a64d059d0d4')",
            )
            db.version = 1
        }
    }
}
