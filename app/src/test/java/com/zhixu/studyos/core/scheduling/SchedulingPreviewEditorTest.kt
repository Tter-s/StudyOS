package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingPreviewEditorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 26)
    private val semester = SemesterSettings(LocalDate.of(2026, 9, 7), zone)
    private val settings = SchedulingSettings()

    @Test
    fun adjustment_rejectsCourseConflictAndPastTimeAndOutsideHorizon() {
        val preview = preview()
        val block = preview.blocks.first()

        val conflict = SchedulingPreviewEditor.adjustBlock(
            preview = preview,
            blockId = block.id,
            date = today,
            startMinute = 9 * 60 + 15,
            endMinuteExclusive = 9 * 60 + 45,
            today = today,
            nowMinuteOfDay = 7 * 60,
        )
        assertNotNull(conflict.error)
        assertTrue(conflict.error.orEmpty().contains("冲突"))

        val past = SchedulingPreviewEditor.adjustBlock(
            preview = preview,
            blockId = block.id,
            date = today,
            startMinute = 8 * 60,
            endMinuteExclusive = 8 * 60 + 40,
            today = today,
            nowMinuteOfDay = 10 * 60,
        )
        assertNotNull(past.error)
        assertTrue(past.error.orEmpty().contains("过去"))

        val outside = SchedulingPreviewEditor.adjustBlock(
            preview = preview,
            blockId = block.id,
            date = today.plusDays(1),
            startMinute = 10 * 60,
            endMinuteExclusive = 10 * 60 + 50,
            today = today,
            nowMinuteOfDay = 7 * 60,
        )
        assertNotNull(outside.error)

        val missing = SchedulingPreviewEditor.adjustBlock(
            preview = preview,
            blockId = 999_999,
            date = today,
            startMinute = 20 * 60,
            endMinuteExclusive = 20 * 60 + 50,
            today = today,
            nowMinuteOfDay = 7 * 60,
        )
        assertNotNull(missing.error)
    }

    @Test
    fun adjustment_locksBlockAndKeepsUnscheduledAccounting() {
        val preview = preview()
        val block = preview.blocks.first()
        val outcome = SchedulingPreviewEditor.adjustBlock(
            preview = preview,
            blockId = block.id,
            date = today,
            startMinute = 20 * 60,
            endMinuteExclusive = 20 * 60 + block.durationMinutes,
            today = today,
            nowMinuteOfDay = 7 * 60,
        )
        assertNull(outcome.error)
        val adjusted = outcome.preview
        assertNotNull(adjusted)
        val moved = adjusted!!.blocks.first { it.id == block.id }
        assertTrue(moved.isLocked)
        assertEquals(20 * 60, moved.startMinute)
        assertEquals(60, adjusted.blocks.sumOf { it.durationMinutes })
        assertTrue(adjusted.unscheduled.isEmpty())
    }

    @Test
    fun removingBlock_increasesUnscheduledAndToggleLockDoesNotChangeMinutes() {
        val preview = preview()
        val block = preview.blocks.first()
        val removed = SchedulingPreviewEditor.removeBlock(preview, block.id)
        assertEquals(60 - block.durationMinutes, removed.blocks.sumOf { it.durationMinutes })
        assertEquals(block.durationMinutes, removed.unscheduled.single().remainingMinutes)
        assertTrue(removed.unscheduled.single().reason.contains("手动调整"))

        val locked = SchedulingPreviewEditor.toggleLock(preview, block.id)
        assertTrue(locked.blocks.first { it.id == block.id }.isLocked)
        val unlocked = SchedulingPreviewEditor.toggleLock(locked, block.id)
        assertFalse(unlocked.blocks.first { it.id == block.id }.isLocked)
        assertEquals(preview.blocks.sumOf { it.durationMinutes }, unlocked.blocks.sumOf { it.durationMinutes })
    }

    private fun preview(): SchedulingPreview {
        val task = StudyTask(
            id = 1,
            title = "数学分析",
            estimatedMinutes = 60,
            priority = TaskPriority.MEDIUM,
            status = TaskStatus.BACKLOG,
        )
        val schedule = ScheduleSnapshot(
            semester = semester,
            courses = listOf(
                Course(
                    id = 1,
                    name = "高等代数",
                    classroom = "A101",
                    dayOfWeek = 1,
                    startMinute = 9 * 60,
                    endMinuteExclusive = 10 * 60,
                    startWeek = 1,
                    endWeek = 16,
                    weekParity = WeekParity.ALL,
                    colorArgb = 0xFF4A79C4.toInt(),
                ),
            ),
        )
        val result = AutoScheduler.schedule(
            SchedulingInput(
                today = today,
                nowMinuteOfDay = 7 * 60,
                horizonDays = 1,
                schedule = schedule,
                tasks = listOf(task),
                settings = settings,
                subjectPreferences = emptyMap(),
                zoneId = zone,
            ),
        )
        return SchedulingPreviewEditor.fromResult(result)
    }
}
