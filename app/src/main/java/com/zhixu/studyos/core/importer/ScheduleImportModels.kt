package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.WeekParity

const val MAX_IMPORT_WEEK = 30
const val MAX_IMPORT_PERIOD = 12

/** How an import candidate was produced. Only local OCR is enabled in V0.7. */
enum class ScheduleImportMethod {
    LOCAL_OCR,
    CLOUD_VISION,
}

enum class ScheduleImportField {
    NAME,
    CLASSROOM,
    DAY,
    PERIOD,
    WEEKS,
}

/**
 * Crop expressed in 0..1 coordinates of the image after rotation is applied.
 * Keeping the crop normalized lets the preview and the recognizer work on
 * different bitmap sizes and still agree on the same region.
 */
data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isValid: Boolean
        get() = left in 0f..1f &&
            top in 0f..1f &&
            right in 0f..1f &&
            bottom in 0f..1f &&
            right - left >= 0.02f &&
            bottom - top >= 0.02f

    fun clamped(): NormalizedCrop {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        return if (safeRight - safeLeft < 0.02f || safeBottom - safeTop < 0.02f) {
            Full
        } else {
            NormalizedCrop(safeLeft, safeTop, safeRight, safeBottom)
        }
    }

    companion object {
        val Full = NormalizedCrop()
    }
}

/** One OCR text line with coordinates normalized to the processed image. */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun normalizedOrNull(): OcrLine? {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        if (safeRight <= safeLeft || safeBottom <= safeTop) return null
        return copy(left = safeLeft, top = safeTop, right = safeRight, bottom = safeBottom)
    }
}

data class OcrDocument(val lines: List<OcrLine>)

data class PeriodSlot(
    val number: Int,
    val startMinute: Int,
    val endMinuteExclusive: Int,
)

/**
 * Period number to clock-time mapping. Screenshots usually label periods as
 * "1, 2, ..." but rarely include a complete clock; when OCR cannot read a
 * period time the user must review and confirm this table before importing.
 */
data class PeriodGrid(val slots: List<PeriodSlot>) {
    val size: Int get() = slots.size

    fun slot(number: Int): PeriodSlot? = slots.firstOrNull { it.number == number }

    fun range(startPeriod: Int, endPeriod: Int): Pair<Int, Int>? {
        if (startPeriod < 1 || endPeriod < startPeriod) return null
        val start = slot(startPeriod) ?: return null
        val end = slot(endPeriod) ?: return null
        if ((startPeriod..endPeriod).any { slot(it) == null }) return null
        return start.startMinute to end.endMinuteExclusive
    }

    fun withSlot(slot: PeriodSlot): PeriodGrid = PeriodGrid(
        slots.map { if (it.number == slot.number) slot else it },
    )

    fun validationError(): String? {
        if (slots.isEmpty()) return "节次时间不能为空"
        val ordered = slots.sortedBy(PeriodSlot::number)
        if (ordered.map(PeriodSlot::number) != (1..ordered.size).toList()) {
            return "节次编号必须从第 1 节开始连续"
        }
        ordered.forEach { slot ->
            if (slot.startMinute !in 0 until MINUTES_PER_DAY) {
                return "第 ${slot.number} 节开始时间必须在当天 00:00–23:59 内"
            }
            if (slot.endMinuteExclusive <= slot.startMinute || slot.endMinuteExclusive > MINUTES_PER_DAY) {
                return "第 ${slot.number} 节结束时间必须晚于开始时间且不跨日"
            }
            if (slot.endMinuteExclusive - slot.startMinute < 5) {
                return "第 ${slot.number} 节时长过短"
            }
        }
        for (index in 1 until ordered.size) {
            if (ordered[index].startMinute < ordered[index - 1].endMinuteExclusive) {
                return "第 ${ordered[index].number} 节开始时间早于上一节结束时间"
            }
        }
        return null
    }

    companion object {
        /**
         * A clearly labelled fallback used only when the screenshot has no
         * readable clock labels. It is never applied silently: the UI requires
         * an explicit confirmation of this table before import is enabled.
         */
        fun commonDefault(): PeriodGrid = PeriodGrid(
            listOf(
                PeriodSlot(1, 8 * 60, 8 * 60 + 45),
                PeriodSlot(2, 8 * 60 + 55, 9 * 60 + 40),
                PeriodSlot(3, 10 * 60, 10 * 60 + 45),
                PeriodSlot(4, 10 * 60 + 55, 11 * 60 + 40),
                PeriodSlot(5, 14 * 60, 14 * 60 + 45),
                PeriodSlot(6, 14 * 60 + 55, 15 * 60 + 40),
                PeriodSlot(7, 16 * 60, 16 * 60 + 45),
                PeriodSlot(8, 16 * 60 + 55, 17 * 60 + 40),
                PeriodSlot(9, 19 * 60, 19 * 60 + 45),
                PeriodSlot(10, 19 * 60 + 55, 20 * 60 + 40),
                PeriodSlot(11, 20 * 60 + 50, 21 * 60 + 35),
                PeriodSlot(12, 21 * 60 + 45, 22 * 60 + 30),
            ),
        )
    }
}

