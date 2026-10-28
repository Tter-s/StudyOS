package com.zhixu.studyos.core.history

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val task = StudyTask(
        id = 7,
        title = "数分习题",
        subject = "数学分析",
        priority = TaskPriority.MEDIUM,
        estimatedMinutes = 60,
        dueDate = LocalDate.of(2026, 10, 30),
        status = TaskStatus.SCHEDULED,
        sessions = listOf(
            TaskSession(
                id = 1,
                date = LocalDate.of(2026, 10, 25),
                startMinute = 540,
                endMinuteExclusive = 600,
                isLocked = true,
            ),
        ),
        globalId = "g-7",
        revision = 3,
    )

    @Test
    fun snapshotRoundTrip_keepsKeyFieldsAndSessionTotals() {
        val json = TaskSnapshotJson.encode(task)
        val decoded = TaskSnapshotJson.decode(json)!!
        assertEquals("数分习题", decoded.title)
        assertEquals("MEDIUM", decoded.priority)
        assertEquals("2026-10-30", decoded.dueDate)
        assertEquals(1, decoded.sessionCount)
        assertEquals(60, decoded.sessionMinutes)
    }

    @Test
    fun deriveEvent_prefersSpecificChanges() {
        assertEquals(
            TaskEventType.PRIORITY_CHANGED,
            HistoryRecorder.deriveTaskEvent(task, task.copy(priority = TaskPriority.HIGH), zone),
        )
        assertEquals(
            TaskEventType.TASK_COMPLETED,
            HistoryRecorder.deriveTaskEvent(task, task.copy(status = TaskStatus.COMPLETED), zone),
        )
        assertEquals(
            TaskEventType.TASK_CREATED,
            HistoryRecorder.deriveTaskEvent(null, task, zone),
        )
        assertEquals(
            TaskEventType.AUTO_SCHEDULED,
            HistoryRecorder.deriveTaskEvent(
                task,
                task.copy(sessions = task.sessions.map { it.copy(isLocked = false) }),
                zone,
            ),
        )
    }

    @Test
    fun views_showSourceAndHumanReadableDetail() {
        val event = TaskEvent(
            id = 1,
            objectType = HistoryObjectType.TASK,
            objectId = 7,
            eventType = TaskEventType.PRIORITY_CHANGED,
            occurredAt = Instant.parse("2026-10-25T08:00:00Z"),
            beforeJson = TaskSnapshotJson.encode(task),
            afterJson = TaskSnapshotJson.encode(task.copy(priority = TaskPriority.HIGH)),
            source = HistorySource.USER,
            revision = 4,
        )
        val view = TaskHistoryViews.toView(event, zone)
        assertTrue(view.detail.contains("中优先级"))
        assertTrue(view.detail.contains("高优先级"))
        assertEquals("用户", view.sourceLabel)
    }

    @Test
    fun analyzer_countsPostponesAndExplainsBacklog() {
        val event = TaskEvent(
            objectType = HistoryObjectType.TASK,
            objectId = 7,
            eventType = TaskEventType.TASK_POSTPONED,
            occurredAt = Instant.EPOCH,
            beforeJson = TaskSnapshotJson.encode(task.copy(postponeCount = 0)),
            afterJson = TaskSnapshotJson.encode(
                task.copy(
                    postponeCount = 3,
                    dueDate = LocalDate.of(2026, 11, 2),
                ),
            ),
            source = HistorySource.USER,
        )
        val analytics = TaskHistoryAnalyzer.analyze(
            events = listOf(event),
            tasks = listOf(task.copy(postponeCount = 3)),
            actualMinutesByTask = mapOf(7L to 90),
        )
        assertEquals(1, analytics.postponeCount)
        assertEquals(1, analytics.planModificationCount)
        assertEquals(listOf(7L), analytics.longBacklogTasks)
        assertEquals(30, analytics.estimatedVsActualMinutes.single().differenceMinutes)
        assertNotNull(analytics.estimatedVsActualMinutes.single())
    }
}
