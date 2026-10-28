package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.SchedulingDao
import com.zhixu.studyos.core.data.local.SchedulingSubjectPreferenceEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskDao
import com.zhixu.studyos.core.data.local.toEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.history.HistoryRecorder
import com.zhixu.studyos.core.history.HistorySource
import com.zhixu.studyos.core.history.TaskEventType
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.task.TaskValidation
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PlannedSession(
    val taskId: Long,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val isLocked: Boolean,
)

data class SchedulingSaveRequest(
    val candidateTaskIds: Set<Long>,
    val sessions: List<PlannedSession>,
)

data class SchedulingSaveResult(
    val updatedTasks: Int,
    val savedSessions: Int,
)

interface SchedulingRepository {
    fun observeSettings(): Flow<SchedulingSettings>

    fun observeSubjectPreferences(): Flow<Map<String, SubjectPreference>>

    suspend fun getSettings(): SchedulingSettings

    suspend fun saveConfiguration(
        settings: SchedulingSettings,
        preferences: Map<String, SubjectPreference>,
        now: Instant,
    )

    /**
     * 原子替换候选任务的自动时段。只删除未锁定时段；锁定、进行中和已完成任务不会被覆盖。
     */
    suspend fun applyPlan(
        request: SchedulingSaveRequest,
        today: LocalDate,
        now: Instant,
    ): SchedulingSaveResult
}

class OfflineSchedulingRepository(
    private val database: StudyOSDatabase,
    private val schedulingDao: SchedulingDao,
    private val taskDao: StudyTaskDao,
) : SchedulingRepository {
    private val history = HistoryRecorder(database.historyDao(), Clock.systemDefaultZone())
    override fun observeSettings(): Flow<SchedulingSettings> =
        schedulingDao.observeSettings().map { it?.toDomainModel() ?: SchedulingSettings.Default }

    override fun observeSubjectPreferences(): Flow<Map<String, SubjectPreference>> =
        schedulingDao.observeSubjectPreferences().map { rows ->
            rows.associate { row -> row.subjectName to row.toDomainModel().preference }
        }

    override suspend fun getSettings(): SchedulingSettings =
        schedulingDao.getSettings()?.toDomainModel() ?: SchedulingSettings.Default

    override suspend fun saveConfiguration(
        settings: SchedulingSettings,
        preferences: Map<String, SubjectPreference>,
        now: Instant,
    ) {
        settings.requireValid()
        val nowMillis = now.toEpochMilli()
        val entities = preferences.entries
            .mapNotNull { (subject, preference) ->
                val name = subject.trim()
                if (name.isEmpty()) null else SchedulingSubjectPreferenceEntity(
                    subjectName = name,
                    level = preference.name,
                    updatedAtEpochMillis = nowMillis,
                )
            }
            .distinctBy { it.subjectName.lowercase(Locale.ROOT) }
        database.withTransaction {
            schedulingDao.upsertSettings(settings.toEntity(nowMillis))
            schedulingDao.upsertSubjectPreferences(entities)
        }
    }

    override suspend fun applyPlan(
        request: SchedulingSaveRequest,
        today: LocalDate,
        now: Instant,
    ): SchedulingSaveResult {
        validateRequest(request, today)
        if (request.candidateTaskIds.isEmpty()) return SchedulingSaveResult(0, 0)
        val candidateIds = request.candidateTaskIds.toList()
        val nowMillis = now.toEpochMilli()
        return database.withTransaction {
            val loaded = candidateIds.associateWith { taskDao.getWithSessions(it) }
            loaded.forEach { (taskId, row) ->
                requireNotNull(row) { "任务 $taskId 不存在，可能已被删除，请重新生成计划" }
                require(
                    row.task.status != TaskStatus.COMPLETED.name &&
                        row.task.status != TaskStatus.IN_PROGRESS.name,
                ) { "任务「${row.task.title}」状态已变化，请重新生成计划" }
            }

            val proposedByTask = request.sessions.groupBy(PlannedSession::taskId)
            val lockedSessions = loaded.values.mapNotNull { it }.flatMap { row ->
                row.sessions.filter { it.isLocked }.map { session ->
                    Triple(row.task.title, session.toDomainModel(), row.task.id)
                }
            }
            request.sessions.forEach { planned ->
                val plannedSession = planned.toTaskSession()
                lockedSessions.firstOrNull { (_, session, taskId) ->
                    taskId != planned.taskId &&
                        session.date == plannedSession.date &&
                        plannedSession.startMinute < session.endMinuteExclusive &&
                        session.startMinute < plannedSession.endMinuteExclusive
                }?.let { (title, _, _) ->
                    throw IllegalArgumentException("新排时段与锁定任务「$title」重叠，请重新生成计划")
                }
            }

            taskDao.deleteUnlockedSessions(candidateIds)

            var updatedTasks = 0
            loaded.forEach { (taskId, row) ->
                val loadedRow = requireNotNull(row)
                val current = loadedRow.toDomainModel()
                val locked = loadedRow.sessions
                    .filter { it.isLocked }
                    .map { it.toDomainModel() }
                val proposed = proposedByTask[taskId].orEmpty().map { it.toTaskSession() }
                val sessions = (locked + proposed).sortedWith(
                    compareBy(TaskSession::date, TaskSession::startMinute),
                )
                val status = if (sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED
                val updated = current.copy(
                    status = status,
                    completedAt = null,
                    sessions = sessions,
                    revision = current.revision + 1,
                )
                TaskValidation.validate(updated)
                taskDao.update(updated.toEntity(loadedRow.task.createdAtEpochMillis, nowMillis))
                if (proposed.isNotEmpty()) {
                    taskDao.insertSessions(proposed.map { it.toEntity(taskId, nowMillis) })
                }
                history.recordTaskChange(
                    before = current,
                    after = updated,
                    eventType = TaskEventType.AUTO_SCHEDULED,
                    source = HistorySource.LOCAL_SCHEDULER,
                    reason = "本地排程引擎保存计划",
                    explicitEventType = true,
                    occurredAt = now,
                )
                updatedTasks += 1
            }
            SchedulingSaveResult(updatedTasks = updatedTasks, savedSessions = request.sessions.size)
        }
    }

    private fun validateRequest(request: SchedulingSaveRequest, today: LocalDate) {
        request.sessions.forEach { planned ->
            require(planned.taskId in request.candidateTaskIds) { "新排时段包含不在候选列表中的任务" }
            require(!planned.date.isBefore(today)) { "不能在今天以前安排学习时段" }
            require(planned.startMinute in 0 until MINUTES_PER_DAY) { "学习时段开始时间无效" }
            require(planned.endMinuteExclusive in 1..MINUTES_PER_DAY) { "学习时段结束时间无效" }
            require(planned.endMinuteExclusive > planned.startMinute) { "学习时段结束时间必须晚于开始时间" }
        }
        request.sessions.groupBy(PlannedSession::date).values.forEach { daySessions ->
            val sorted = daySessions.sortedBy(PlannedSession::startMinute)
            sorted.zipWithNext().forEach { (first, second) ->
                require(second.startMinute >= first.endMinuteExclusive) { "排程时段互相重叠" }
            }
        }
    }

    private fun PlannedSession.toTaskSession() = TaskSession(
        id = 0,
        date = date,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        isLocked = isLocked,
    )
}
