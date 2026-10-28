package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProblemBankRepositoryTest {

    private lateinit var database: StudyOSDatabase
    private lateinit var repository: OfflineProblemBankRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = OfflineProblemBankRepository(database, database.problemBankDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createDocument(displayName: String = "习题册A", sha: String = "sha-a"): Long =
        repository.createDocument(
            displayName = displayName,
            relativePath = "problem_bank_documents/a.pdf",
            originalName = "a.pdf",
            sizeBytes = 100,
            contentSha256 = sha,
            pageCount = 3,
            type = ProblemDocumentType.PDF,
        )

    private fun draft(documentId: Long, number: String, chapter: String = "第一章") =
        com.zhixu.studyos.core.model.ProblemDraft(
            localId = "local-$number",
            documentId = documentId,
            pageIndex = 0,
            problemNumber = number,
            statement = "第 $number 题的题干内容",
            chapter = chapter,
            courseName = "数学分析",
            textbook = "数学分析习题册",
            formulas = listOf(
                ProblemFormula(
                    orderIndex = 0,
                    latex = "x^2+1",
                    confidence = 0.9,
                    needsReview = false,
                    originalSnippet = "x^2+1",
                ),
            ),
            knowledgePoints = listOf(ProblemKnowledgePoint(name = "数列极限", source = com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC)),
            estimatedDifficulty = 3,
            estimatedMinutes = 20,
            learningSuggestion = "建议先复习定义。",
        )

    @Test
    fun confirmDrafts_insertsProblemsWithFormulasAndKnowledgePoints() = runTest {
        val documentId = createDocument()

        val result = repository.confirmDrafts(listOf(draft(documentId, "1"), draft(documentId, "2")))

        assertEquals(2, result.inserted)
        assertEquals(0, result.skippedDuplicates)
        val problems = repository.observeProblems().first()
        assertEquals(2, problems.size)
        val first = problems.first { it.problemNumber == "1" }
        assertEquals("x^2+1", first.formulas.single().latex)
        assertEquals("数列极限", first.knowledgePoints.single().name)
        assertEquals(3, first.estimatedDifficulty)
        assertEquals("建议先复习定义。", first.learningSuggestion)
        assertEquals(documentId, first.documentId)
    }

    @Test
    fun confirmDrafts_skipsExistingKeys_inSingleTransaction() = runTest {
        val documentId = createDocument()
        repository.confirmDrafts(listOf(draft(documentId, "1")))

        val result = repository.confirmDrafts(
            listOf(draft(documentId, "1"), draft(documentId, "3")),
        )

        assertEquals(1, result.inserted)
        assertEquals(1, result.skippedDuplicates)
        assertEquals(2, repository.observeProblems().first().size)
    }

    @Test
    fun confirmDrafts_rollsBackAllOnFailure() = runTest {
        val documentId = createDocument()
        val broken = draft(documentId, "1").copy(statement = "")

        try {
            repository.confirmDrafts(listOf(broken))
            throw AssertionError("非法草稿应当抛出异常")
        } catch (_: Exception) {
        }
        // 事务回滚：没有任何题目被写入
        assertTrue(repository.observeProblems().first().isEmpty())
    }

    @Test
    fun duplicateContentDocument_isRejected() = runTest {
        createDocument(sha = "sha-x")
        try {
            createDocument(displayName = "重复文件", sha = "sha-x")
            throw AssertionError("相同内容应当被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("已导入过"))
        }
    }

    @Test
    fun updateProblem_replacesFormulasAndMetadata() = runTest {
        val documentId = createDocument()
        repository.confirmDrafts(listOf(draft(documentId, "1")))
        val problem = repository.observeProblems().first().single()

        repository.updateProblem(
            problem.copy(
                statement = "用户修改后的题干",
                estimatedDifficulty = 5,
                actualMinutes = 33,
                completionStatus = com.zhixu.studyos.core.model.ProblemCompletionStatus.COMPLETED,
                reviewStatus = com.zhixu.studyos.core.model.ProblemReviewStatus.MASTERED,
                formulas = listOf(
                    ProblemFormula(orderIndex = 0, latex = "y=f(x)", confidence = 0.9, needsReview = false, originalSnippet = "y=f(x)"),
                ),
                knowledgePoints = listOf(ProblemKnowledgePoint(name = "导数与微分", source = com.zhixu.studyos.core.model.ProblemAnalysisSource.USER)),
            ),
        )

        val updated = repository.getProblem(problem.id)!!
        assertEquals("用户修改后的题干", updated.statement)
        assertEquals(5, updated.estimatedDifficulty)
        assertEquals(33, updated.actualMinutes)
        assertEquals(com.zhixu.studyos.core.model.ProblemCompletionStatus.COMPLETED, updated.completionStatus)
        assertEquals(com.zhixu.studyos.core.model.ProblemReviewStatus.MASTERED, updated.reviewStatus)
        assertEquals("y=f(x)", updated.formulas.single().latex)
        assertEquals("导数与微分", updated.knowledgePoints.single().name)
    }

    @Test
    fun updateProblem_sameKeyCollision_isRejected() = runTest {
        val documentId = createDocument()
        repository.confirmDrafts(listOf(draft(documentId, "1"), draft(documentId, "2")))
        val problem = repository.observeProblems().first().first { it.problemNumber == "2" }

        try {
            repository.updateProblem(problem.copy(problemNumber = "1"))
            throw AssertionError("同键冲突应当被拒绝")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("已存在"))
        }
    }

    @Test
    fun deleteDocument_blockedWhileProblemsExist() = runTest {
        val documentId = createDocument()
        repository.confirmDrafts(listOf(draft(documentId, "1")))

        try {
            repository.deleteDocument(documentId)
            throw AssertionError("有题目时应当拒绝删除来源")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("仍有"))
        }
        // 删除题目后可以删除来源
        repository.deleteProblem(repository.observeProblems().first().single().id)
        repository.deleteDocument(documentId)
        assertTrue(repository.observeDocuments().first().isEmpty())
    }

    @Test
    fun existingKeys_andSuspectedKeys_driveDeduplication() = runTest {
        val documentId = createDocument()
        repository.confirmDrafts(listOf(draft(documentId, "1", chapter = "第一章")))

        val keys = repository.existingKeys(documentId)
        assertTrue(keys.contains(com.zhixu.studyos.core.bank.ProblemDeduplicator.key(documentId, "第一章", "1")))

        val suspected = repository.suspectedDuplicateKeys("数学分析习题册", excludeDocumentId = documentId + 1)
        assertTrue(
            suspected.contains(com.zhixu.studyos.core.bank.ProblemDeduplicator.suspectedKey("数学分析习题册", "第一章", "1")),
        )
    }
}
