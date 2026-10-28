package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskDao
import com.zhixu.studyos.core.data.local.StudyTaskEntity
import com.zhixu.studyos.core.data.local.TaskSessionEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.history.HistoryObjectType
import com.zhixu.studyos.core.history.HistoryRecorder
import com.zhixu.studyos.core.history.HistorySource
import com.zhixu.studyos.core.history.TaskEventType
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSaveResult
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.task.TaskPlanner
import com.zhixu.studyos.core.task.TaskStateMachine
import com.zhixu.studyos.core.task.TaskValidation
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TaskRepository : HomeRepository {
    fun observeAllTasks(): Flow<List<StudyTask>>
    suspend fun saveTask(task: StudyTask): TaskSaveResult
    suspend fun deleteTask(taskId: Long)
    suspend fun deleteSeries(seriesId: String)
    suspend fun startTask(taskId: Long)
    suspend fun pauseTask(taskId: Long)
    suspend fun postponeTask(taskId: Long, days: Long = 1L): StudyTask
    suspend fun reorderTasks(orderedTaskIds: List<Long>)
    suspend fun setSessionLocked(taskId: Long, sessionId: Long, locked: Boolean)
    suspend fun getTask(taskId: Long): StudyTask?

    /** 记录 AI 建议被接受/拒绝；默认空实现便于测试替身。 */
    suspend fun recordAiSuggestionEvent(
        taskId: Long,
        accepted: Boolean,
        reason: String,
    ) = Unit
}

