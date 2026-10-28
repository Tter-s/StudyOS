package com.zhixu.studyos.core.backup

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.ProblemBankEntryEntity
import com.zhixu.studyos.core.data.local.ProblemDocumentEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupArchiveTest {

    private lateinit var database: StudyOSDatabase
    private lateinit var filesDir: File
    private lateinit var creator: BackupCreator
    private lateinit var restorer: BackupRestorer

    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), zone)

    private val documentRelativePath = "problem_bank_documents/pdf-1/book.pdf"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        filesDir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "backup_test_${UUID.randomUUID()}",
        ).also { it.mkdirs() }
        creator = BackupCreator(
            database = database,
            filesDir = filesDir,
            clock = clock,
            zoneId = zone,
            appVersion = "1.0.0",
        )
        restorer = BackupRestorer(database = database, filesDir = filesDir, clock = clock)
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    private suspend fun seedTask(title: String = "原始任务") {
        database.studyTaskDao().insert(
            StudyTaskEntity(
                title = title,
                description = "",
                subject = "数学分析",
                priority = "MEDIUM",
                estimatedMinutes = 60,
                dueDateEpochDay = null,
                status = "BACKLOG",
                completedAtEpochMillis = null,
                seriesId = null,
                recurrenceType = "NONE",
                recurrenceInterval = 1,
                recurrenceUntilEpochDay = null,
                sortOrder = 1,
                postponeCount = 0,
                createdAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        )
    }

    private suspend fun seedDocument(withFile: Boolean = true) {
        val documentId = database.problemBankDao().insertDocument(
            ProblemDocumentEntity(
                displayName = "数分习题册",
                type = "PDF",
                relativePath = documentRelativePath,
                originalName = "book.pdf",
                sizeBytes = 12,
                contentSha256 = "sha-doc",
                pageCount = 1,
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
                estimatedDifficulty = 2,
                difficultySource = "HEURISTIC",
                estimatedMinutes = 10,
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
        if (withFile) {
            val file = File(filesDir, documentRelativePath)
            file.parentFile?.mkdirs()
            file.writeText("PDF-DATA-原始")
        }
    }

    private suspend fun createBackupBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        creator.write(output)
        return output.toByteArray()
    }

    @Test
    fun createBackup_inspectAndRestore_roundTrip() = runTest {
        seedTask()
        seedDocument()
        val bytes = createBackupBytes()

        val preview = restorer.inspect(
            ByteArrayInputStream(bytes),
            sourceName = "backup.zip",
            sizeBytes = bytes.size.toLong(),
        )
        assertEquals(BACKUP_FORMAT_VERSION, preview.manifest.formatVersion)
        assertEquals("1.0.0", preview.manifest.appVersion)
        assertEquals(1, preview.manifest.tableCounts["study_tasks"])
        assertEquals(1, preview.manifest.documentCount)
        assertTrue(preview.documentPaths.contains(documentRelativePath))
        assertTrue(preview.warnings.any { it.contains("不可撤销") })

        // 破坏当前数据：删除任务、文档记录与文件
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL("DELETE FROM `study_tasks`")
            db.execSQL("DELETE FROM `problem_bank_entries`")
            db.execSQL("DELETE FROM `problem_documents`")
        }
        File(filesDir, documentRelativePath).delete()

        val result = restorer.restore(ByteArrayInputStream(bytes))

        assertEquals(1, result.restoredCounts["study_tasks"])
        assertEquals(1, result.restoredDocumentFiles)
        assertEquals(1, database.studyTaskDao().count())
        assertEquals(1, database.problemBankDao().problemCountsByDocument().sumOf { it.problemCount })
        assertEquals("PDF-DATA-原始", File(filesDir, documentRelativePath).readText())
    }

    @Test
    fun inspect_manifestCountsAreVisibleBeforeRestore() = runTest {
        seedTask("任务A")
        seedTask("任务B")
        val bytes = createBackupBytes()

        val preview = restorer.inspect(ByteArrayInputStream(bytes), "b.zip", bytes.size.toLong())

        assertEquals(2, preview.manifest.tableCounts["study_tasks"])
    }

    @Test
    fun inspect_rejectsNonZip() = runTest {
        val error = assertThrows(BackupValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                restorer.inspect(ByteArrayInputStream("not a zip".toByteArray()), "bad.zip", 9)
            }
        }
        assertTrue(error.message!!.contains("manifest.json"))
    }

    @Test
    fun inspect_rejectsUnsupportedFormatVersion() = runTest {
        seedTask()
        val entries = unzip(createBackupBytes())
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("formatVersion", 2)
            .toString()
        val bytes = zip(entries + ("manifest.json" to manifest.toByteArray()))

        val error = assertThrows(BackupValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                restorer.inspect(ByteArrayInputStream(bytes), "future.zip", bytes.size.toLong())
            }
        }
        assertTrue(error.message!!.contains("格式版本"))
    }

    @Test
    fun inspect_rejectsPathTraversal() = runTest {
        seedTask()
        seedDocument()
        val entries = unzip(createBackupBytes()).toMutableMap()
        entries["documents/problem_bank_documents/../../evil.txt"] = "evil".toByteArray()
        val bytes = zip(entries)

        val error = assertThrows(BackupValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                restorer.inspect(ByteArrayInputStream(bytes), "attack.zip", bytes.size.toLong())
            }
        }
        assertTrue(error.message!!.contains("非法文件路径"))
    }

    @Test
    fun inspect_rejectsMissingReferencedDocument() = runTest {
        seedTask()
        seedDocument()
        val entries = unzip(createBackupBytes())
            .filterKeys { !it.startsWith("documents/") }
        val bytes = zip(entries)

        val error = assertThrows(BackupValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                restorer.inspect(ByteArrayInputStream(bytes), "no-docs.zip", bytes.size.toLong())
            }
        }
        assertTrue(error.message!!.contains("题库原始文件"))
    }

    @Test
    fun preview_warnsWhenBackupHasNoDocuments() = runTest {
        seedTask()
        val bytes = createBackupBytes()

        val preview = restorer.inspect(ByteArrayInputStream(bytes), "no-docs.zip", bytes.size.toLong())

        assertTrue(preview.warnings.any { it.contains("不包含题库原始文件") })
    }

    @Test
    fun restore_failure_rollsBackDatabaseAndFiles() = runTest {
        seedTask("备份中的任务")
        seedDocument()
        val entries = unzip(createBackupBytes())
        val data = JSONObject(entries.getValue("data.json").toString(Charsets.UTF_8))
        // 注入一行引用不存在任务的学习时段，触发外键失败
        val sessions = data.getJSONObject("tables").getJSONArray("task_sessions")
        sessions.put(
            JSONObject()
                .put("id", 777_777L)
                .put("taskId", 999_999L)
                .put("dateEpochDay", 20_300L)
                .put("startMinute", 480)
                .put("endMinuteExclusive", 540)
                .put("createdAtEpochMillis", 1L)
                .put("updatedAtEpochMillis", 1L)
                .put("isLocked", 1),
        )
        val brokenBytes = zip(entries + ("data.json" to data.toString().toByteArray()))

        // 当前数据与备份不同
        database.studyTaskDao().insert(
            StudyTaskEntity(
                title = "当前任务",
                description = "",
                subject = "",
                priority = "MEDIUM",
                estimatedMinutes = 30,
                dueDateEpochDay = null,
                status = "BACKLOG",
                completedAtEpochMillis = null,
                seriesId = null,
                recurrenceType = "NONE",
                recurrenceInterval = 1,
                recurrenceUntilEpochDay = null,
                sortOrder = 2,
                postponeCount = 0,
                createdAtEpochMillis = 2_000,
                updatedAtEpochMillis = 2_000,
            ),
        )
        File(filesDir, documentRelativePath).writeText("PDF-DATA-当前")

        val error = assertThrows(BackupValidationException::class.java) {
            kotlinx.coroutines.runBlocking {
                restorer.restore(ByteArrayInputStream(brokenBytes))
            }
        }
        assertTrue(error.message!!.contains("回滚") || error.message!!.contains("恢复失败"))

        // 数据库与文件都保持恢复前状态
        val titles = database.openHelper.readableDatabase
            .query("SELECT title FROM study_tasks ORDER BY id")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        assertEquals(listOf("备份中的任务", "当前任务"), titles)
        assertEquals("PDF-DATA-当前", File(filesDir, documentRelativePath).readText())
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
