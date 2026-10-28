package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.WeekParity

/**
 * Detects duplicate and conflicting imports without trusting OCR. Two courses
 * overlap only when their teaching weeks intersect — including odd/even week
 * rules — and their real minute intervals intersect. Cross-midnight courses are
 * split into a next-day segment so an early-morning imported course is compared
 * against the correct column.
 */
object ScheduleImportAnalyzer {
    fun analyze(
        drafts: List<ScheduleImportDraft>,
        grid: PeriodGrid,
        existingCourses: List<Course>,
    ): Map<String, ScheduleImportDraftAnalysis> {
        val validation = LinkedHashMap<String, String?>()
        val review = LinkedHashMap<String, Set<ScheduleImportField>>()
        val courses = LinkedHashMap<String, Course>()

        drafts.forEach { draft ->
            val error = ScheduleImportValidation.validationError(draft, grid)
            validation[draft.localId] = error
            review[draft.localId] = draft.needsReview
            if (error == null) {
                ScheduleImportValidation.toCourse(draft, grid)?.let { courses[draft.localId] = it }
            }
        }

        val duplicates = mutableMapOf<String, MutableList<CourseScheduleOverlap>>()
        val conflicts = mutableMapOf<String, MutableList<CourseScheduleOverlap>>()

        fun record(localId: String, overlaps: List<CourseScheduleOverlap>) {
            overlaps.forEach { overlap ->
                val target = if (overlap.sameName) duplicates else conflicts
                target.getOrPut(localId) { mutableListOf() }
                    .add(overlap)
            }
        }

        courses.forEach { (localId, course) ->
            record(localId, findOverlaps(course, existingCourses))
        }

        val ids = courses.keys.toList()
        for (index in ids.indices) {
            for (otherIndex in index + 1 until ids.size) {
                val firstId = ids[index]
                val secondId = ids[otherIndex]
                val first = courses.getValue(firstId)
                val second = courses.getValue(secondId)
                val firstOverlaps = findOverlaps(first, listOf(second))
                val secondOverlaps = findOverlaps(second, listOf(first))
                record(firstId, firstOverlaps)
                record(secondId, secondOverlaps)
            }
        }

        return drafts.associate { draft ->
            draft.localId to ScheduleImportDraftAnalysis(
                localId = draft.localId,
                validationError = validation[draft.localId],
                needsReview = review[draft.localId].orEmpty(),
                duplicates = duplicates[draft.localId].orEmpty().distinctOverlaps(),
                conflicts = conflicts[draft.localId].orEmpty().distinctOverlaps(),
            )
        }
    }

    fun findOverlaps(candidate: Course, others: List<Course>): List<CourseScheduleOverlap> {
        val result = mutableListOf<CourseScheduleOverlap>()
        segmentCourse(candidate).forEach { candidateSegment ->
            others.forEach { other ->
                segmentCourse(other).forEach otherLoop@{ otherSegment ->
                    if (candidateSegment.dayOfWeek != otherSegment.dayOfWeek) return@otherLoop
                    val weeks = candidateSegment.weeks intersect otherSegment.weeks
                    if (weeks.isEmpty()) return@otherLoop
                    val start = maxOf(candidateSegment.startMinute, otherSegment.startMinute)
                    val end = minOf(candidateSegment.endMinuteExclusive, otherSegment.endMinuteExclusive)
                    if (start >= end) return@otherLoop
                    result += CourseScheduleOverlap(
                        otherName = other.name,
                        sameName = normalizedName(candidate.name) == normalizedName(other.name),
                        dayOfWeek = candidateSegment.dayOfWeek,
                        startMinute = start,
                        endMinuteExclusive = end,
                        weeks = weeks,
                    )
                }
            }
        }
        return result.distinctOverlaps()
    }

    fun normalizedName(name: String): String = name
        .filterNot(Char::isWhitespace)
        .lowercase()

    private fun List<CourseScheduleOverlap>.distinctOverlaps(): List<CourseScheduleOverlap> =
        distinctBy {
            listOf(
                it.otherName,
                it.sameName,
                it.dayOfWeek,
                it.startMinute,
                it.endMinuteExclusive,
                it.weeks.sorted(),
            )
        }

    private fun segmentCourse(course: Course): List<ActiveSegment> {
        if (course.dayOfWeek !in 1..7) return emptyList()
        val weeks = activeWeeks(course)
        if (weeks.isEmpty()) return emptyList()
        val result = mutableListOf<ActiveSegment>()
        if (course.startMinute < MINUTES_PER_DAY) {
            val firstEnd = minOf(course.endMinuteExclusive, MINUTES_PER_DAY)
            if (firstEnd > course.startMinute) {
                result += ActiveSegment(course.dayOfWeek, course.startMinute, firstEnd, weeks)
            }
        }
        if (course.endMinuteExclusive > MINUTES_PER_DAY) {
            val carryEnd = (course.endMinuteExclusive - MINUTES_PER_DAY).coerceAtMost(MINUTES_PER_DAY)
            if (carryEnd > 0) {
                val nextDay = if (course.dayOfWeek == 7) 1 else course.dayOfWeek + 1
                // Crossing Sunday midnight enters the next teaching week.
                val nextWeeks = if (course.dayOfWeek == 7) weeks.map { it + 1 }.toSet() else weeks
                result += ActiveSegment(nextDay, 0, carryEnd, nextWeeks)
            }
        }
        return result
    }

    private fun activeWeeks(course: Course): Set<Int> {
        if (course.startWeek < 1 || course.endWeek < course.startWeek) return emptySet()
        return (course.startWeek..course.endWeek).filter { week ->
            when (course.weekParity) {
                WeekParity.ALL -> true
                WeekParity.ODD -> week % 2 == 1
                WeekParity.EVEN -> week % 2 == 0
            }
        }.toSet()
    }

    private data class ActiveSegment(
        val dayOfWeek: Int,
        val startMinute: Int,
        val endMinuteExclusive: Int,
        val weeks: Set<Int>,
    )
}
