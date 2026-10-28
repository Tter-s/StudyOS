package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.TaskSession
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSessionLockPolicyTest {
    private val date = LocalDate.of(2026, 10, 26)

    @Test
    fun unchangedSessionsKeepTheirOriginalLockState() {
        val auto = session(start = 8 * 60, end = 9 * 60, isLocked = false)
        val manual = session(start = 10 * 60, end = 11 * 60, isLocked = true)
        val edited = TaskSessionLockPolicy.applyManualEdit(
            initial = listOf(auto, manual),
            edited = listOf(auto, manual),
        )
        assertFalse(edited[0].isLocked)
        assertTrue(edited[1].isLocked)
    }

    @Test
    fun changedAndNewSessionsAreLocked() {
        val auto = session(start = 8 * 60, end = 9 * 60, isLocked = false)
        val changed = session(start = 8 * 60, end = 9 * 60 + 30, isLocked = false)
        val new = session(start = 13 * 60, end = 14 * 60, isLocked = false)

        val edited = TaskSessionLockPolicy.applyManualEdit(
            initial = listOf(auto),
            edited = listOf(changed, new),
        )
        assertTrue(edited.all { it.isLocked })
        assertEquals(0L, edited[0].id)
        assertEquals(0L, edited[1].id)
    }

    @Test
    fun newTaskSessionsAreLockedByDefault() {
        val edited = TaskSessionLockPolicy.applyManualEdit(
            initial = emptyList(),
            edited = listOf(session(start = 9 * 60, end = 10 * 60, isLocked = false)),
        )
        assertTrue(edited.single().isLocked)
    }

    private fun session(start: Int, end: Int, isLocked: Boolean) = TaskSession(
        id = 7,
        date = date,
        startMinute = start,
        endMinuteExclusive = end,
        isLocked = isLocked,
    )
}