data class ScheduleParseOptions(
    val fallbackGrid: PeriodGrid = PeriodGrid.commonDefault(),
    val maxWeek: Int = MAX_IMPORT_WEEK,
)

/** One course candidate found in a screenshot, before the user edits it. */
data class ParsedScheduleCourse(
    val name: String,
    val teacher: String?,
    val classroom: String,
    val dayOfWeek: Int?,
    val periodStart: Int?,
    val periodEnd: Int?,
    val startWeek: Int?,
    val endWeek: Int?,
    val weekParity: WeekParity,
    val needsReview: Set<ScheduleImportField>,
    val notes: List<String>,
    val sourceText: String,
)

data class ParsedSchedule(
    val courses: List<ParsedScheduleCourse>,
    val grid: PeriodGrid,
    /** Periods whose clock time was not read from the image and still use the fallback. */
    val fallbackPeriods: Set<Int>,
    val warnings: List<String>,
    val textLineCount: Int,
    val unparsedLineCount: Int,
)

data class ScheduleImportRequest(
    val imageBytes: ByteArray,
    val mimeType: String,
    /** Total clockwise rotation applied to the raw decoded image, including EXIF. */
    val rotationQuarterTurns: Int = 0,
    val crop: NormalizedCrop = NormalizedCrop.Full,
    val fallbackGrid: PeriodGrid = PeriodGrid.commonDefault(),
    val maxWeek: Int = MAX_IMPORT_WEEK,
)

data class ScheduleImportResult(
    val method: ScheduleImportMethod,
    val parsed: ParsedSchedule,
    val elapsedMillis: Long,
)

/**
 * The single seam between the import flow and a recognition provider. A local
 * OCR implementation is shipped in V0.7; a future cloud vision provider can be
 * added without touching parsing, validation, preview or persistence.
 */
interface ScheduleImportService {
    val method: ScheduleImportMethod
    val displayName: String

    suspend fun recognize(request: ScheduleImportRequest): ScheduleImportResult
}

class ScheduleImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Editable preview row. Missing values stay null instead of being invented. */
data class ScheduleImportDraft(
    val localId: String,
    val name: String,
    val teacher: String? = null,
    val classroom: String = "",
    val dayOfWeek: Int? = null,
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val startWeek: Int? = null,
    val endWeek: Int? = null,
    val weekParity: WeekParity = WeekParity.ALL,
    val colorArgb: Int = 0,
    val needsReview: Set<ScheduleImportField> = emptySet(),
    val notes: List<String> = emptyList(),
    val sourceText: String = "",
)

data class CourseScheduleOverlap(
    val otherName: String,
    val sameName: Boolean,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val weeks: Set<Int>,
)

enum class ScheduleImportDraftState {
    READY,
    NEEDS_REVIEW,
    INCOMPLETE,
    DUPLICATE,
    CONFLICT,
}

data class ScheduleImportDraftAnalysis(
    val localId: String,
    val validationError: String?,
    val needsReview: Set<ScheduleImportField>,
    val duplicates: List<CourseScheduleOverlap>,
    val conflicts: List<CourseScheduleOverlap>,
) {
    val state: ScheduleImportDraftState
        get() = when {
            validationError != null -> ScheduleImportDraftState.INCOMPLETE
            duplicates.isNotEmpty() -> ScheduleImportDraftState.DUPLICATE
            conflicts.isNotEmpty() -> ScheduleImportDraftState.CONFLICT
            needsReview.isNotEmpty() -> ScheduleImportDraftState.NEEDS_REVIEW
            else -> ScheduleImportDraftState.READY
        }

    val isSelectable: Boolean get() = state == ScheduleImportDraftState.READY
}

data class RejectedImportCourse(
    val courseName: String,
    val overlaps: List<CourseScheduleOverlap>,
)

class ScheduleImportConflictException(
    val rejected: List<RejectedImportCourse>,
) : IllegalStateException(
    rejected.firstOrNull()?.let { first ->
        val overlap = first.overlaps.first()
        "未写入任何课程：「${first.courseName}」与「${overlap.otherName}」" +
            "在${ScheduleImportFormat.weekDay(overlap.dayOfWeek)} " +
            "${ScheduleImportFormat.clock(overlap.startMinute)}–" +
            "${ScheduleImportFormat.clock(overlap.endMinuteExclusive)} 的第 " +
            "${ScheduleImportFormat.weeks(overlap.weeks)} 周重叠"
    } ?: "未写入任何课程：检测到时间冲突",
)
