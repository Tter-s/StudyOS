package com.zhixu.studyos.feature.bank

import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.data.repository.ProblemConfirmResult
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentPage
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemDraft
import com.zhixu.studyos.core.model.ProblemImportBatch
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItem
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import com.zhixu.studyos.core.model.ProblemPageTextSource
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSaveResult
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProblemBankViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun list_showsProblemsWithCounts() = runTest {
        val repository = FakeProblemBankRepository().apply {
            seed(problem(number = "1"))
            seed(problem(number = "2", completion = ProblemCompletionStatus.COMPLETED))
            seedDocument(document(id = 1L))
        }
        val viewModel = ProblemBankViewModel(repository, FakeTaskRepository(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.problemCount)
        assertEquals(1, state.completedCount)
        assertEquals(1, state.documents.size)
        assertEquals(2, state.documentProblemCounts[1L])
    }

    @Test
    fun filters_narrowTheList() = runTest {
        val repository = FakeProblemBankRepository().apply {
            seed(problem(number = "1", chapter = "第一章"))
            seed(problem(number = "2", chapter = "第二章"))
        }
        val viewModel = ProblemBankViewModel(repository, FakeTaskRepository(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setFilterChapter("第二章")
        advanceUntilIdle()

        assertEquals(listOf("2"), viewModel.uiState.value.problems.map { it.problemNumber })
    }

    @Test
    fun taskPlan_createsBacklogTasksForSelectedProblems() = runTest {
        val repository = FakeProblemBankRepository().apply {
            seed(problem(number = "1"))
            seed(problem(number = "2"))
            seed(problem(number = "3"))
        }
        val taskRepository = FakeTaskRepository()
        val viewModel = ProblemBankViewModel(repository, taskRepository, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.prepareTaskPlan(problemsPerDay = 2, startDate = LocalDate.of(2026, 10, 26))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.taskPlan?.dayCount)

        viewModel.confirmGenerateTasks()
        advanceUntilIdle()

        assertEquals(2, taskRepository.saved.size)
        assertTrue(taskRepository.saved.all { it.status == com.zhixu.studyos.core.model.TaskStatus.BACKLOG })
        assertTrue(taskRepository.saved.all { it.dueDate != null })
        assertEquals("数学分析", taskRepository.saved.first().subject)
    }

    @Test
    fun deleteProblem_removesFromRepository() = runTest {
        val repository = FakeProblemBankRepository().apply { seed(problem(number = "1")) }
        val viewModel = ProblemBankViewModel(repository, FakeTaskRepository(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteProblem(1L)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.problemCount)
    }

    @Test
    fun loadMore_appendsNextPageAndStopsAtTotal() = runTest {
        val repository = FakeProblemBankRepository().apply {
            (1..120).forEach { seed(problem(number = it.toString())) }
        }
        val viewModel = ProblemBankViewModel(repository, FakeTaskRepository(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(ProblemBankViewModel.PAGE_SIZE, viewModel.uiState.value.loadedCount)
        assertEquals(120, viewModel.uiState.value.problemCount)
        assertTrue(viewModel.uiState.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(ProblemBankViewModel.PAGE_SIZE * 2, viewModel.uiState.value.loadedCount)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(120, viewModel.uiState.value.loadedCount)
        assertEquals(false, viewModel.uiState.value.hasMore)
    }

    @Test
    fun filter_reloadsFirstPage() = runTest {
        val repository = FakeProblemBankRepository().apply {
            seed(problem(number = "1", chapter = "第一章"))
            seed(problem(number = "2", chapter = "第二章"))
        }
        val viewModel = ProblemBankViewModel(repository, FakeTaskRepository(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setFilterChapter("第二章")
        advanceUntilIdle()

        assertEquals(listOf("2"), viewModel.uiState.value.problems.map { it.problemNumber })
        assertEquals(1, viewModel.uiState.value.problemCount)
    }

    private fun problem(
        number: String,
        chapter: String = "第一章",
        completion: ProblemCompletionStatus = ProblemCompletionStatus.NEW,
    ) = ProblemBankEntry(
        id = number.toLong(),
        documentId = 1L,
        pageIndex = 0,
        courseId = null,
        courseName = "数学分析",
        textbook = "数学分析习题册",
        chapter = chapter,
        problemNumber = number,
        statement = "题干 $number",
        estimatedMinutes = 10,
        completionStatus = completion,
    )

    private fun document(id: Long) = ProblemDocument(
        id = id,
        displayName = "习题册",
        type = ProblemDocumentType.PDF,
        relativePath = "problem_bank_documents/x.pdf",
        originalName = "x.pdf",
        sizeBytes = 1,
        contentSha256 = "sha",
        pageCount = 1,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
}

private class FakeProblemBankRepository : ProblemBankRepository {
    val problemsFlow = MutableStateFlow<List<ProblemBankEntry>>(emptyList())
    val documentsFlow = MutableStateFlow<List<ProblemDocument>>(emptyList())

    fun seed(problem: ProblemBankEntry) {
        problemsFlow.value = problemsFlow.value + problem
    }

    fun seedDocument(document: ProblemDocument) {
        documentsFlow.value = documentsFlow.value + document
    }

    override fun observeDocuments(): Flow<List<ProblemDocument>> = documentsFlow
    override fun observeProblems(): Flow<List<ProblemBankEntry>> = problemsFlow

    override suspend fun pageProblems(
        query: com.zhixu.studyos.core.data.repository.ProblemQuery,
        limit: Int,
        offset: Int,
    ): com.zhixu.studyos.core.data.repository.ProblemPage {
        val filtered = filtered(query)
        return com.zhixu.studyos.core.data.repository.ProblemPage(
            problems = filtered.drop(offset).take(limit),
            totalCount = filtered.size,
            completedCount = filtered.count { it.completionStatus == ProblemCompletionStatus.COMPLETED },
        )
    }

    override suspend fun problemsForSelection(
        query: com.zhixu.studyos.core.data.repository.ProblemQuery,
        limit: Int,
    ): List<ProblemBankEntry> = filtered(query).take(limit)

    override suspend fun distinctChapters(): List<String> =
        problemsFlow.value.map { it.chapter }.filter { it.isNotBlank() }.distinct().sorted()

    override suspend fun difficultProblemCandidates(limit: Int): List<ProblemBankEntry> =
        problemsFlow.value.take(limit)

    private fun filtered(query: com.zhixu.studyos.core.data.repository.ProblemQuery): List<ProblemBankEntry> =
        problemsFlow.value.filter { problem ->
            (query.documentId == null || problem.documentId == query.documentId) &&
                (query.chapter == null || problem.chapter == query.chapter) &&
                (query.completionStatus == null || problem.completionStatus == query.completionStatus) &&
                (!query.onlyUnfinished || problem.completionStatus != ProblemCompletionStatus.COMPLETED) &&
                (query.maxDifficulty == null ||
                    (problem.estimatedDifficulty ?: 0) <= query.maxDifficulty) &&
                (query.searchText.isBlank() ||
                    problem.statement.contains(query.searchText, ignoreCase = true) ||
                    problem.problemNumber.contains(query.searchText, ignoreCase = true)) &&
                (!query.onlyFormulaNeedsReview || problem.hasFormulaNeedingReview)
        }
    override suspend fun getProblem(id: Long): ProblemBankEntry? = problemsFlow.value.firstOrNull { it.id == id }
    override suspend fun getDocument(id: Long): ProblemDocument? = documentsFlow.value.firstOrNull { it.id == id }
    override suspend fun findDocumentBySha256(sha256: String): ProblemDocument? = null

    override suspend fun createDocument(
        displayName: String,
        relativePath: String,
        originalName: String,
        sizeBytes: Long,
        contentSha256: String,
        pageCount: Int,
        type: ProblemDocumentType,
    ): Long = 1L

    override suspend fun upsertPageText(
        documentId: Long,
        pageIndex: Int,
        text: String,
        source: ProblemPageTextSource,
    ) = Unit

    override suspend fun createBatch(documentId: Long, totalPages: Int): Long = 1L
    override suspend fun findResumableBatch(documentId: Long): ProblemImportBatch? = null
    override suspend fun findLatestBatch(documentId: Long): ProblemImportBatch? = null
    override suspend fun getBatch(batchId: Long): ProblemImportBatch? = null
    override suspend fun getBatchItems(batchId: Long): List<ProblemImportItem> = emptyList()
    override suspend fun updateBatchStatus(batchId: Long, status: ProblemImportBatchStatus) = Unit

    override suspend fun confirmDrafts(drafts: List<ProblemDraft>): ProblemConfirmResult =
        ProblemConfirmResult(drafts.size, 0)

    override suspend fun updateProblem(entry: ProblemBankEntry) {
        problemsFlow.value = problemsFlow.value.map { if (it.id == entry.id) entry else it }
    }

    override suspend fun deleteProblem(problemId: Long) {
        problemsFlow.value = problemsFlow.value.filterNot { it.id == problemId }
    }

    override suspend fun deleteDocument(documentId: Long) = Unit
    override suspend fun existingKeys(documentId: Long): Set<String> = emptySet()
    override suspend fun suspectedDuplicateKeys(textbook: String, excludeDocumentId: Long): Set<String> = emptySet()
    override suspend fun getPageText(documentId: Long, pageIndex: Int): ProblemDocumentPage? = null
    override suspend fun problemsByIds(ids: List<Long>): List<ProblemBankEntry> = emptyList()
    override suspend fun documentProblemCounts(): Map<Long, Int> =
        problemsFlow.value.groupingBy { it.documentId }.eachCount()
}

private class FakeTaskRepository : TaskRepository {
    val saved = mutableListOf<StudyTask>()

    override fun observeAllTasks(): Flow<List<StudyTask>> = MutableStateFlow(emptyList())
    override suspend fun saveTask(task: StudyTask): TaskSaveResult {
        saved += task
        return TaskSaveResult(listOf(saved.size.toLong()))
    }

    override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> = MutableStateFlow(emptyList())
    override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) = Unit
    override suspend fun deleteTask(taskId: Long) = Unit
    override suspend fun deleteSeries(seriesId: String) = Unit
    override suspend fun startTask(taskId: Long) = Unit
    override suspend fun pauseTask(taskId: Long) = Unit
    override suspend fun postponeTask(taskId: Long, days: Long): StudyTask = throw UnsupportedOperationException()
    override suspend fun reorderTasks(orderedTaskIds: List<Long>) = Unit
    override suspend fun setSessionLocked(taskId: Long, sessionId: Long, locked: Boolean) = Unit
    override suspend fun getTask(taskId: Long): StudyTask? = null
}
