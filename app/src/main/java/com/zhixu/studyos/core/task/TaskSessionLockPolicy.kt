package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.TaskSession

/**
 * 手工编辑任务时的锁定规则：
 * - 与原有会话完全相同的时段保持原锁定状态；
 * - 新建或改变时间的时段一律锁定；
 * - 自动排程生成且未手工改动的时段仍可在下次排程中替换。
 */
object TaskSessionLockPolicy {
    fun applyManualEdit(
        initial: List<TaskSession>,
        edited: List<TaskSession>,
    ): List<TaskSession> {
        val unmatched = initial.toMutableList()
        return edited.map { draft ->
            val index = unmatched.indexOfFirst { existing ->
                existing.date == draft.date &&
                    existing.startMinute == draft.startMinute &&
                    existing.endMinuteExclusive == draft.endMinuteExclusive
            }
            if (index >= 0) {
                val match = unmatched.removeAt(index)
                draft.copy(id = 0, isLocked = match.isLocked)
            } else {
                draft.copy(id = 0, isLocked = true)
            }
        }
    }
}
