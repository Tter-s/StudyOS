package com.zhixu.studyos.core.backup

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.CourseEntity
import com.zhixu.studyos.core.data.local.FinanceTransactionEntity
import com.zhixu.studyos.core.data.local.ProblemBankEntryEntity
import com.zhixu.studyos.core.data.local.ProblemDocumentEntity
import com.zhixu.studyos.core.data.local.SemesterEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskEntity
import com.zhixu.studyos.core.data.local.TaskSessionEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupCodecTest {

    private lateinit var database: StudyOSDatabase
    private val codec = BackupCodec()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed() {
        val taskDao = database.studyTaskDao()
        val id = taskDao.insert(
            StudyTaskEntity(
                title = "备份验证任务",
                description = "说明",
                subject = "数学分析",
                priority = "HIGH",
                estimatedMinutes = 60,
                dueDateEpochDay = 20_300,
                status = "SCHEDULED",
                completedAtEpochMillis = null,
                seriesId = null,
                recurrenceType = "NONE",
                recurrenceInterval = 1,
                recurrenceUntilEpochDay = null,
                sortOrder = 1,
                postponeCount = 2,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        taskDao.insertSessions(
            listOf(
                TaskSessionEntity(
                    taskId = id,
                    dateEpochDay = 20_300,
                    startMinute = 480,
                    endMinuteExclusive = 540,
                    createdAtEpochMillis = 1_000,
                    updatedAtEpochMillis = 1_000,
                    isLocked = true,
                ),
            ),
        )
        database.scheduleDao().upsertSemester(
            SemesterEntity(
                id = 1,
                startDateEpochDay = 20_000,
                zoneId = "Asia/Shanghai",
                updatedAtEpochMillis = 1_000,
            ),
        )
        database.scheduleDao().insertCourse(
            CourseEntity(
                name = "数学分析",
                teacher = "王老师",
                classroom = "A101",
                dayOfWeek = 1,
                startMinute = 480,
                endMinuteExclusive = 600,
                startWeek = 1,
                endWeek = 16,
                weekParity = "ALL",
                colorArgb = -10_000_000,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        database.financeDao().insertTransaction(
            FinanceTransactionEntity(
                dateEpochDay = 20_300,
                type = "EXPENSE",
                amountFen = 1234,
                categoryId = null,
                note = "午餐",
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        val documentId = database.problemBankDao().insertDocument(
            ProblemDocumentEntity(
                displayName = "数分习题册",
                type = "PDF",
                relativePath = "problem_bank_documents/pdf-1/book.pdf",
                originalName = "book.pdf",
                sizeBytes = 10,
                contentSha256 = "sha-1",
                pageCount = 2,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
        database.problemBankDao().insertProblem(
            ProblemBankEntryEntity(
                documentId = documentId,
                pageIndex = 0,
                courseId = null,
                courseName = "数学分析",
                textbook = "数分",
                chapter = "第一章",
                problemNumber = "1",
                statement = "题干",
                estimatedDifficulty = 3,
                difficultySource = "HEURISTIC",
                estimatedMinutes = 20,
                actualMinutes = null,
                completionStatus = "NEW",
                reviewStatus = "NOT_STARTED",
                learningSuggestion = null,
                suggestionSource = null,
                dependsOn = "",
                importBatchId = null,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
    }

    @Test
    fun exportValidateImport_roundTripPreservesRows() = runTest {
        seed()
        val exported = database.withTransaction {
            codec.exportTables(database.openHelper.writableDatabase)
        }
        assertFalse(exported.getJSONObject("tables").has("ai_usage_daily"))
        assertFalse(exported.getJSONObject("tables").has("ai_analysis_cache"))
        assertEquals("studyos-sql-v1", exported.getString("format"))

        val report = database.withTransaction {
            codec.validate(database.openHelper.writableDatabase, exported)
        }
        assertEquals(1, report.counts["study_tasks"])
        assertEquals(1, report.counts["task_sessions"])
        assertEquals(1, report.counts["courses"])
        assertEquals(1, report.counts["finance_transactions"])

        // 清空后导入
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            BackupTables.ordered.asReversed().forEach { db.execSQL("DELETE FROM `$it`") }
        }
        database.withTransaction {
            codec.importTables(database.openHelper.writableDatabase, exported, report)
        }

        val tasks = database.studyTaskDao().observeByDate(20_300).first()
        assertEquals(1, tasks.size)
        assertEquals("备份验证任务", tasks.single().task.title)
        assertEquals(2, tasks.single().task.postponeCount)
        assertTrue(tasks.single().sessions.single().isLocked)
        assertEquals(1, database.scheduleDao().observeCourses().first().size)
        assertEquals(1, database.financeDao().getTransactions().size)
        assertEquals(1, database.problemBankDao().getDocuments().size)
        assertEquals(1, database.problemBankDao().problemCountsByDocument().sumOf { it.problemCount })
    }

    @Test
    fun validate_rejectsNewerDatabaseVersion() = runTest {
        seed()
        val exported = database.withTransaction {
            codec.exportTables(database.openHelper.writableDatabase)
        }
        exported.put("databaseVersion", database.openHelper.writableDatabase.version + 1)

        val error = assertThrows(BackupValidationException::class.java) {
            runBlockingTransaction { codec.validate(database.openHelper.writableDatabase, exported) }
        }
        assertTrue(error.message!!.contains("更新版本"))
    }

    @Test
    fun validate_rejectsMissingRequiredColumn() = runTest {
        seed()
        val exported = database.withTransaction {
            codec.exportTables(database.openHelper.writableDatabase)
        }
        val taskRows = exported.getJSONObject("tables").getJSONArray("study_tasks")
        val row = taskRows.getJSONObject(0)
        row.remove("title")

        val error = assertThrows(BackupValidationException::class.java) {
            runBlockingTransaction { codec.validate(database.openHelper.writableDatabase, exported) }
        }
        assertTrue(error.message!!.contains("必填列"))
    }

    @Test
    fun import_failure_rollsBackWholeTableSet() = runTest {
        seed()
        val exported = database.withTransaction {
            codec.exportTables(database.openHelper.writableDatabase)
        }
        val report = database.withTransaction {
            codec.validate(database.openHelper.writableDatabase, exported)
        }
        // 插入一条引用不存在任务的学习时段，外键会拒绝
        val sessionRows = exported.getJSONObject("tables").getJSONArray("task_sessions")
        sessionRows.put(
            JSONObject()
                .put("id", 999_999L)
                .put("taskId", 987_654L)
                .put("dateEpochDay", 20_300L)
                .put("startMinute", 480)
                .put("endMinuteExclusive", 540)
                .put("createdAtEpochMillis", 1L)
                .put("updatedAtEpochMillis", 1L)
                .put("isLocked", 1),
        )
        val updatedReport = report.copy(
            tableValidations = report.tableValidations + (
                "task_sessions" to report.tableValidations.getValue("task_sessions").copy(rowCount = 2)
                ),
        )

        val failure = runCatching {
            database.withTransaction {
                codec.importTables(database.openHelper.writableDatabase, exported, updatedReport)
            }
        }
        assertTrue(failure.isFailure)

        // 事务回滚：原数据仍在，没有被清空
        val tasks = database.studyTaskDao().observeByDate(20_300).first()
        assertEquals(1, tasks.size)
        assertEquals("备份验证任务", tasks.single().task.title)
        assertEquals(1, database.financeDao().getTransactions().size)
    }

    @Test
    fun exportTables_containEveryBusinessTable() = runTest {
        seed()
        val tables = database.withTransaction {
            codec.exportTables(database.openHelper.writableDatabase).getJSONObject("tables")
        }

        BackupTables.ordered.forEach { table ->
            assertTrue("缺少表 $table", tables.has(table))
            assertTrue("表 $table 不是数组", tables.getJSONArray(table) is org.json.JSONArray)
        }
        assertEquals(0, tables.getJSONArray("subjects").length()) // 内存库未执行种子回调
    }

    private fun <T> runBlockingTransaction(block: () -> T): T =
        kotlinx.coroutines.runBlocking {
            database.withTransaction { block() }
        }
}
