package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskConflict
import com.zhixu.studyos.core.schedule.ScheduleDateTimes
import com.zhixu.studyos.core.schedule.ScheduleEngine
import java.time.LocalDate
import java.time.ZoneId

object TaskConflictDetector {
    fun findConflicts(
        task: StudyTask,
        schedule: ScheduleSnapshot,
        fallbackZoneId: ZoneId,
    ): List<TaskConflict> {
        val semester = schedule.semester ?: return emptyList()
        if (task.isCompleted || task.sessions.isEmpty()) return emptyList()
        val zoneId = semester.zoneId
        val firstDate = task.sessions.minOf { it.date }
        val lastDate = task.sessions.maxOf { it.endDate }
        val courses = ScheduleEngine.instancesForAnchors(
            snapshot = schedule,
            anchorStart = firstDate.minusDays(1L),
            anchorEnd = lastDate,
        ).filter { it.type == ScheduleItemType.COURSE }

        val conflicts = mutableListOf<TaskConflict>()
        task.sessions.forEach { session ->
            val taskInstance = TaskPlanner.sessionInstance(task, session)
            val taskRange = ScheduleDateTimes.instantRange(taskInstance, zoneId) ?: return@forEach
            courses.forEach { course ->
                val courseRange = ScheduleDateTimes.instantRange(course, zoneId) ?: return@forEach
                val overlaps = taskRange.first.isBefore(courseRange.second) &&
                    courseRange.first.isBefore(taskRange.second)
                if (overlaps) {
                    val overlapStart = if (taskRange.first.isAfter(courseRange.first)) {
                        taskRange.first
                    } else {
                        courseRange.first
                    }
                    conflicts += TaskConflict(
                        taskId = task.id,
                        taskTitle = task.title,
                        date = overlapStart.atZone(zoneId).toLocalDate(),
                        otherTitle = course.title,
                        otherStartMinute = course.startMinute,
                        otherEndMinuteExclusive = course.endMinuteExclusive,
                    )
                }
            }
        }
        return conflicts.distinctBy { listOf(it.taskId, it.date, it.otherTitle, it.otherStartMinute) }
            .sortedBy(TaskConflict::date)
    }

    fun findAll(
        tasks: List<StudyTask>,
        schedule: ScheduleSnapshot?,
        fallbackZoneId: ZoneId,
    ): Map<Long, List<TaskConflict>> {
        if (schedule == null) return emptyMap()
        return tasks.mapNotNull { task ->
            val conflicts = findConflicts(task, schedule, fallbackZoneId)
            if (conflicts.isEmpty()) null else task.id to conflicts
        }.toMap()
    }
}
