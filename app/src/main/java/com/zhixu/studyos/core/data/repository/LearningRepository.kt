package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.LearningDao
import com.zhixu.studyos.core.data.local.LearningGoalsEntity
import com.zhixu.studyos.core.data.local.ProblemEntity
import com.zhixu.studyos.core.data.local.SelfControlEntryEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.SubjectEntity
import com.zhixu.studyos.core.data.local.TimingSessionEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.LearningSnapshot
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TimerInterruptionReason
import com.zhixu.studyos.core.model.TimingSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface LearningRepository {
    fun observeSnapshot(): Flow<LearningSnapshot>
    suspend fun getActiveTiming(): TimingSession?
    suspend fun addSubject(name: String): Subject
    suspend fun startTiming(
        subjectId: Long,
        taskId: Long?,
        mode: StudyTimerMode,
        plannedDurationMinutes: Int?,
        now: Instant,
    ): TimingSession
    suspend fun checkpointTiming(sessionId: Long, now: Instant)
    suspend fun pauseTiming(sessionId: Long, reason: TimerInterruptionReason, now: Instant)
    suspend fun resumeTiming(sessionId: Long, now: Instant)
    suspend fun finishTiming(sessionId: Long, now: Instant): TimingSession
    suspend fun recoverRunningSessions(now: Instant): Int
    suspend fun addManualTiming(
        subjectId: Long,
        taskId: Long?,
        studyDate: LocalDate,
        startedAt: Instant,
        durationMinutes: Int,
        note: String,
        now: Instant,
    ): TimingSession
    suspend fun correctTiming(sessionId: Long, durationMinutes: Int, note: String, now: Instant)
    suspend fun deleteTiming(sessionId: Long)
    suspend fun saveProblem(problem: Problem, now: Instant): Long
    suspend fun deleteProblem(problemId: Long)
    suspend fun retryProblem(problemId: Long, now: Instant)
    suspend fun addSelfControlEntry(
        date: LocalDate,
        procrastinationReason: ProcrastinationReason?,
        voluntaryInterruption: Boolean,
        note: String,
        now: Instant,
    ): SelfControlEntry
    suspend fun deleteSelfControlEntry(entryId: Long)
    suspend fun saveGoals(goals: LearningGoals, now: Instant)
}

