package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.ScheduleConflict
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import java.time.Instant
import java.time.ZoneId

object ScheduleConflictDetector {
    fun findCourseConflicts(candidate: Course, snapshot: ScheduleSnapshot): List<ScheduleConflict> {
        val semester = requireNotNull(snapshot.semester) { "请先设置学期" }
        val candidateInstances = ScheduleEngine.courseInstances(
            course = candidate,
            semester = semester,
            exceptions = snapshot.exceptions.filter { it.courseId == candidate.id },
        )
        val existing = existingCourseAndEventInstances(snapshot, excludeCourseId = candidate.id)
        return findConflicts(candidateInstances, existing, semester.zoneId)
    }

    fun findEventConflicts(
        candidate: OneOffEvent,
        snapshot: ScheduleSnapshot,
        fallbackZoneId: ZoneId,
    ): List<ScheduleConflict> {
        val existing = existingCourseAndEventInstances(snapshot, excludeEventId = candidate.id)
        val zoneId = snapshot.semester?.zoneId ?: fallbackZoneId
        return findConflicts(listOf(ScheduleEngine.eventInstance(candidate)), existing, zoneId)
    }

    fun findExceptionConflicts(exception: CourseException, snapshot: ScheduleSnapshot): List<ScheduleConflict> {
        if (exception.type == CourseExceptionType.CANCEL) return emptyList()
        val semester = requireNotNull(snapshot.semester) { "请先设置学期" }
        val course = snapshot.courses.singleOrNull { it.id == exception.courseId }
            ?: throw IllegalArgumentException("课程不存在")
        require(SemesterCalendar.occursOn(course, exception.originalDate, semester)) { "原日期没有这门课程" }
        val original = ScheduleInstance(
            type = ScheduleItemType.COURSE,
            sourceId = course.id,
            title = course.name,
            subtitle = course.classroom,
            colorArgb = course.colorArgb,
            anchorDate = exception.originalDate,
            startMinute = course.startMinute,
            endMinuteExclusive = course.endMinuteExclusive,
        )
        val moved = original.copy(
            anchorDate = requireNotNull(exception.targetDate),
            startMinute = requireNotNull(exception.targetStartMinute),
            endMinuteExclusive = requireNotNull(exception.targetEndMinuteExclusive),
            isMoved = true,
        )
        val existing = existingCourseAndEventInstances(snapshot).filterNot {
            it.type == ScheduleItemType.COURSE &&
                it.sourceId == exception.courseId &&
                it.originalDate == exception.originalDate
        }
        return findConflicts(listOf(moved), existing, semester.zoneId)
    }

    fun overlaps(first: ScheduleInstance, second: ScheduleInstance, zoneId: ZoneId): Boolean {
        val firstRange = ScheduleDateTimes.instantRange(first, zoneId) ?: return false
        val secondRange = ScheduleDateTimes.instantRange(second, zoneId) ?: return false
        return firstRange.first.isBefore(secondRange.second) && secondRange.first.isBefore(firstRange.second)
    }

    private fun existingCourseAndEventInstances(
        snapshot: ScheduleSnapshot,
        excludeCourseId: Long? = null,
        excludeEventId: Long? = null,
    ): List<ScheduleInstance> {
        val courses = snapshot.semester?.let { semester ->
            snapshot.courses.filterNot { it.id == excludeCourseId }.flatMap { course ->
                ScheduleEngine.courseInstances(
                    course = course,
                    semester = semester,
                    exceptions = snapshot.exceptions.filter { it.courseId == course.id },
                )
            }
        }.orEmpty()
        val events = snapshot.events.filterNot { it.id == excludeEventId }.map(ScheduleEngine::eventInstance)
        return courses + events
    }

    private fun findConflicts(
        candidates: List<ScheduleInstance>,
        existing: List<ScheduleInstance>,
        zoneId: ZoneId,
    ): List<ScheduleConflict> {
        val conflicts = mutableListOf<ScheduleConflict>()
        candidates.forEach { candidate ->
            val candidateRange = ScheduleDateTimes.instantRange(candidate, zoneId) ?: return@forEach
            existing.forEach { other ->
                val otherRange = ScheduleDateTimes.instantRange(other, zoneId) ?: return@forEach
                if (overlaps(candidateRange, otherRange)) {
                    val overlapStart = if (candidateRange.first.isAfter(otherRange.first)) {
                        candidateRange.first
                    } else {
                        otherRange.first
                    }
                    conflicts += ScheduleConflict(
                        candidate = candidate,
                        existing = other,
                        date = overlapStart.atZone(zoneId).toLocalDate(),
                    )
                }
            }
        }
        return conflicts.sortedWith(
            compareBy(ScheduleConflict::date, { it.candidate.startMinute }, { it.existing.title }),
        )
    }

    private fun overlaps(first: Pair<Instant, Instant>, second: Pair<Instant, Instant>): Boolean =
        first.first.isBefore(second.second) && second.first.isBefore(first.second)
}
