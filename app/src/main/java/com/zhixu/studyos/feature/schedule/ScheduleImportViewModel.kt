package com.zhixu.studyos.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.PeriodGrid
import com.zhixu.studyos.core.importer.ScheduleImportDraft
import com.zhixu.studyos.core.importer.ScheduleImportDraftAnalysis
import com.zhixu.studyos.core.importer.ScheduleImportRequest
import com.zhixu.studyos.core.importer.ScheduleImportService
import com.zhixu.studyos.core.importer.ScheduleImportValidation
import com.zhixu.studyos.core.importer.ScheduleImportAnalyzer
import com.zhixu.studyos.core.importer.ScheduleImportConflictException
import com.zhixu.studyos.core.importer.ScheduleImportException
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.ScheduleSnapshot
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ScheduleImportStage {
    PICK_IMAGE,
    ADJUST,
    RECOGNIZING,
    PREVIEW,
    IMPORTED,
}

data class ScheduleImportUiState(
    val stage: ScheduleImportStage = ScheduleImportStage.PICK_IMAGE,
    val drafts: List<ScheduleImportDraft> = emptyList(),
    val analysis: Map<String, ScheduleImportDraftAnalysis> = emptyMap(),
    val selectedIds: Set<String> = emptySet(),
    val grid: PeriodGrid = PeriodGrid.commonDefault(),
    val gridConfirmed: Boolean = true,
    val fallbackPeriods: Set<Int> = emptySet(),
    val warnings: List<String> = emptyList(),
    val hasSemester: Boolean = false,
    val isSnapshotAvailable: Boolean = true,
    val snapshotError: String? = null,
    val isRecognizing: Boolean = false,
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val recognizedCount: Int = 0,
    val importedCount: Int = 0,
    val elapsedMillis: Long? = null,
    val methodLabel: String? = null,
) {
    val selectedDrafts: List<ScheduleImportDraft>
        get() = drafts.filter { draft ->
            draft.localId in selectedIds && analysis[draft.localId]?.isSelectable == true
        }

    val canImport: Boolean
        get() = stage == ScheduleImportStage.PREVIEW &&
            selectedDrafts.isNotEmpty() &&
            hasSemester &&
            gridConfirmed &&
            isSnapshotAvailable &&
            !isRecognizing &&
            !isImporting

    val needsGridConfirmation: Boolean
        get() = stage == ScheduleImportStage.PREVIEW && !gridConfirmed

    val needsSemester: Boolean
        get() = stage == ScheduleImportStage.PREVIEW && isSnapshotAvailable && !hasSemester
}