class OfflineLearningRepository(
    private val database: StudyOSDatabase,
    private val learningDao: LearningDao,
    private val clock: Clock,
) : LearningRepository {
    override fun observeSnapshot(): Flow<LearningSnapshot> = combine(
        learningDao.observeSubjects(),
        learningDao.observeTimingSessions(),
        learningDao.observeProblems(),
        learningDao.observeSelfControlEntries(),
        learningDao.observeGoals(),
    ) { subjects, timings, problems, entries, goals ->
        LearningSnapshot(
            subjects = subjects.map { it.toDomainModel() },
            timings = timings.map { it.toDomainModel() },
            problems = problems.map { it.toDomainModel() },
            selfControlEntries = entries.map { it.toDomainModel() },
            goals = goals?.toDomainModel() ?: LearningGoals(),
        )
    }

    override suspend fun getActiveTiming(): TimingSession? =
        learningDao.getActiveTimingSession()?.toDomainModel()

    override suspend fun addSubject(name: String): Subject {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入科目名称" }
        require(trimmed.length <= 30) { "科目名称不能超过 30 个字符" }
        require(learningDao.getSubjectByName(trimmed) == null) { "该科目已存在" }
        val id = learningDao.insertSubject(SubjectEntity(name = trimmed, isPreset = false))
        return Subject(id = id, name = trimmed, isPreset = false)
    }

    override suspend fun startTiming(
        subjectId: Long,
        taskId: Long?,
        mode: StudyTimerMode,
        plannedDurationMinutes: Int?,
        now: Instant,
    ): TimingSession {
        require(mode != StudyTimerMode.MANUAL) { "手动补录请使用补录入口" }
        require(learningDao.getSubject(subjectId) != null) { "科目不存在" }
        require(learningDao.getActiveTimingSession() == null) { "已有计时会话，请先结束当前计时" }
        if (mode == StudyTimerMode.POMODORO) {
            require(plannedDurationMinutes != null && plannedDurationMinutes in 1..180) {
                "番茄钟时长应在 1–180 分钟之间"
            }
        }
        val nowMillis = now.toEpochMilli()
        val entity = TimingSessionEntity(
            subjectId = subjectId,
            taskId = taskId,
            dateEpochDay = now.atZone(clock.zone).toLocalDate().toEpochDay(),
            mode = mode.name,
            status = StudyTimerStatus.RUNNING.name,
            plannedDurationMinutes = plannedDurationMinutes.takeIf { mode == StudyTimerMode.POMODORO },
            accumulatedMillis = 0,
            runningSinceEpochMillis = nowMillis,
            interruptionCount = 0,
            lastInterruptionReason = null,
            startedAtEpochMillis = nowMillis,
            endedAtEpochMillis = null,
            correctedAtEpochMillis = null,
            note = "",
            createdAtEpochMillis = nowMillis,
            updatedAtEpochMillis = nowMillis,
        )
        val id = learningDao.insertTimingSession(entity)
        return entity.copy(id = id).toDomainModel()
    }

    override suspend fun checkpointTiming(sessionId: Long, now: Instant) {
        database.withTransaction {
            val entity = learningDao.getTimingSession(sessionId) ?: throw IllegalArgumentException("计时会话不存在")
            if (entity.status != StudyTimerStatus.RUNNING.name) return@withTransaction
            val runningSince = entity.runningSinceEpochMillis ?: now.toEpochMilli()
            val segment = (now.toEpochMilli() - runningSince).coerceAtLeast(0L)
            learningDao.updateTimingSession(
                entity.copy(
                    accumulatedMillis = entity.accumulatedMillis + segment,
                    runningSinceEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun pauseTiming(sessionId: Long, reason: TimerInterruptionReason, now: Instant) {
        database.withTransaction {
            val entity = learningDao.getTimingSession(sessionId) ?: throw IllegalArgumentException("计时会话不存在")
            if (entity.status == StudyTimerStatus.PAUSED.name) return@withTransaction
            require(entity.status == StudyTimerStatus.RUNNING.name) { "当前会话不能暂停" }
            val runningSince = entity.runningSinceEpochMillis ?: now.toEpochMilli()
            val segment = (now.toEpochMilli() - runningSince).coerceAtLeast(0L)
            learningDao.updateTimingSession(
                entity.copy(
                    status = StudyTimerStatus.PAUSED.name,
                    accumulatedMillis = entity.accumulatedMillis + segment,
                    runningSinceEpochMillis = null,
                    interruptionCount = entity.interruptionCount + 1,
                    lastInterruptionReason = reason.name,
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun resumeTiming(sessionId: Long, now: Instant) {
        database.withTransaction {
            val entity = learningDao.getTimingSession(sessionId) ?: throw IllegalArgumentException("计时会话不存在")
            require(entity.status == StudyTimerStatus.PAUSED.name) { "只有暂停中的计时可以继续" }
            require(learningDao.getActiveTimingSession()?.id == sessionId) { "已有其他计时会话" }
            learningDao.updateTimingSession(
                entity.copy(
                    status = StudyTimerStatus.RUNNING.name,
                    runningSinceEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun finishTiming(sessionId: Long, now: Instant): TimingSession {
        return database.withTransaction {
            val entity = learningDao.getTimingSession(sessionId) ?: throw IllegalArgumentException("计时会话不存在")
            if (entity.status == StudyTimerStatus.COMPLETED.name) return@withTransaction entity.toDomainModel()
            val segment = if (entity.status == StudyTimerStatus.RUNNING.name) {
                val runningSince = entity.runningSinceEpochMillis ?: now.toEpochMilli()
                (now.toEpochMilli() - runningSince).coerceAtLeast(0L)
            } else {
                0L
            }
            val finished = entity.copy(
                status = StudyTimerStatus.COMPLETED.name,
                accumulatedMillis = entity.accumulatedMillis + segment,
                runningSinceEpochMillis = null,
                endedAtEpochMillis = now.toEpochMilli(),
                updatedAtEpochMillis = now.toEpochMilli(),
            )
            learningDao.updateTimingSession(finished)
            finished.toDomainModel()
        }
    }

    override suspend fun recoverRunningSessions(now: Instant): Int {
        return database.withTransaction {
            val running = learningDao.getRunningTimingSessions()
            running.forEach { entity ->
                // Only the last persisted checkpoint is trusted; unknown process-death time is discarded.
                learningDao.updateTimingSession(
                    entity.copy(
                        status = StudyTimerStatus.PAUSED.name,
                        runningSinceEpochMillis = null,
                        interruptionCount = entity.interruptionCount + 1,
                        lastInterruptionReason = TimerInterruptionReason.PROCESS_RECOVERY.name,
                        updatedAtEpochMillis = now.toEpochMilli(),
                    ),
                )
            }
            running.size
        }
    }

    override suspend fun addManualTiming(
        subjectId: Long,
        taskId: Long?,
        studyDate: LocalDate,
        startedAt: Instant,
        durationMinutes: Int,
        note: String,
        now: Instant,
    ): TimingSession {
        require(learningDao.getSubject(subjectId) != null) { "科目不存在" }
        require(durationMinutes in 1..1440) { "学习时长应在 1–1440 分钟之间" }
        require(learningDao.getActiveTimingSession() == null) { "有进行中的计时，不能补录新的完成记录" }
        val durationMillis = durationMinutes * 60_000L
        val entity = TimingSessionEntity(
            subjectId = subjectId,
            taskId = taskId,
            dateEpochDay = studyDate.toEpochDay(),
            mode = StudyTimerMode.MANUAL.name,
            status = StudyTimerStatus.COMPLETED.name,
            plannedDurationMinutes = null,
            accumulatedMillis = durationMillis,
            runningSinceEpochMillis = null,
            interruptionCount = 0,
            lastInterruptionReason = null,
            startedAtEpochMillis = startedAt.toEpochMilli(),
            endedAtEpochMillis = startedAt.plusMillis(durationMillis).toEpochMilli(),
            correctedAtEpochMillis = null,
            note = note.trim(),
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        val id = learningDao.insertTimingSession(entity)
        return entity.copy(id = id).toDomainModel()
    }

    override suspend fun correctTiming(sessionId: Long, durationMinutes: Int, note: String, now: Instant) {
        require(durationMinutes in 1..1440) { "学习时长应在 1–1440 分钟之间" }
        val entity = learningDao.getTimingSession(sessionId) ?: throw IllegalArgumentException("计时会话不存在")
        require(entity.status == StudyTimerStatus.COMPLETED.name) { "只能修正已完成的记录" }
        learningDao.updateTimingSession(
            entity.copy(
                accumulatedMillis = durationMinutes * 60_000L,
                note = note.trim(),
                correctedAtEpochMillis = now.toEpochMilli(),
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun deleteTiming(sessionId: Long) = learningDao.deleteTimingSession(sessionId)

    override suspend fun saveProblem(problem: Problem, now: Instant): Long {
        require(problem.subjectId > 0 && learningDao.getSubject(problem.subjectId) != null) { "请选择有效科目" }
        require(
            problem.problemNumber.isNotBlank() || problem.description.isNotBlank() || problem.knowledgePoint.isNotBlank(),
        ) { "请填写题号、知识点或题目说明" }
        require(problem.independentThinkingMinutes in 0..1440) { "独立思考时长应在 0–1440 分钟之间" }
        require(problem.retryCount >= 0) { "重练次数无效" }
        val completedAt = when (problem.status) {
            ProblemStatus.COMPLETED -> problem.completedAt ?: now
            ProblemStatus.TODO, ProblemStatus.IN_PROGRESS -> null
        }
        val existing = if (problem.id == 0L) null else learningDao.getProblem(problem.id)
        if (problem.id != 0L && existing == null) throw IllegalArgumentException("习题不存在，可能已被删除")
        val createdAt = existing?.createdAtEpochMillis ?: now.toEpochMilli()
        val entity = ProblemEntity(
            id = problem.id,
            subjectId = problem.subjectId,
            textbook = problem.textbook.trim(),
            chapter = problem.chapter.trim(),
            problemNumber = problem.problemNumber.trim(),
            knowledgePoint = problem.knowledgePoint.trim(),
            description = problem.description.trim(),
            status = problem.status.name,
            independentThinkingMinutes = problem.independentThinkingMinutes,
            usedAiHint = problem.usedAiHint,
            blockerReason = problem.blockerReason?.name,
            isMistake = problem.isMistake,
            isDifficult = problem.isDifficult || problem.blockerReason != null,
            independentCompleted = problem.independentCompleted,
            retryCount = problem.retryCount,
            secondAttemptIndependent = if (problem.retryCount > 0) problem.secondAttemptIndependent else null,
            completedAtEpochMillis = completedAt?.toEpochMilli(),
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        return if (problem.id == 0L) {
            learningDao.insertProblem(entity)
        } else {
            learningDao.updateProblem(entity)
            problem.id
        }
    }

    override suspend fun deleteProblem(problemId: Long) = learningDao.deleteProblem(problemId)

    override suspend fun retryProblem(problemId: Long, now: Instant) {
        val entity = learningDao.getProblem(problemId) ?: throw IllegalArgumentException("习题不存在")
        require(entity.status == ProblemStatus.COMPLETED.name) { "只有已完成的习题可以重新练习" }
        learningDao.updateProblem(
            entity.copy(
                status = ProblemStatus.IN_PROGRESS.name,
                retryCount = entity.retryCount + 1,
                secondAttemptIndependent = null,
                completedAtEpochMillis = null,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }

    override suspend fun addSelfControlEntry(
        date: LocalDate,
        procrastinationReason: ProcrastinationReason?,
        voluntaryInterruption: Boolean,
        note: String,
        now: Instant,
    ): SelfControlEntry {
        val trimmed = note.trim()
        require(procrastinationReason != null || voluntaryInterruption || trimmed.isNotEmpty()) {
            "请至少记录拖延原因、主动中断或备注"
        }
        val entity = SelfControlEntryEntity(
            dateEpochDay = date.toEpochDay(),
            procrastinationReason = procrastinationReason?.name,
            voluntaryInterruption = voluntaryInterruption,
            note = trimmed,
            createdAtEpochMillis = now.toEpochMilli(),
        )
        val id = learningDao.insertSelfControlEntry(entity)
        return entity.copy(id = id).toDomainModel()
    }

    override suspend fun deleteSelfControlEntry(entryId: Long) = learningDao.deleteSelfControlEntry(entryId)

    override suspend fun saveGoals(goals: LearningGoals, now: Instant) {
        require(goals.dailyStudyMinutes == null || goals.dailyStudyMinutes in 1..1440) { "每日学习目标无效" }
        require(goals.weeklyStudyMinutes == null || goals.weeklyStudyMinutes in 1..10_080) { "每周学习目标无效" }
        require(goals.dailyTaskTarget == null || goals.dailyTaskTarget in 1..100) { "每日任务目标无效" }
        learningDao.upsertGoals(
            LearningGoalsEntity(
                dailyStudyMinutes = goals.dailyStudyMinutes,
                weeklyStudyMinutes = goals.weeklyStudyMinutes,
                dailyTaskTarget = goals.dailyTaskTarget,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )
    }
}

private fun Instant.plusMillis(millis: Long): Instant = plus(Duration.ofMillis(millis))
