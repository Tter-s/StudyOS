package com.zhixu.studyos.core.bank

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.ProblemDocumentEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import com.zhixu.studyos.core.model.ProblemPageTextSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExerciseImportPipelineTest {

    private lateinit var database: StudyOSDatabase
    private lateinit var dao: ProblemBankDao
    private lateinit var pipeline: ExerciseImportPipeline
    private lateinit var fakeFactory: FakePageSourceFactory
    private val clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.problemBankDao()
        fakeFactory = FakePageSourceFactory()
        pipeline = ExerciseImportPipeline(
            dao = dao,
            sourceFactory = fakeFactory,
            parser = ExerciseProblemParser(analysisEngine = HeuristicProblemAnalyzer()),
            ocrRecognizer = null,
            clock = clock,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createDocument(pages: Int): ProblemDocument {
        val documentId = dao.insertDocument(
            ProblemDocumentEntity(
                displayName = "测试习题册",
                type = ProblemDocumentType.PDF.name,
                relativePath = "problem_bank_documents/test.pdf",
                originalName = "test.pdf",
                sizeBytes = 1000,
                contentSha256 = "sha-$pages",
                pageCount = pages,
                createdAtEpochMillis = clock.millis(),
                updatedAtEpochMillis = clock.millis(),
            ),
        )
        return ProblemDocument(
            id = documentId,
            displayName = "测试习题册",
            type = ProblemDocumentType.PDF,
            relativePath = "problem_bank_documents/test.pdf",
            originalName = "test.pdf",
            sizeBytes = 1000,
            contentSha256 = "sha-$pages",
            pageCount = pages,
            createdAtEpochMillis = clock.millis(),
            updatedAtEpochMillis = clock.millis(),
        )
    }

    private suspend fun createBatch(document: ProblemDocument): Long {
        val batchId = dao.insertBatch(
            com.zhixu.studyos.core.data.local.ProblemImportBatchEntity(
                documentId = document.id,
                status = ProblemImportBatchStatus.PAUSED.name,
                totalPages = document.pageCount,
                createdAtEpochMillis = clock.millis(),
                updatedAtEpochMillis = clock.millis(),
            ),
        )
        dao.insertItems(
            (0 until document.pageCount).map { pageIndex ->
                com.zhixu.studyos.core.data.local.ProblemImportItemEntity(
                    batchId = batchId,
                    pageIndex = pageIndex,
                    status = ProblemImportItemStatus.PENDING.name,
                    attempts = 0,
                    lastError = null,
                    draftJson = null,
                )
            },
        )
        return batchId
    }

    @Test
    fun runBatch_processesAllPages_andPersistsDrafts() = runTest {
        val document = createDocument(pages = 2)
        fakeFactory.pagesText[0] = "1. 求极限。\n2. 求导数。"
        fakeFactory.pagesText[1] = "3. 证明中值定理。"
        val batchId = createBatch(document)

        val progress = pipeline.runBatch(batchId, courseName = "数学分析", textbook = "习题册")

        assertEquals(ProblemImportBatchStatus.COMPLETED, progress.status)
        assertEquals(2, progress.donePages)
        assertEquals(0, progress.failedPages)
        val drafts = pipeline.loadDrafts(batchId)
        assertEquals(3, drafts.size)
        assertEquals(listOf("1", "2", "3"), drafts.map { it.problemNumber })
        // 页面文本已持久化
        assertNotNull(dao.getPage(document.id, 0))
        assertEquals(ProblemPageTextSource.EMBEDDED, ProblemPageTextSource.valueOf(dao.getPage(document.id, 0)!!.textSource))
    }

    @Test
    fun pageFailure_doesNotAffectOtherPages() = runTest {
        val document = createDocument(pages = 2)
        fakeFactory.pagesText[0] = "1. 正常页面。"
        fakeFactory.failPages.add(1)
        val batchId = createBatch(document)

        val progress = pipeline.runBatch(batchId, courseName = "", textbook = "")

        assertEquals(1, progress.donePages)
        assertEquals(1, progress.failedPages)
        assertEquals(ProblemImportBatchStatus.PAUSED, progress.status)
        val failedItem = progress.items.single { it.status == ProblemImportItemStatus.FAILED }
        assertEquals(1, failedItem.pageIndex)
        assertNotNull(failedItem.lastError)
    }

    @Test
    fun retryFailed_processesOnlyFailedPages() = runTest {
        val document = createDocument(pages = 2)
        fakeFactory.pagesText[0] = "1. 正常。"
        fakeFactory.failPages.add(1)
        val batchId = createBatch(document)
        pipeline.runBatch(batchId, courseName = "", textbook = "")

        // 第二次允许第 1 页成功
        fakeFactory.failPages.clear()
        fakeFactory.pagesText[1] = "2. 恢复的页面。"
        val progress = pipeline.retryFailed(batchId, courseName = "", textbook = "")

        assertEquals(ProblemImportBatchStatus.COMPLETED, progress.status)
        assertEquals(2, progress.donePages)
        assertEquals(2, pipeline.loadDrafts(batchId).size)
    }

    @Test
    fun resume_skipsDonePages() = runTest {
        val document = createDocument(pages = 2)
        fakeFactory.pagesText[0] = "1. 第一页。"
        fakeFactory.pagesText[1] = "2. 第二页。"
        val batchId = createBatch(document)

        // 第一轮：处理完第 0 页后取消（数据库状态改为 PAUSED）
        pipeline.runBatch(batchId, courseName = "", textbook = "") {
            // 第一页处理完成后立即取消
            if (it.donePages >= 1) pipeline.cancel(batchId)
        }
        val paused = pipeline.loadDrafts(batchId)
        assertEquals(1, paused.size)

        // 恢复：只处理剩余页
        val progress = pipeline.resumeBatch(batchId, courseName = "", textbook = "")
        assertEquals(ProblemImportBatchStatus.COMPLETED, progress.status)
        assertEquals(2, pipeline.loadDrafts(batchId).size)
    }

    @Test
    fun completedBatch_cannotRerun() = runTest {
        val document = createDocument(pages = 1)
        fakeFactory.pagesText[0] = "1. 唯一一页。"
        val batchId = createBatch(document)
        pipeline.runBatch(batchId, courseName = "", textbook = "")

        try {
            pipeline.runBatch(batchId, courseName = "", textbook = "")
            throw AssertionError("应当拒绝重复运行")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("已完成"))
        }
    }

    @Test
    fun missingDocument_abortsBatch() = runTest {
        val document = createDocument(pages = 1)
        val batchId = createBatch(document)
        // 删除文档（级联删除批次，所以这里直接清空文档表模拟损坏状态）
        dao.deleteDocumentById(document.id)

        try {
            pipeline.runBatch(batchId, courseName = "", textbook = "")
            throw AssertionError("文档缺失应当报错")
        } catch (error: Exception) {
            assertNotNull(error.message)
        }
        assertNull(dao.getBatch(batchId))
    }
}

private class FakePageSourceFactory : ExercisePageSourceFactory {
    val pagesText = mutableMapOf<Int, String>()
    val failPages = mutableListOf<Int>()
    private var currentDocument: ProblemDocument? = null

    override suspend fun open(document: ProblemDocument): ExercisePageSource {
        currentDocument = document
        return object : ExercisePageSource {
            override val document: ProblemDocument = document

            override suspend fun embeddedText(pageIndex: Int): String {
                if (pageIndex in failPages) throw IllegalStateException("模拟第 ${pageIndex + 1} 页解析失败")
                return pagesText[pageIndex] ?: ""
            }

            override suspend fun renderPage(pageIndex: Int, maxDimension: Int): android.graphics.Bitmap =
                throw UnsupportedOperationException("fake source has no bitmaps")

            override fun close() = Unit
        }
    }
}
