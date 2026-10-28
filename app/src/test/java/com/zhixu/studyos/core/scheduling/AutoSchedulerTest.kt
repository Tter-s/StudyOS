package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.WeekParity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 26)
    private val semester = SemesterSettings(LocalDate.of(2026, 9, 7), zone)
    private val defaultSettings = SchedulingSettings()

    @Test
    fun schedulesByDeadlineAndPriorityWithoutOverlap() {
        val dueToday = task(3, estimated = 60, dueDate = today, priority = TaskPriority.LOW)
        val high = task(1, estimated = 60, priority = TaskPriority.HIGH)
        val low = task(2, estimated = 60, priority = TaskPriority.LOW)

        val result = schedule(listOf(high, low, dueToday))

        assertEquals(listOf(3L, 1L, 2L), result.taskPlans.map { it.taskId })
        assertTrue(result.unscheduled.isEmpty())
        result.taskPlans.forEach { plan ->
            assertEquals(
                plan.remainingMinutes,
                result.blocks.filter { it.taskId == plan.taskId }.sumOf { it.durationMinutes },
            )
        }
        assertEquals(180, result.blocks.sumOf { it.durationMinutes })
        assertNoOverlap(result.blocks)
        assertTrue(result.blocks.all { it.startMinute >= 480 && it.endMinuteExclusive <= 1320 })
        assertTrue(result.blocks.all { it.durationMinutes <= 50 })
    }

    @Test
    fun doesNotOccupyCoursesSleepMealsOrEventsAndKeepsBuffer() {
        val schedule = ScheduleSnapshot(
            semester = semester,
            courses = listOf(course(start = 9 * 60, end = 10 * 60)),
            events = listOf(event(start = 18 * 60, end = 19 * 60)),
            routines = listOf(
                routine(
                    id = 1,
                    category = RoutineCategory.SLEEP,
                    start = 22 * 60,
                    end = 31 * 60,
                ),
                routine(
                    id = 2,
                    category = RoutineCategory.MEAL,
                    start = 12 * 60,
                    end = 13 * 60,
                ),
            ),
        )
        val settings = defaultSettings.copy(
            studyStartMinute = 7 * 60,
            studyEndMinuteExclusive = 23 * 60,
        )
        val result = schedule(
            tasks = listOf(task(1, estimated = 120)),
            schedule = schedule,
            settings = settings,
            nowMinute = 7 * 60,
        )

        assertEquals(120, result.blocks.sumOf { it.durationMinutes })
        assertNoOverlap(result.blocks)
        assertNoFixedOverlap(result.days, result.blocks)
        result.blocks.forEach { block ->
            assertTrue(block.startMinute >= 7 * 60)
            assertTrue(block.endMinuteExclusive <= 23 * 60)
        }
        assertTrue(result.days.single().fixedBlocks.any { it.kind == FixedBlockKind.COURSE })
        assertTrue(result.days.single().fixedBlocks.any { it.kind == FixedBlockKind.ROUTINE })
    }

    @Test
    fun splitsLargeTaskWithBufferAndBoundedSessions() {
        val result = schedule(listOf(task(1, estimated = 120)))
        val blocks = result.blocks
        assertEquals(120, blocks.sumOf { it.durationMinutes })
        assertTrue(blocks.all { it.durationMinutes <= SchedulingSettings.MAX_SESSION_MINUTES })
        assertTrue(blocks.size > 1)
        assertNoOverlap(blocks)
        blocks.zipWithNext().forEach { (first, second) ->
            if (first.date == second.date) {
                assertTrue(second.startMinute - first.endMinuteExclusive >= defaultSettings.bufferMinutes)
            }
        }
    }

    @Test
    fun respectsDailyStudyLimitAndReportsTheUnplacedRemainder() {
        val settings = defaultSettings.copy(dailyLimitMinutes = 90)
        val result = schedule(
            tasks = listOf(task(1, estimated = 180)),
            settings = settings,
        )
        assertEquals(90, result.blocks.sumOf { it.durationMinutes })
        assertEquals(90, result.unscheduled.single().remainingMinutes)
        assertTrue(result.unscheduled.single().reason.contains("上限"))
        assertTrue(result.warnings.any { it.type == SchedulingWarningType.DAILY_LIMIT })
    }

    @Test
    fun noFreeTime_leavesTasksUnscheduledWithExplicitReason() {
        val schedule = ScheduleSnapshot(
            routines = listOf(
                routine(
                    id = 1,
                    category = RoutineCategory.FIXED,
                    start = 8 * 60,
                    end = 22 * 60,
                ),
            ),
        )
        val result = schedule(
            tasks = listOf(task(1, estimated = 60)),
            schedule = schedule,
            nowMinute = 8 * 60,
        )
        assertTrue(result.blocks.isEmpty())
        assertEquals(60, result.unscheduled.single().remainingMinutes)
        assertTrue(result.unscheduled.single().reason.contains("没有可用空闲时间"))
        assertTrue(result.warnings.any { it.type == SchedulingWarningType.NO_FREE_TIME })
    }

    @Test
    fun crossDayCourseBlocksBothDaysAndDoesNotCrossMidnight() {
        val schedule = ScheduleSnapshot(
            semester = semester,
            courses = listOf(course(start = 23 * 60, end = 25 * 60)),
        )
        val settings = defaultSettings.copy(
            studyStartMinute = 0,
            studyEndMinuteExclusive = 24 * 60,
            dailyLimitMinutes = 600,
            bufferMinutes = 10,
        )
        val result = schedule(
            tasks = listOf(task(1, estimated = 900)),
            schedule = schedule,
            settings = settings,
            horizonDays = 2,
            nowMinute = 8 * 60,
        )
        assertEquals(900, result.blocks.sumOf { it.durationMinutes })
        assertTrue(result.blocks.none { it.date == today && it.endMinuteExclusive > 23 * 60 })
        assertTrue(
            result.blocks.none {
                it.date == today.plusDays(1) && it.startMinute < 60 + settings.bufferMinutes
            },
        )
        assertTrue(result.days[0].fixedBlocks.any { it.startMinute == 23 * 60 })
        assertTrue(result.days[1].fixedBlocks.any { it.startMinute == 0 && it.endMinuteExclusive == 60 })
        assertNoOverlap(result.blocks)
        assertTrue(result.blocks.all { it.endMinuteExclusive <= 24 * 60 })
    }

    @Test
    fun horizonOneVersusSevenDays_changesCoverage() {
        val oneDay = schedule(
            tasks = listOf(task(1, estimated = 600)),
            settings = defaultSettings.copy(dailyLimitMinutes = 240),
            horizonDays = 1,
        )
        assertEquals(240, oneDay.blocks.sumOf { it.durationMinutes })
        assertEquals(360, oneDay.unscheduled.single().remainingMinutes)

        val sevenDays = schedule(
            tasks = listOf(task(1, estimated = 600)),
            settings = defaultSettings.copy(dailyLimitMinutes = 240),
            horizonDays = 7,
        )
        assertEquals(600, sevenDays.blocks.sumOf { it.durationMinutes })
        assertTrue(sevenDays.unscheduled.isEmpty())
        assertTrue(sevenDays.blocks.map { it.date }.distinct().size > 1)
    }

    @Test
    fun dueDate_limitsSchedulingDays() {
        val schedule = ScheduleSnapshot(
            semester = semester,
            events = listOf(event(date = today, start = 8 * 60, end = 22 * 60)),
        )
        val result = schedule(
            tasks = listOf(task(1, estimated = 120, dueDate = today.plusDays(1))),
            schedule = schedule,
            horizonDays = 3,
            nowMinute = 8 * 60,
        )
        assertEquals(120, result.blocks.sumOf { it.durationMinutes })
        assertTrue(result.blocks.all { it.date == today.plusDays(1) })
        assertTrue(result.unscheduled.isEmpty())
    }

    @Test
    fun lockedTaskMinutes_arePreservedAndConflictsAreReported() {
        val lockedSession = TaskSession(
            date = today,
            startMinute = 9 * 60,
            endMinuteExclusive = 9 * 60 + 30,
            isLocked = true,
        )
        val lockedTask = task(
            id = 1,
            estimated = 90,
            sessions = listOf(lockedSession),
        )
        val schedule = ScheduleSnapshot(
            semester = semester,
            courses = listOf(course(start = 9 * 60 + 15, end = 10 * 60)),
        )
        val result = schedule(
            tasks = listOf(lockedTask),
            schedule = schedule,
            nowMinute = 7 * 60,
        )
        assertEquals(30, result.taskPlans.single().fixedMinutes)
        assertEquals(60, result.taskPlans.single().remainingMinutes)
        assertEquals(60, result.blocks.sumOf { it.durationMinutes })
        assertTrue(result.blocks.none {
            it.startMinute < 9 * 60 + 30 && 9 * 60 + 15 < it.endMinuteExclusive
        })
        assertTrue(result.days.single().fixedBlocks.any { it.isLocked && it.taskId == 1L })
        assertTrue(result.conflicts.any { it.firstTitle == "数学分析" || it.secondTitle == "数学分析" })
        assertTrue(result.warnings.any { it.type == SchedulingWarningType.CONFLICT })
    }

    @Test
    fun inProgressAndCompletedTasks_areFixedAndNotCandidates() {
        val inProgress = task(
            id = 1,
            estimated = 60,
            sessions = listOf(TaskSession(date = today, startMinute = 9 * 60, endMinuteExclusive = 10 * 60)),
            status = TaskStatus.IN_PROGRESS,
        )
        val completed = task(
            id = 2,
            estimated = 60,
            sessions = listOf(TaskSession(date = today, startMinute = 11 * 60, endMinuteExclusive = 12 * 60)),
            status = TaskStatus.COMPLETED,
            completedAt = Instant.parse("2026-10-26T03:00:00Z"),
        )
        val backlog = task(id = 3, estimated = 60)
        val result = schedule(
            tasks = listOf(inProgress, completed, backlog),
            nowMinute = 8 * 60,
        )
        assertEquals(listOf(3L), result.taskPlans.map { it.taskId })
        assertEquals(listOf(3L), result.candidateTaskIds)
        assertEquals(60, result.blocks.sumOf { it.durationMinutes })
        assertEquals(60, result.days.single().fixedStudyMinutes)
        assertTrue(result.days.single().fixedBlocks.any { it.taskId == 1L })
        assertTrue(result.days.single().fixedBlocks.any { it.taskId == 2L && it.isCompleted })
    }

    @Test
    fun todayNow_isNeverScheduledIntoThePast() {
        val result = schedule(
            tasks = listOf(task(1, estimated = 60)),
            nowMinute = 15 * 60,
        )
        assertTrue(result.blocks.isNotEmpty())
        assertTrue(result.blocks.all { it.startMinute >= 15 * 60 })
        assertTrue(result.blocks.all { it.date == today })
    }

    @Test
    fun subjectPreferenceAndCarryOver_changeOrdering() {
        val preferred = task(1, subject = "数学分析")
        val neutral = task(2)
        val carryOver = task(
            id = 3,
            sessions = listOf(TaskSession(date = today.minusDays(1), startMinute = 9 * 60, endMinuteExclusive = 10 * 60, isLocked = true)),
        )
        val preferences = mapOf("数学分析" to SubjectPreference.HIGHER)
        val result = schedule(
            tasks = listOf(preferred, neutral, carryOver),
            preferences = preferences,
        )
        assertEquals(listOf(3L, 1L, 2L), result.taskPlans.map { it.taskId })
        assertEquals(3L, result.blocks.first().taskId)
    }

    @Test
    fun extremeTaskVolume_isDeterministicAndFullyAccountedFor() {
        val tasks = (1L..300L).map { id -> task(id, estimated = 30) }
        val first = schedule(tasks = tasks, horizonDays = 7)
        val second = schedule(tasks = tasks, horizonDays = 7)

        assertEquals(300, first.taskPlans.size)
        assertEquals(300 * 30, first.blocks.sumOf { it.durationMinutes } +
            first.unscheduled.sumOf { it.remainingMinutes })
        assertTrue(first.unscheduled.isNotEmpty())
        assertNoOverlap(first.blocks)
        assertTrue(first.blocks.all { it.durationMinutes <= 50 })
        assertEquals(
            first.blocks.map { listOf(it.id, it.taskId, it.date, it.startMinute, it.endMinuteExclusive) },
            second.blocks.map { listOf(it.id, it.taskId, it.date, it.startMinute, it.endMinuteExclusive) },
        )
        assertEquals(
            first.taskPlans.map { it.taskId },
            second.taskPlans.map { it.taskId },
        )
    }

    private fun schedule(
        tasks: List<StudyTask>,
        schedule: ScheduleSnapshot = ScheduleSnapshot(semester = semester),
        settings: SchedulingSettings = defaultSettings,
        horizonDays: Int = 1,
        nowMinute: Int = 8 * 60,
        preferences: Map<String, SubjectPreference> = emptyMap(),
    ): SchedulingResult = AutoScheduler.schedule(
        SchedulingInput(
            today = today,
            nowMinuteOfDay = nowMinute,
            horizonDays = horizonDays,
            schedule = schedule,
            tasks = tasks,
            settings = settings,
            subjectPreferences = preferences,
            zoneId = zone,
        ),
    )

    private fun assertNoOverlap(blocks: List<ProposedSession>) {
        blocks.groupBy(ProposedSession::date).values.forEach { dayBlocks ->
            val sorted = dayBlocks.sortedBy(ProposedSession::startMinute)
            sorted.zipWithNext().forEach { (first, second) ->
                assertTrue(
                    "重排时段重叠：$first / $second",
                    second.startMinute >= first.endMinuteExclusive,
                )
            }
        }
    }

    private fun assertNoFixedOverlap(days: List<SchedulingDay>, blocks: List<ProposedSession>) {
        days.forEach { day ->
            val dayBlocks = blocks.filter { it.date == day.date }
            day.fixedBlocks.forEach { fixed ->
                dayBlocks.forEach { block ->
                    assertFalse(
                        "自动时段与固定安排重叠：$fixed / $block",
                        block.startMinute < fixed.endMinuteExclusive &&
                            fixed.startMinute < block.endMinuteExclusive,
                    )
                }
            }
        }
    }

    private fun task(
        id: Long,
        title: String = "数学分析",
        estimated: Int = 60,
        dueDate: LocalDate? = null,
        priority: TaskPriority = TaskPriority.MEDIUM,
        subject: String = "",
        sessions: List<TaskSession> = emptyList(),
        status: TaskStatus = if (sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED,
        completedAt: Instant? = null,
    ) = StudyTask(
        id = id,
        title = title,
        subject = subject,
        priority = priority,
        dueDate = dueDate,
        estimatedMinutes = estimated,
        status = status,
        completedAt = completedAt,
        sortOrder = id.toInt(),
        sessions = sessions,
    )

    private fun course(
        start: Int,
        end: Int,
    ) = Course(
        id = 1,
        name = "数学分析",
        teacher = "张老师",
        classroom = "A101",
        dayOfWeek = today.dayOfWeek.value,
        startMinute = start,
        endMinuteExclusive = end,
        startWeek = 1,
        endWeek = 16,
        weekParity = WeekParity.ALL,
        colorArgb = 0xFF4A79C4.toInt(),
    )

    private fun event(
        date: LocalDate = today,
        start: Int,
        end: Int,
    ) = OneOffEvent(
        id = 1,
        title = "临时事务",
        date = date,
        startMinute = start,
        endMinuteExclusive = end,
        location = "图书馆",
        colorArgb = 0xFFD1913C.toInt(),
    )

    private fun routine(
        id: Long,
        category: RoutineCategory,
        start: Int,
        end: Int,
    ) = RoutineBlock(
        id = id,
        title = category.name,
        category = category,
        repeat = RoutineRepeat.DAILY,
        dayOfWeek = null,
        startMinute = start,
        endMinuteExclusive = end,
    )
}
