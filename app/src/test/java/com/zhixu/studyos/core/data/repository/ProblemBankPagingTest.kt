package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.ProblemBankEntryEntity
import com.zhixu.studyos.core.data.local.ProblemBankFormulaEntity
import com.zhixu.studyos.core.data.local.ProblemBankKnowledgePointEntity
import com.zhixu.studyos.core.data.local.ProblemDocumentEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocumentType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProblemBankPagingTest {

    private lateinit var database: StudyOSDatabase
    private lateinit var repository: OfflineProblemBankRepository
    private var documentId = 0L

    private val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("UTC"))

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

    private suspend fun seedProblems(count: Int = 120) {
        documentId = database.problemBankDao().insertDocument(
            ProblemDocumentEntity(
                displayName = "大习题册",
                type = ProblemDocumentType.PDF.name,
                relativePath = "problem_bank_documents/big/book.pdf",
                originalName = "book.pdf",
                sizeBytes = 1,
                contentSha256 = "sha-big",
                pageCount = count,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )
        for (index in 1..count) {
            val problemId = database.problemBankDao().insertProblem(
                ProblemBankEntryEntity(
                    documentId = documentId,
                    pageIndex = index / 10,
                    courseId = null,
                    courseName = "数学分析",
                    textbook = "数分习题册",
                    chapter = if (index <= 60) "第一章" else "第二章",
                    problemNumber = index.toString(),
                    statement = "第 $index 题的题干内容",
                    estimatedDifficulty = (index % 5) + 1,
                    difficultySource = "HEURISTIC",
                    estimatedMinutes = 10,
                    actualMinutes = null,
                    completionStatus = if (index % 4 == 0) {
                        ProblemCompletionStatus.COMPLETED.name
                    } else {
                        ProblemCompletionStatus.NEW.name
                    },
                    reviewStatus = "NOT_STARTED",
                    learningSuggestion = null,
                    suggestionSource = null,
                    dependsOn = "",
                    importBatchId = null,
                    createdAtEpochMillis = index.toLong(),
                    updatedAtEpochMillis = index.toLong(),
                ),
            )
            if (index % 20 == 0) {
                database.problemBankDao().insertFormulas(
                    listOf(
                        ProblemBankFormulaEntity(
                            problemId = problemId,
                            orderIndex = 0,
                            latex = "x^2",
                            confidence = 0.5,
                            needsReview = true,
                            originalSnippet = "x^2",
                        ),
                    ),
                )
            }
            if (index % 30 == 0) {
                database.problemBankDao().insertKnowledgePoints(
                    listOf(
                        ProblemBankKnowledgePointEntity(
                            problemId = problemId,
                            name = "极限与连续",
                            source = "HEURISTIC",
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun page_returnsLimitedRowsWithAccurateTotals() = runTest {
        seedProblems()
        val first = repository.pageProblems(ProblemQuery(), limit = 50, offset = 0)

        assertEquals(50, first.problems.size)
        assertEquals(120, first.totalCount)
        assertEquals(30, first.completedCount) // 每 4 题一个已完成
        assertEquals("1", first.problems.first().problemNumber)

        val second = repository.pageProblems(ProblemQuery(), limit = 50, offset = 50)
        assertEquals(50, second.problems.size)
        assertEquals("51", second.problems.first().problemNumber)
        assertTrue(first.problems.map { it.id }.intersect(second.problems.map { it.id }.toSet()).isEmpty())

        val third = repository.pageProblems(ProblemQuery(), limit = 50, offset = 100)
        assertEquals(20, third.problems.size)
    }

    @Test
    fun chapterFilter_isAppliedInSql() = runTest {
        seedProblems()
        val page = repository.pageProblems(
            ProblemQuery(chapter = "第二章"),
            limit = 50,
            offset = 0,
        )

        assertEquals(60, page.totalCount)
        assertTrue(page.problems.all { it.chapter == "第二章" })
    }

    @Test
    fun onlyUnfinished_excludesCompleted() = runTest {
        seedProblems()
        val page = repository.pageProblems(
            ProblemQuery(onlyUnfinished = true),
            limit = 200,
            offset = 0,
        )

        assertEquals(90, page.totalCount)
        assertTrue(page.problems.none { it.completionStatus == ProblemCompletionStatus.COMPLETED })
    }

    @Test
    fun search_matchesStatementAndKnowledgePoints() = runTest {
        seedProblems()
        val byNumber = repository.pageProblems(
            ProblemQuery(searchText = "第 77 题"),
            limit = 10,
            offset = 0,
        )
        assertEquals(1, byNumber.totalCount)
        assertEquals("77", byNumber.problems.single().problemNumber)

        val byKnowledge = repository.pageProblems(
            ProblemQuery(searchText = "极限与连续"),
            limit = 10,
            offset = 0,
        )
        assertEquals(4, byKnowledge.totalCount) // 30 / 60 / 90 / 120
    }

    @Test
    fun formulaReviewFilter_usesFormulaTable() = runTest {
        seedProblems()
        val page = repository.pageProblems(
            ProblemQuery(onlyFormulaNeedsReview = true),
            limit = 50,
            offset = 0,
        )

        assertEquals(6, page.totalCount) // 每 20 题一个
        assertTrue(page.problems.all { it.hasFormulaNeedingReview })
    }

    @Test
    fun maxDifficultyFilter_isApplied() = runTest {
        seedProblems()
        val page = repository.pageProblems(
            ProblemQuery(maxDifficulty = 2),
            limit = 200,
            offset = 0,
        )

        // 难度循环 1..5：120 题中 ≤2 的为 48 题
        assertEquals(48, page.totalCount)
    }

    @Test
    fun problemsForSelection_respectsLimitAndFilter() = runTest {
        seedProblems()
        val selected = repository.problemsForSelection(
            ProblemQuery(chapter = "第一章").copy(onlyUnfinished = true),
            limit = 25,
        )

        assertEquals(25, selected.size)
        assertTrue(selected.all { it.chapter == "第一章" })
    }

    @Test
    fun distinctChapters_returnsSortedUniqueValues() = runTest {
        seedProblems()

        assertEquals(listOf("第一章", "第二章"), repository.distinctChapters())
    }

    @Test
    fun documentFilter_separatesSources() = runTest {
        seedProblems(count = 4)
        val otherDocument = database.problemBankDao().insertDocument(
            ProblemDocumentEntity(
                displayName = "另一册",
                type = ProblemDocumentType.PDF.name,
                relativePath = "problem_bank_documents/other/book.pdf",
                originalName = "other.pdf",
                sizeBytes = 1,
                contentSha256 = "sha-other",
                pageCount = 1,
                createdAtEpochMillis = 2,
                updatedAtEpochMillis = 2,
            ),
        )
        database.problemBankDao().insertProblem(
            ProblemBankEntryEntity(
                documentId = otherDocument,
                pageIndex = 0,
                courseId = null,
                courseName = "高等代数",
                textbook = "高代",
                chapter = "第三章",
                problemNumber = "1",
                statement = "另一册的题目",
                estimatedDifficulty = 1,
                difficultySource = "HEURISTIC",
                estimatedMinutes = 5,
                actualMinutes = null,
                completionStatus = "NEW",
                reviewStatus = "NOT_STARTED",
                learningSuggestion = null,
                suggestionSource = null,
                dependsOn = "",
                importBatchId = null,
                createdAtEpochMillis = 3,
                updatedAtEpochMillis = 3,
            ),
        )

        val page = repository.pageProblems(ProblemQuery(documentId = otherDocument), limit = 50, offset = 0)

        assertEquals(1, page.totalCount)
        assertEquals("高等代数", page.problems.single().courseName)
        assertFalse(page.problems.any { it.documentId == documentId })
    }

    @Test
    fun difficultCandidates_onlyReturnSignalsAndRespectLimit() = runTest {
        seedProblems(count = 2)
        // 慢题：实际用时超过估计 2 倍
        val slowId = database.problemBankDao().insertProblem(
            ProblemBankEntryEntity(
                documentId = documentId,
                pageIndex = 0,
                courseId = null,
                courseName = "数学分析",
                textbook = "数分习题册",
                chapter = "第一章",
                problemNumber = "900",
                statement = "慢题",
                estimatedDifficulty = 4,
                difficultySource = "HEURISTIC",
                estimatedMinutes = 10,
                actualMinutes = 100,
                completionStatus = "COMPLETED",
                reviewStatus = "NOT_STARTED",
                learningSuggestion = null,
                suggestionSource = null,
                dependsOn = "",
                importBatchId = null,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 10,
            ),
        )
        // 未掌握且含待校对公式
        val formulaId = database.problemBankDao().insertProblem(
            ProblemBankEntryEntity(
                documentId = documentId,
                pageIndex = 0,
                courseId = null,
                courseName = "数学分析",
                textbook = "数分习题册",
                chapter = "第一章",
                problemNumber = "901",
                statement = "公式待校对",
                estimatedDifficulty = 3,
                difficultySource = "HEURISTIC",
                estimatedMinutes = 10,
                actualMinutes = null,
                completionStatus = "COMPLETED",
                reviewStatus = "REVIEWING",
                learningSuggestion = null,
                suggestionSource = null,
                dependsOn = "",
                importBatchId = null,
                createdAtEpochMillis = 11,
                updatedAtEpochMillis = 11,
            ),
        )
        database.problemBankDao().insertFormulas(
            listOf(
                ProblemBankFormulaEntity(
                    problemId = formulaId,
                    orderIndex = 0,
                    latex = "x",
                    confidence = 0.5,
                    needsReview = true,
                    originalSnippet = "x",
                ),
            ),
        )

        val candidates = repository.difficultProblemCandidates(limit = 50)
        assertEquals(setOf(slowId, formulaId), candidates.map { it.id }.toSet())

        assertEquals(1, repository.difficultProblemCandidates(limit = 1).size)
    }
}
