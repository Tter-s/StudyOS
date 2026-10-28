package com.zhixu.studyos.feature.schedule

import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.ParsedSchedule
import com.zhixu.studyos.core.importer.ParsedScheduleCourse
import com.zhixu.studyos.core.importer.PeriodGrid
import com.zhixu.studyos.core.importer.ScheduleImportException
import com.zhixu.studyos.core.importer.ScheduleImportField
import com.zhixu.studyos.core.importer.ScheduleImportMethod
import com.zhixu.studyos.core.importer.ScheduleImportRequest
import com.zhixu.studyos.core.importer.ScheduleImportResult
import com.zhixu.studyos.core.importer.ScheduleImportService
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.ScheduleConflict
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleImportViewModelTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), zoneId)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recognize_selectsCleanRowsAndImportsOnlyThose() = runTest {
        val repository = FakeScheduleRepository(snapshot())
        val service = FakeImportService {
            recognition(
                listOf(
                    parsedCourse(name = "数学分析"),
                    parsedCourse(name = "高等代数", dayOfWeek = 2, classroom = ""),
                ),
            )
        }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)

        val preview = viewModel.uiState.value
        assertEquals(ScheduleImportStage.PREVIEW, preview.stage)
        assertEquals(2, preview.drafts.size)
        assertEquals(1, preview.selectedDrafts.size)
        assertTrue(preview.canImport)
        assertFalse(
            viewModel.uiState.value.drafts.first { it.name == "高等代数" }.localId in preview.selectedIds,
        )

        viewModel.importSelected()

        assertEquals(1, repository.imported.size)
        assertEquals("数学分析", repository.imported.single().single().name)
        assertEquals(ScheduleImportStage.IMPORTED, viewModel.uiState.value.stage)
        assertEquals(1, viewModel.uiState.value.importedCount)
    }

    @Test
    fun recognitionFailure_staysOnAdjustAndWritesNothing() = runTest {
        val repository = FakeScheduleRepository(snapshot())
        val service = FakeImportService { throw ScheduleImportException("本地 OCR 失败") }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)

        val state = viewModel.uiState.value
        assertEquals(ScheduleImportStage.ADJUST, state.stage)
        assertTrue(state.errorMessage.orEmpty().contains("OCR"))
        assertTrue(state.drafts.isEmpty())
        assertTrue(repository.imported.isEmpty())
    }

    @Test
    fun fallbackPeriodsBlockImportUntilGridIsConfirmedAndSaved() = runTest {
        val repository = FakeScheduleRepository(snapshot())
        val service = FakeImportService {
            recognition(listOf(parsedCourse()), fallbackPeriods = setOf(1))
        }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)

        assertFalse(viewModel.uiState.value.gridConfirmed)
        assertFalse(viewModel.uiState.value.canImport)

        viewModel.updateGrid(PeriodGrid.commonDefault())

        assertTrue(viewModel.uiState.value.gridConfirmed)
        assertTrue(viewModel.uiState.value.canImport)
    }

    @Test
    fun duplicateRowIsBlockedAndNotSelected() = runTest {
        val existing = existingCourse()
        val repository = FakeScheduleRepository(snapshot(courses = listOf(existing)))
        val service = FakeImportService { recognition(listOf(parsedCourse(name = "数学 分析"))) }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)

        val state = viewModel.uiState.value
        val analysis = state.analysis.values.single()
        assertEquals(
            com.zhixu.studyos.core.importer.ScheduleImportDraftState.DUPLICATE,
            analysis.state,
        )
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.canImport)
    }

    @Test
    fun editingAConflictingDraftRecomputesAndSelectsIt() = runTest {
        val repository = FakeScheduleRepository(snapshot(courses = listOf(existingCourse())))
        val service = FakeImportService { recognition(listOf(parsedCourse(name = "高等代数"))) }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)
        val conflicted = viewModel.uiState.value.drafts.single()
        assertFalse(viewModel.uiState.value.canImport)

        viewModel.updateDraft(
            conflicted.copy(
                periodStart = 3,
                periodEnd = 4,
                needsReview = emptySet(),
                notes = emptyList(),
            ),
        )

        val state = viewModel.uiState.value
        assertEquals(
            com.zhixu.studyos.core.importer.ScheduleImportDraftState.READY,
            state.analysis.getValue(conflicted.localId).state,
        )
        assertTrue(conflicted.localId in state.selectedIds)
        assertTrue(state.canImport)
    }

    @Test
    fun pendingReviewCanBeClearedByEditingThenSelected() = runTest {
        val repository = FakeScheduleRepository(snapshot())
        val service = FakeImportService {
            recognition(
                listOf(parsedCourse(needsReview = setOf(ScheduleImportField.PERIOD))),
            )
        }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)
        val draft = viewModel.uiState.value.drafts.single()
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(viewModel.uiState.value.canImport)

        viewModel.updateDraft(draft.copy(needsReview = emptySet(), notes = emptyList()))

        val state = viewModel.uiState.value
        assertEquals(
            com.zhixu.studyos.core.importer.ScheduleImportDraftState.READY,
            state.analysis.getValue(draft.localId).state,
        )
        assertTrue(draft.localId in state.selectedIds)
        assertTrue(state.canImport)
    }

    @Test
    fun missingSemesterBlocksImportUntilSemesterIsSaved() = runTest {
        val repository = FakeScheduleRepository(snapshot(withSemester = false))
        val service = FakeImportService { recognition(listOf(parsedCourse())) }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)
        assertFalse(viewModel.uiState.value.hasSemester)
        assertFalse(viewModel.uiState.value.canImport)

        var result: String? = "not-called"
        viewModel.saveSemester(LocalDate.of(2026, 9, 7), zoneId) { result = it }

        assertEquals(null, result)
        assertTrue(viewModel.uiState.value.hasSemester)
        assertTrue(viewModel.uiState.value.canImport)
        assertEquals(LocalDate.of(2026, 9, 7), repository.savedSemester?.first)
    }

    @Test
    fun importFailureKeepsPreviewAndDrafts() = runTest {
        val repository = FakeScheduleRepository(snapshot()).apply {
            importFailure = IllegalStateException("数据库写入失败")
        }
        val service = FakeImportService { recognition(listOf(parsedCourse())) }
        val viewModel = ScheduleImportViewModel(repository, service, clock)

        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)
        viewModel.importSelected()

        val state = viewModel.uiState.value
        assertEquals(ScheduleImportStage.PREVIEW, state.stage)
        assertEquals(1, state.drafts.size)
        assertFalse(state.isImporting)
        assertEquals("数据库写入失败", state.errorMessage)
    }

    @Test
    fun resetReturnsToPickStageButKeepsSnapshotAwareness() = runTest {
        val repository = FakeScheduleRepository(snapshot())
        val viewModel = ScheduleImportViewModel(
            repository,
            FakeImportService { recognition(listOf(parsedCourse())) },
            clock,
        )
        viewModel.recognize(byteArrayOf(1), "image/png", 0, NormalizedCrop.Full)

        viewModel.reset()

        val state = viewModel.uiState.value
        assertEquals(ScheduleImportStage.PICK_IMAGE, state.stage)
        assertTrue(state.drafts.isEmpty())
        assertTrue(state.hasSemester)
    }

    private fun snapshot(
        withSemester: Boolean = true,
        courses: List<Course> = emptyList(),
    ) = ScheduleSnapshot(
        semester = if (withSemester) SemesterSettings(LocalDate.of(2026, 9, 7), zoneId) else null,
        courses = courses,
    )

    private fun existingCourse() = Course(
        id = 1,
        name = "数学分析",
        teacher = "王老师",
        classroom = "A101",
        dayOfWeek = 1,
        startMinute = 8 * 60,
        endMinuteExclusive = 9 * 60 + 40,
        startWeek = 1,
        endWeek = 16,
        weekParity = WeekParity.ALL,
        colorArgb = 0,
    )

    private fun parsedCourse(
        name: String = "数学分析",
        dayOfWeek: Int = 1,
        classroom: String = "A101",
        needsReview: Set<ScheduleImportField> = emptySet(),
    ) = ParsedScheduleCourse(
        name = name,
        teacher = "王老师",
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        periodStart = 1,
        periodEnd = 2,
        startWeek = 1,
        endWeek = 16,
        weekParity = WeekParity.ALL,
        needsReview = needsReview,
        notes = emptyList(),
        sourceText = name,
    )

    private fun recognition(
        courses: List<ParsedScheduleCourse>,
        fallbackPeriods: Set<Int> = emptySet(),
        warnings: List<String> = emptyList(),
    ) = ScheduleImportResult(
        method = ScheduleImportMethod.LOCAL_OCR,
        parsed = ParsedSchedule(
            courses = courses,
            grid = PeriodGrid.commonDefault(),
            fallbackPeriods = fallbackPeriods,
            warnings = warnings,
            textLineCount = 10,
            unparsedLineCount = 0,
        ),
        elapsedMillis = 1,
    )

    private class FakeImportService(
        private val result: suspend () -> ScheduleImportResult,
    ) : ScheduleImportService {
        override val method: ScheduleImportMethod = ScheduleImportMethod.LOCAL_OCR
        override val displayName: String = "测试 OCR"

        override suspend fun recognize(request: ScheduleImportRequest): ScheduleImportResult = result()
    }

    private class FakeScheduleRepository(
        initial: ScheduleSnapshot,
    ) : ScheduleRepository {
        private val snapshot = MutableStateFlow(initial)
        val imported = mutableListOf<List<Course>>()
        var importFailure: Throwable? = null
        var savedSemester: Pair<LocalDate, ZoneId>? = null

        override fun observeSnapshot(): Flow<ScheduleSnapshot> = snapshot

        override suspend fun saveSemester(startDate: LocalDate, zoneId: ZoneId) {
            savedSemester = startDate to zoneId
            snapshot.value = snapshot.value.copy(
                semester = SemesterSettings(startDate, zoneId),
            )
        }

        override suspend fun importCourses(courses: List<Course>): List<Long> {
            importFailure?.let { throw it }
            imported += courses
            return courses.indices.map { it + 10L }
        }

        override suspend fun saveCourse(course: Course): List<ScheduleConflict> =
            throw UnsupportedOperationException()

        override suspend fun deleteCourse(courseId: Long) = throw UnsupportedOperationException()

        override suspend fun saveException(exception: CourseException): List<ScheduleConflict> =
            throw UnsupportedOperationException()

        override suspend fun deleteException(exceptionId: Long) = throw UnsupportedOperationException()

        override suspend fun saveEvent(event: OneOffEvent): List<ScheduleConflict> =
            throw UnsupportedOperationException()

        override suspend fun deleteEvent(eventId: Long) = throw UnsupportedOperationException()

        override suspend fun saveRoutine(routine: RoutineBlock) = throw UnsupportedOperationException()

        override suspend fun deleteRoutine(routineId: Long) = throw UnsupportedOperationException()
    }
}