private data class ImportSnapshotLoad(
    val snapshot: ScheduleSnapshot? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleImportViewModel(
    private val repository: ScheduleRepository,
    private val importService: ScheduleImportService,
    private val clock: Clock,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScheduleImportUiState())
    val uiState: StateFlow<ScheduleImportUiState> = _uiState.asStateFlow()

    private val refresh = MutableStateFlow(0)
    private var existingCourses: List<Course> = emptyList()

    init {
        viewModelScope.launch {
            refresh.flatMapLatest {
                repository.observeSnapshot()
                    .map { snapshot -> ImportSnapshotLoad(snapshot = snapshot) }
                    .catch {
                        emit(ImportSnapshotLoad(error = "无法读取现有课表，导入已暂停。请重试。"))
                    }
            }.collect { load ->
                val snapshot = load.snapshot
                if (snapshot == null) {
                    existingCourses = emptyList()
                    _uiState.update {
                        it.copy(
                            isSnapshotAvailable = false,
                            hasSemester = false,
                            snapshotError = load.error,
                        )
                    }
                } else {
                    existingCourses = snapshot.courses
                    _uiState.update {
                        it.copy(
                            isSnapshotAvailable = true,
                            hasSemester = snapshot.semester != null,
                            snapshotError = null,
                        )
                    }
                }
                recomputeAnalysis()
            }
        }
    }

    fun retrySnapshot() {
        _uiState.update { it.copy(snapshotError = null, isSnapshotAvailable = true) }
        refresh.value += 1
    }

    fun currentDate(): LocalDate = LocalDate.now(clock)

    val fallbackZoneId: ZoneId get() = clock.zone

    fun saveSemester(
        startDate: LocalDate,
        zoneId: ZoneId,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching { repository.saveSemester(startDate, zoneId) }
            if (!isActive) return@launch
            result.onSuccess { onResult(null) }
                .onFailure { error -> onResult(error.userMessage("学期设置保存失败")) }
        }
    }

    fun startNewImage() {
        _uiState.update {
            it.copy(
                stage = ScheduleImportStage.ADJUST,
                drafts = emptyList(),
                analysis = emptyMap(),
                selectedIds = emptySet(),
                grid = PeriodGrid.commonDefault(),
                gridConfirmed = true,
                fallbackPeriods = emptySet(),
                warnings = emptyList(),
                errorMessage = null,
                recognizedCount = 0,
                importedCount = 0,
                elapsedMillis = null,
                methodLabel = null,
            )
        }
    }

    fun backToAdjust() {
        if (_uiState.value.isRecognizing || _uiState.value.isImporting) return
        _uiState.update { it.copy(stage = ScheduleImportStage.ADJUST, errorMessage = null) }
    }

    fun recognize(
        imageBytes: ByteArray,
        mimeType: String,
        rotationQuarterTurns: Int,
        crop: NormalizedCrop,
    ) {
        if (_uiState.value.isRecognizing) return
        _uiState.update {
            it.copy(
                stage = ScheduleImportStage.RECOGNIZING,
                isRecognizing = true,
                errorMessage = null,
                drafts = emptyList(),
                analysis = emptyMap(),
                selectedIds = emptySet(),
                warnings = emptyList(),
                fallbackPeriods = emptySet(),
                recognizedCount = 0,
                importedCount = 0,
                elapsedMillis = null,
                methodLabel = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                importService.recognize(
                    ScheduleImportRequest(
                        imageBytes = imageBytes,
                        mimeType = mimeType,
                        rotationQuarterTurns = rotationQuarterTurns,
                        crop = crop,
                        fallbackGrid = PeriodGrid.commonDefault(),
                    ),
                )
            }
            if (!isActive) return@launch
            result.onSuccess { recognition ->
                val parsed = recognition.parsed
                if (parsed.courses.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            stage = ScheduleImportStage.ADJUST,
                            isRecognizing = false,
                            warnings = parsed.warnings,
                            errorMessage = parsed.warnings.firstOrNull()
                                ?: "未识别到课程，请调整裁剪或换一张截图。",
                            elapsedMillis = recognition.elapsedMillis,
                            methodLabel = importService.displayName,
                        )
                    }
                    return@onSuccess
                }
                val drafts = parsed.courses.mapIndexed { index, course ->
                    ScheduleImportDraft(
                        localId = UUID.randomUUID().toString(),
                        name = course.name,
                        teacher = course.teacher,
                        classroom = course.classroom,
                        dayOfWeek = course.dayOfWeek,
                        periodStart = course.periodStart,
                        periodEnd = course.periodEnd,
                        startWeek = course.startWeek,
                        endWeek = course.endWeek,
                        weekParity = course.weekParity,
                        colorArgb = courseColorPalette[index % courseColorPalette.size],
                        needsReview = course.needsReview,
                        notes = course.notes,
                        sourceText = course.sourceText,
                    )
                }
                val analysis = ScheduleImportAnalyzer.analyze(drafts, parsed.grid, existingCourses)
                _uiState.update {
                    it.copy(
                        stage = ScheduleImportStage.PREVIEW,
                        drafts = drafts,
                        analysis = analysis,
                        selectedIds = analysis.filterValues { row -> row.isSelectable }.keys,
                        grid = parsed.grid,
                        gridConfirmed = parsed.fallbackPeriods.isEmpty(),
                        fallbackPeriods = parsed.fallbackPeriods,
                        warnings = parsed.warnings,
                        isRecognizing = false,
                        errorMessage = null,
                        recognizedCount = drafts.size,
                        elapsedMillis = recognition.elapsedMillis,
                        methodLabel = importService.displayName,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        stage = ScheduleImportStage.ADJUST,
                        isRecognizing = false,
                        errorMessage = error.userMessage("识别失败，原课表未做任何修改。"),
                    )
                }
            }
        }
    }

    fun updateGrid(grid: PeriodGrid) {
        val validation = grid.validationError()
        if (validation != null) {
            _uiState.update { it.copy(errorMessage = validation) }
            return
        }
        _uiState.update {
            it.copy(
                grid = grid,
                gridConfirmed = true,
                errorMessage = null,
            )
        }
        recomputeAnalysis()
    }

    fun updateDraft(draft: ScheduleImportDraft) {
        _uiState.update { state ->
            state.copy(
                drafts = state.drafts.map { if (it.localId == draft.localId) draft else it },
                errorMessage = null,
            )
        }
        recomputeAnalysis(autoIncludeIds = setOf(draft.localId))
    }

    fun toggleSelected(localId: String, selected: Boolean) {
        val state = _uiState.value
        val analysis = state.analysis[localId] ?: return
        if (selected && !analysis.isSelectable) return
        _uiState.update { current ->
            current.copy(
                selectedIds = if (selected) {
                    current.selectedIds + localId
                } else {
                    current.selectedIds - localId
                },
            )
        }
    }

    fun importSelected() {
        val state = _uiState.value
        if (state.isImporting) return
        val error = when {
            !state.isSnapshotAvailable -> "无法读取现有课表，导入已暂停。"
            !state.hasSemester -> "请先设置学期后再导入课程。"
            !state.gridConfirmed -> "请先核对并确认节次时间表。"
            state.selectedDrafts.isEmpty() -> "请至少选择一门可导入的课程。"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error) }
            return
        }
        val courses = state.selectedDrafts.mapNotNull { draft ->
            ScheduleImportValidation.toCourse(draft, state.grid)
        }
        if (courses.size != state.selectedDrafts.size) {
            _uiState.update { it.copy(errorMessage = "部分课程字段不完整，请先修改或取消选择。") }
            return
        }

        _uiState.update { it.copy(isImporting = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching { repository.importCourses(courses) }
            if (!isActive) return@launch
            result.onSuccess { ids ->
                    _uiState.update {
                        it.copy(
                            stage = ScheduleImportStage.IMPORTED,
                            isImporting = false,
                            importedCount = ids.size,
                            drafts = emptyList(),
                            analysis = emptyMap(),
                            selectedIds = emptySet(),
                            warnings = emptyList(),
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { failure ->
                    val message = when (failure) {
                        is ScheduleImportConflictException -> failure.message
                        is ScheduleImportException -> failure.message
                        else -> failure.message?.takeIf(String::isNotBlank)
                            ?: "导入失败，原课表未做任何修改。"
                    }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = message,
                        )
                    }
                }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reset() {
        _uiState.update {
            ScheduleImportUiState(
                hasSemester = it.hasSemester,
                isSnapshotAvailable = it.isSnapshotAvailable,
                snapshotError = it.snapshotError,
            )
        }
    }

    private fun recomputeAnalysis(autoIncludeIds: Set<String> = emptySet()) {
        val state = _uiState.value
        if (state.drafts.isEmpty()) return
        val analysis = ScheduleImportAnalyzer.analyze(state.drafts, state.grid, existingCourses)
        val selectable = analysis.filterValues { it.isSelectable }.keys
        val selected = (state.selectedIds intersect selectable) + (autoIncludeIds intersect selectable)
        _uiState.update {
            it.copy(analysis = analysis, selectedIds = selected)
        }
    }

    private fun Throwable.userMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback
}
