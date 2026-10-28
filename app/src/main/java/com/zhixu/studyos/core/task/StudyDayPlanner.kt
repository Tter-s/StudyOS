package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.REST_COLOR_ARGB
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.StudyBlockKind
import com.zhixu.studyos.core.model.StudyDayBlock
import com.zhixu.studyos.core.model.StudyDayPlan
import com.zhixu.studyos.core.model.TaskDayEntry
import com.zhixu.studyos.core.schedule.FreeTimeCalculator
import com.zhixu.studyos.core.schedule.ScheduleEngine
import com.zhixu.studyos.core.sleep.SleepInterval
import java.time.LocalDate
import java.time.ZoneId

object StudyDayPlanner {
    fun build(
        date: LocalDate,
        tasks: List<TaskDayEntry>,
        schedule: ScheduleSnapshot?,
        fallbackZoneId: ZoneId,
        sleepIntervals: List<SleepInterval> = emptyList(),
    ): StudyDayPlan {
        val zoneId = schedule?.semester?.zoneId ?: fallbackZoneId
        val scheduleInstances = schedule?.let {
            ScheduleEngine.instancesForAnchors(it, date.minusDays(1L), date)
        }.orEmpty()
        val sleepInstances = sleepIntervals.mapIndexed { index, interval ->
            ScheduleInstance(
                type = ScheduleItemType.ROUTINE,
                sourceId = -10_000L - index,
                title = "睡眠保护",
                subtitle = "含睡前缓冲",
                colorArgb = REST_COLOR_ARGB,
                anchorDate = date,
                startMinute = interval.startMinute,
                endMinuteExclusive = interval.endMinuteExclusive,
                routineCategory = RoutineCategory.SLEEP,
            )
        }
        val taskInstances = tasks.flatMap { entry ->
            entry.task.sessions.map { session -> TaskPlanner.sessionInstance(entry.task, session) }
        }
        val freeTimes = FreeTimeCalculator.calculate(
            date,
            scheduleInstances + sleepInstances + taskInstances,
            zoneId,
        )

        val blocks = mutableListOf<StudyDayBlock>()
        if (schedule != null) {
            val scheduleDay = ScheduleEngine.daySchedule(schedule, date, fallbackZoneId)
            scheduleDay.items.forEach { item ->
                val instance = item.instance
                val kind = when (instance.type) {
                    ScheduleItemType.COURSE -> StudyBlockKind.COURSE
                    ScheduleItemType.ONE_OFF_EVENT -> StudyBlockKind.EVENT
                    ScheduleItemType.ROUTINE -> StudyBlockKind.REST
                    ScheduleItemType.TASK -> StudyBlockKind.TASK
                }
                blocks += StudyDayBlock(
                    kind = kind,
                    title = instance.title,
                    subtitle = when (kind) {
                        StudyBlockKind.COURSE -> listOf("课程", instance.subtitle).filter(String::isNotBlank).joinToString(" · ")
                        StudyBlockKind.EVENT -> "一次性日程"
                        StudyBlockKind.REST -> "休息 · ${instance.subtitle}"
                        StudyBlockKind.TASK -> "学习任务"
                    },
                    startMinute = item.segmentStartMinute,
                    endMinuteExclusive = item.segmentEndMinuteExclusive,
                    colorArgb = if (kind == StudyBlockKind.REST) REST_COLOR_ARGB else instance.colorArgb,
                    isContinuation = instance.anchorDate != date,
                )
            }
        }

        sleepInstances.forEach { instance ->
            blocks += StudyDayBlock(
                kind = StudyBlockKind.REST,
                title = instance.title,
                subtitle = "睡眠保护 · 不安排学习任务",
                startMinute = instance.startMinute,
                endMinuteExclusive = instance.endMinuteExclusive,
                colorArgb = REST_COLOR_ARGB,
            )
        }

        tasks.forEach { entry ->
            entry.segments.forEach { segment ->
                blocks += StudyDayBlock(
                    kind = StudyBlockKind.TASK,
                    title = entry.task.title,
                    subtitle = buildString {
                        append("学习任务")
                        if (entry.task.subject.isNotBlank()) append(" · ${entry.task.subject}")
                        if (entry.task.isCompleted) append(" · 已完成")
                    },
                    startMinute = segment.startMinute,
                    endMinuteExclusive = segment.endMinuteExclusive,
                    colorArgb = TaskPlanner.sessionInstance(entry.task, segment.session).colorArgb,
                    isContinuation = segment.isContinuation,
                    taskId = entry.task.id,
                    isCompleted = entry.task.isCompleted,
                )
            }
        }

        if (tasks.isNotEmpty() || scheduleInstances.isNotEmpty() || sleepInstances.isNotEmpty()) {
            freeTimes.forEach { interval ->
                val start = interval.start.hour * 60 + interval.start.minute
                val end = interval.end.hour * 60 + interval.end.minute
                val actualEnd = if (interval.end.toLocalDate().isAfter(date)) 24 * 60 else end
                if (actualEnd - start >= 15) {
                    blocks += StudyDayBlock(
                        kind = StudyBlockKind.REST,
                        title = "空闲",
                        subtitle = "休息或自由安排",
                        startMinute = start,
                        endMinuteExclusive = actualEnd,
                        colorArgb = REST_COLOR_ARGB,
                    )
                }
            }
        }

        return StudyDayPlan(
            date = date,
            zoneId = zoneId,
            tasks = tasks,
            timeline = blocks.sortedWith(
                compareBy(StudyDayBlock::startMinute, StudyDayBlock::endMinuteExclusive, StudyDayBlock::title),
            ),
            freeTimes = freeTimes,
        )
    }
}
