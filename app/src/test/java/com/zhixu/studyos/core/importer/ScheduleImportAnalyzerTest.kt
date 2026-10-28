package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.WeekParity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleImportAnalyzerTest {
    private val grid = PeriodGrid.commonDefault()

    @Test
    fun validationRequiresAllRequiredFields() {
        val draft = draft(name = "", classroom = "", dayOfWeek = null, periodStart = null, periodEnd = null)

        val error = ScheduleImportValidation.validationError(draft, grid)

        assertNotNull(error)
        assertFalse(ScheduleImportDraftAnalysis(
            localId = draft.localId,
            validationError = error,
            needsReview = draft.needsReview,
            duplicates = emptyList(),
            conflicts = emptyList(),
        ).isSelectable)
    }

    @Test
    fun validationConvertsPeriodsToClockRange() {
        val draft = draft(periodStart = 1, periodEnd = 2)
        assertEquals(8 * 60 to 9 * 60 + 40, ScheduleImportValidation.timeRange(draft, grid))
        val course = ScheduleImportValidation.toCourse(draft, grid)
        assertNotNull(course)
        assertEquals(480, course!!.startMinute)
        assertEquals(580, course.endMinuteExclusive)
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
    }

    @Test
    fun duplicateSameNameOverlappingWeeksIsMarked() {
        val existing = course(name = "数学分析")
        val draft = draft(name = "数学分析")
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.DUPLICATE, analysis.state)
        assertEquals(1, analysis.duplicates.size)
        assertEquals("数学分析", analysis.duplicates.single().otherName)
        assertTrue(analysis.duplicates.single().weeks.contains(8))
        assertFalse(analysis.isSelectable)
    }

    @Test
    fun duplicateNameIgnoresWhitespace() {
        val existing = course(name = "数学分析")
        val draft = draft(name = "数学 分析")
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.DUPLICATE, analysis.state)
    }

    @Test
    fun oddAndEvenWeeksDoNotConflict() {
        val existing = course(name = "数学分析", parity = WeekParity.ODD)
        val draft = draft(name = "数学分析", parity = WeekParity.EVEN)
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.READY, analysis.state)
        assertTrue(analysis.duplicates.isEmpty())
        assertTrue(analysis.conflicts.isEmpty())
    }

    @Test
    fun disjointWeekRangesDoNotConflict() {
        val existing = course(name = "数学分析", startWeek = 1, endWeek = 8)
        val draft = draft(name = "高等代数", startWeek = 10, endWeek = 16)
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.READY, analysis.state)
    }

    @Test
    fun partialWeekOverlapIsConflictAndReportsOverlapWeeks() {
        val existing = course(name = "数学分析", startWeek = 1, endWeek = 8)
        val draft = draft(name = "高等代数", startWeek = 5, endWeek = 12)
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.CONFLICT, analysis.state)
        val conflict = analysis.conflicts.single()
        assertEquals(setOf(5, 6, 7, 8), conflict.weeks)
        assertEquals(1, conflict.dayOfWeek)
        assertEquals(480, conflict.startMinute)
        assertEquals(580, conflict.endMinuteExclusive)
    }

    @Test
    fun crossMidnightCourseConflictsOnNextDay() {
        val existing = course(
            name = "夜间实验",
            dayOfWeek = 1,
            startMinute = 23 * 60,
            endMinuteExclusive = 25 * 60,
        )
        val candidate = course(
            name = "数学分析",
            dayOfWeek = 2,
            startMinute = 30,
            endMinuteExclusive = 90,
        )

        val conflict = ScheduleImportAnalyzer.findOverlaps(candidate, listOf(existing)).single()

        assertEquals(2, conflict.dayOfWeek)
        assertEquals(30, conflict.startMinute)
        assertEquals(60, conflict.endMinuteExclusive)
    }

    @Test
    fun sundayMidnightCarryEntersNextTeachingWeek() {
        val existing = course(
            name = "周日晚课",
            dayOfWeek = 7,
            startMinute = 23 * 60,
            endMinuteExclusive = 25 * 60,
            startWeek = 1,
            endWeek = 1,
        )
        val mondayNextWeek = course(
            name = "周一早课",
            dayOfWeek = 1,
            startMinute = 30,
            endMinuteExclusive = 60,
            startWeek = 2,
            endWeek = 2,
        )
        val mondaySameWeek = mondayNextWeek.copy(startWeek = 1, endWeek = 1)

        // Sunday week 1 continues into Monday week 2, so this is a real overlap.
        assertEquals(1, ScheduleImportAnalyzer.findOverlaps(mondayNextWeek, listOf(existing)).size)
        // Monday of week 1 is a different teaching week than the Sunday carry.
        assertTrue(ScheduleImportAnalyzer.findOverlaps(mondaySameWeek, listOf(existing)).isEmpty())
    }

    @Test
    fun sameNameWithoutOverlapIsNeitherDuplicateNorConflict() {
        val existing = course(name = "数学分析", startMinute = 8 * 60, endMinuteExclusive = 9 * 60)
        val draft = draft(name = "数学分析", periodStart = 3, periodEnd = 3)
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, listOf(existing)).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.READY, analysis.state)
    }

    @Test
    fun conflictingDraftsInsideOneBatchAreBothBlocked() {
        val first = draft(localId = "first", name = "数学分析")
        val second = draft(localId = "second", name = "高等代数")
        val analysis = ScheduleImportAnalyzer.analyze(listOf(first, second), grid, emptyList())

        assertEquals(ScheduleImportDraftState.CONFLICT, analysis.getValue("first").state)
        assertEquals(ScheduleImportDraftState.CONFLICT, analysis.getValue("second").state)
    }

    @Test
    fun pendingReviewKeepsRowUnselectableEvenWhenFieldsAreValid() {
        val draft = draft(needsReview = setOf(ScheduleImportField.PERIOD))
        val analysis = ScheduleImportAnalyzer.analyze(listOf(draft), grid, emptyList()).getValue(draft.localId)

        assertEquals(ScheduleImportDraftState.NEEDS_REVIEW, analysis.state)
        assertFalse(analysis.isSelectable)
    }

    @Test
    fun periodGridRejectsOverlappingAndNonConsecutiveSlots() {
        val overlapping = PeriodGrid(
            listOf(
                PeriodSlot(1, 480, 540),
                PeriodSlot(2, 530, 600),
            ),
        )
        assertNotNull(overlapping.validationError())

        val nonConsecutive = PeriodGrid(
            listOf(
                PeriodSlot(1, 480, 540),
                PeriodSlot(3, 600, 660),
            ),
        )
        assertNotNull(nonConsecutive.validationError())
    }

    private fun draft(
        localId: String = "draft",
        name: String = "数学分析",
        classroom: String = "A101",
        dayOfWeek: Int? = 1,
        periodStart: Int? = 1,
        periodEnd: Int? = 2,
        startWeek: Int? = 1,
        endWeek: Int? = 16,
        parity: WeekParity = WeekParity.ALL,
        needsReview: Set<ScheduleImportField> = emptySet(),
    ) = ScheduleImportDraft(
        localId = localId,
        name = name,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        periodStart = periodStart,
        periodEnd = periodEnd,
        startWeek = startWeek,
        endWeek = endWeek,
        weekParity = parity,
        needsReview = needsReview,
    )

    private fun course(
        name: String,
        dayOfWeek: Int = 1,
        startMinute: Int = 480,
        endMinuteExclusive: Int = 580,
        startWeek: Int = 1,
        endWeek: Int = 16,
        parity: WeekParity = WeekParity.ALL,
    ) = Course(
        name = name,
        classroom = "A101",
        dayOfWeek = dayOfWeek,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        startWeek = startWeek,
        endWeek = endWeek,
        weekParity = parity,
        colorArgb = 0,
    )
}