class OfflineTaskRepository(
    private val database: StudyOSDatabase,
    private val taskDao: StudyTaskDao,
    private val clock: Clock,
) : TaskRepository {
    private val history = HistoryRecorder(database.historyDao(), clock)

    override fun observeAllTasks(): Flow<List<StudyTask>> =
        taskDao.observeAllWithSessions().map { rows -> rows.map { it.toDomainModel() } }

    override fun observeTasks(epochDay: Long): Flow<List<StudyTask>> =
        taskDao.observeByDate(epochDay).map { rows -> rows.map { it.toDomainModel() } }

    override suspend fun saveTask(task: StudyTask): TaskSaveResult {
        return if (task.id == 0L) createTask(task) else updateTask(task)
    }

    override suspend fun setTaskCompleted(taskId: Long, completed: Boolean) {
        database.withTransaction {
            val loaded = taskDao.getWithSessions(taskId) ?: throw IllegalArgumentException("任务不存在，可能已被删除")
            val task = loaded.toDomainModel()
            if (task.isCompleted == completed) return@withTransaction
            val updated = if (completed) {
                TaskStateMachine.complete(task, Instant.now(clock))
            } else {
                TaskStateMachine.undoCompletion(task)
            }.copy(revision = task.revision + 1)
            TaskStateMachine.validateInvariants(updated)
            taskDao.update(updated.toEntity(loaded.task.createdAtEpochMillis, clock.millis()))
            history.recordTaskChange(
                before = task,
                after = updated,
                eventType = if (completed) TaskEventType.TASK_COMPLETED else TaskEventType.TASK_REOPENED,
                explicitEventType = true,
            )
        }
    }

    override suspend fun startTask(taskId: Long) {
        database.withTransaction {
            val loaded = taskDao.getWithSessions(taskId) ?: throw IllegalArgumentException("任务不存在")
            val current = loaded.toDomainModel()
            val updated = TaskStateMachine.start(current).copy(revision = current.revision + 1)
            TaskStateMachine.validateInvariants(updated)
            taskDao.update(updated.toEntity(loaded.task.createdAtEpochMillis, clock.millis()))
            history.recordTaskChange(
                before = current,
                after = updated,
                eventType = TaskEventType.TASK_STARTED,
                explicitEventType = true,
            )
        }
    }

    override suspend fun pauseTask(taskId: Long) {
        database.withTransaction {
            val loaded = taskDao.getWithSessions(taskId) ?: throw IllegalArgumentException("任务不存在")
            val current = loaded.toDomainModel()
            val updated = TaskStateMachine.pause(current).copy(revision = current.revision + 1)
            TaskStateMachine.validateInvariants(updated)
            taskDao.update(updated.toEntity(loaded.task.createdAtEpochMillis, clock.millis()))
            history.recordTaskChange(
                before = current,
                after = updated,
                eventType = TaskEventType.TASK_PAUSED,
                explicitEventType = true,
            )
        }
    }

    override suspend fun postponeTask(taskId: Long, days: Long): StudyTask {
        require(days > 0L) { "延期天数必须大于 0" }
        return database.withTransaction {
            val loaded = taskDao.getWithSessions(taskId) ?: throw IllegalArgumentException("任务不存在")
            val current = loaded.toDomainModel()
            require(!current.isCompleted) { "已完成任务不能延期" }
            require(current.sessions.isNotEmpty()) { "待安排任务请先安排时间" }
            val replanned = TaskStateMachine.afterReplan(
                current.copy(
                    sessions = current.sessions.map {
                        it.copy(id = 0, date = it.date.plusDays(days), isLocked = true)
                    },
                    dueDate = current.dueDate?.plusDays(days),
                    status = TaskStatus.SCHEDULED,
                    completedAt = null,
                    postponeCount = current.postponeCount + 1,
                    revision = current.revision + 1,
                ),
            )
            TaskValidation.validate(replanned)
            val now = clock.millis()
            taskDao.update(replanned.toEntity(loaded.task.createdAtEpochMillis, now))
            replaceSessions(replanned, now)
            history.recordTaskChange(
                before = current,
                after = replanned,
                eventType = TaskEventType.TASK_POSTPONED,
                reason = "延期 $days 天",
                explicitEventType = true,
            )
            replanned
        }
    }

    override suspend fun reorderTasks(orderedTaskIds: List<Long>) {
        require(orderedTaskIds.distinct().size == orderedTaskIds.size) { "任务顺序包含重复项" }
        val now = clock.millis()
        database.withTransaction {
            orderedTaskIds.forEachIndexed { index, taskId ->
                taskDao.updateSortOrder(taskId, index, now)
            }
        }
    }

    override suspend fun deleteTask(taskId: Long) {
        database.withTransaction {
            taskDao.getWithSessions(taskId)?.let { row ->
                val task = row.toDomainModel()
                history.recordTaskChange(
                    before = task,
                    after = task,
                    eventType = TaskEventType.TASK_DELETED,
                    reason = "用户删除任务",
                    explicitEventType = true,
                )
            }
            taskDao.deleteTask(taskId)
        }
    }

    override suspend fun deleteSeries(seriesId: String) {
        database.withTransaction {
            taskDao.getSeries(seriesId).forEach { entity ->
                taskDao.getWithSessions(entity.id)?.let { loaded ->
                    val task = loaded.toDomainModel()
                    history.recordTaskChange(
                        before = task,
                        after = task,
                        eventType = TaskEventType.TASK_DELETED,
                        reason = "用户删除周期任务系列",
                        explicitEventType = true,
                    )
                }
            }
            taskDao.deleteSeries(seriesId)
        }
    }

    override suspend fun setSessionLocked(taskId: Long, sessionId: Long, locked: Boolean) {
        database.withTransaction {
            val loaded = taskDao.getWithSessions(taskId)
                ?: throw IllegalArgumentException("任务不存在，可能已被删除")
            require(loaded.sessions.any { it.id == sessionId }) { "学习时段不存在，可能已被删除" }
            val updated = taskDao.updateSessionLocked(
                taskId = taskId,
                sessionId = sessionId,
                isLocked = locked,
                updatedAt = clock.millis(),
            )
            require(updated == 1) { "更新锁定状态失败" }
            history.record(
                objectType = HistoryObjectType.TASK,
                objectId = taskId,
                eventType = if (locked) TaskEventType.SESSION_LOCKED else TaskEventType.SESSION_UNLOCKED,
                source = HistorySource.USER,
                sessionId = sessionId,
            )
        }
    }

    override suspend fun getTask(taskId: Long): StudyTask? =
        taskDao.getWithSessions(taskId)?.toDomainModel()

    override suspend fun recordAiSuggestionEvent(taskId: Long, accepted: Boolean, reason: String) {
        val task = getTask(taskId) ?: return
        history.recordTaskChange(
            before = task,
            after = task,
            eventType = if (accepted) {
                TaskEventType.AI_SUGGESTION_ACCEPTED
            } else {
                TaskEventType.AI_SUGGESTION_REJECTED
            },
            source = HistorySource.AI_SUGGESTION,
            reason = reason,
            explicitEventType = true,
        )
    }

    private suspend fun createTask(task: StudyTask): TaskSaveResult {
        val normalized = task.copy(
            id = 0,
            title = task.title.trim(),
            description = task.description.trim(),
            subject = task.subject.trim(),
            status = if (task.sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED,
            completedAt = null,
            seriesId = null,
            sessions = task.sessions.map { it.copy(id = 0) },
            globalId = task.globalId.ifBlank { UUID.randomUUID().toString() },
            revision = 1,
        )
        TaskValidation.validate(normalized)
        val seriesId = if (normalized.recurrence == TaskRecurrence.NONE) null else UUID.randomUUID().toString()
        val occurrences = TaskPlanner.expandForCreation(
            task = normalized,
            seriesId = seriesId,
            firstSortOrder = taskDao.maxSortOrder() + 1,
        )
        val now = clock.millis()
        val ids = mutableListOf<Long>()
        database.withTransaction {
            occurrences.forEach { occurrence ->
                val withIdentity = occurrence.copy(
                    globalId = if (occurrences.size == 1) {
                        occurrence.globalId
                    } else {
                        UUID.randomUUID().toString()
                    },
                    revision = 1,
                )
                TaskValidation.validate(withIdentity)
                val id = taskDao.insert(withIdentity.toEntity(now, now))
                ids += id
                val sessions = withIdentity.sessions.map { session ->
                    session.toEntity(id, now)
                }
                if (sessions.isNotEmpty()) taskDao.insertSessions(sessions)
                history.recordTaskChange(
                    before = null,
                    after = withIdentity.copy(id = id),
                    eventType = TaskEventType.TASK_CREATED,
                    source = HistorySource.USER,
                    explicitEventType = true,
                )
            }
        }
        return TaskSaveResult(ids)
    }

    private suspend fun updateTask(task: StudyTask): TaskSaveResult {
        return database.withTransaction {
            val loaded = taskDao.getWithSessions(task.id)
                ?: throw IllegalArgumentException("任务不存在，可能已被删除")
            val current = loaded.toDomainModel()
            val merged = current.copy(
                title = task.title.trim(),
                description = task.description.trim(),
                subject = task.subject.trim(),
                priority = task.priority,
                estimatedMinutes = task.estimatedMinutes,
                dueDate = task.dueDate,
                sessions = task.sessions.map { it.copy(id = 0) },
                globalId = current.globalId,
                sourceType = current.sourceType,
                sourceId = current.sourceId,
                revision = current.revision + 1,
            )
            val normalized = if (current.isCompleted) {
                merged
            } else {
                TaskStateMachine.afterReplan(merged)
            }
            TaskValidation.validate(normalized)
            val now = clock.millis()
            taskDao.update(normalized.toEntity(loaded.task.createdAtEpochMillis, now))
            replaceSessions(normalized, now)
            history.recordTaskChange(
                before = current,
                after = normalized,
                eventType = HistoryRecorder.deriveTaskEvent(current, normalized, clock.zone),
            )
            TaskSaveResult(listOf(normalized.id))
        }
    }

    private suspend fun replaceSessions(task: StudyTask, now: Long) {
        taskDao.deleteSessionsForTask(task.id)
        if (task.sessions.isNotEmpty()) {
            taskDao.insertSessions(
                task.sessions.map { session -> session.toEntity(task.id, now) },
            )
        }
    }
}

internal fun StudyTask.toEntity(createdAt: Long, updatedAt: Long): StudyTaskEntity = StudyTaskEntity(
    id = id,
    title = title,
    description = description,
    subject = subject,
    priority = priority.name,
    estimatedMinutes = estimatedMinutes,
    dueDateEpochDay = dueDate?.toEpochDay(),
    status = status.name,
    completedAtEpochMillis = completedAt?.toEpochMilli(),
    seriesId = seriesId,
    recurrenceType = recurrence.name,
    recurrenceInterval = recurrenceInterval,
    recurrenceUntilEpochDay = recurrenceUntil?.toEpochDay(),
    sortOrder = sortOrder,
    postponeCount = postponeCount,
    globalId = globalId.ifBlank { if (id > 0) "task-$id" else UUID.randomUUID().toString() },
    revision = revision,
    sourceType = sourceType,
    sourceId = sourceId,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
)

internal fun com.zhixu.studyos.core.model.TaskSession.toEntity(
    taskId: Long,
    now: Long,
): TaskSessionEntity = TaskSessionEntity(
    id = id,
    taskId = taskId,
    dateEpochDay = date.toEpochDay(),
    startMinute = startMinute,
    endMinuteExclusive = endMinuteExclusive,
    createdAtEpochMillis = now,
    updatedAtEpochMillis = now,
    isLocked = isLocked,
)
