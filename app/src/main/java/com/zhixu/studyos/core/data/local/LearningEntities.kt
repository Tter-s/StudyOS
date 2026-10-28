package com.zhixu.studyos.core.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TimerInterruptionReason
import com.zhixu.studyos.core.model.TimingSession
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "subjects",
    indices = [Index(value = ["name"], unique = true)],
)
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPreset: Boolean,
)

@Entity(
    tableName = "study_timing_sessions",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["taskId"]),
        Index(value = ["status"]),
        Index(value = ["dateEpochDay"]),
    ],
)
data class TimingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val taskId: Long?,
    val dateEpochDay: Long,
    val mode: String,
    val status: String,
    val plannedDurationMinutes: Int?,
    val accumulatedMillis: Long,
    val runningSinceEpochMillis: Long?,
    val interruptionCount: Int,
    val lastInterruptionReason: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val correctedAtEpochMillis: Long?,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "problems",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["status"]),
        Index(value = ["isDifficult"]),
        Index(value = ["isMistake"]),
    ],
)
data class ProblemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val textbook: String,
    val chapter: String,
    val problemNumber: String,
    val knowledgePoint: String,
    val description: String,
    val status: String,
    val independentThinkingMinutes: Int,
    val usedAiHint: Boolean,
    val blockerReason: String?,
    val isMistake: Boolean,
    val isDifficult: Boolean,
    val independentCompleted: Boolean?,
    val retryCount: Int,
    val secondAttemptIndependent: Boolean?,
    val completedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "self_control_entries",
    indices = [Index(value = ["dateEpochDay"])],
)
data class SelfControlEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val procrastinationReason: String?,
    val voluntaryInterruption: Boolean,
    val note: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "learning_goals")
data class LearningGoalsEntity(
    @PrimaryKey val id: Int = 1,
    val dailyStudyMinutes: Int?,
    val weeklyStudyMinutes: Int?,
    val dailyTaskTarget: Int?,
    val updatedAtEpochMillis: Long,
)

fun SubjectEntity.toDomainModel() = Subject(id = id, name = name, isPreset = isPreset)

fun TimingSessionEntity.toDomainModel() = TimingSession(
    id = id,
    subjectId = subjectId,
    taskId = taskId,
    studyDate = LocalDate.ofEpochDay(dateEpochDay),
    mode = StudyTimerMode.valueOf(mode),
    status = StudyTimerStatus.valueOf(status),
    plannedDurationMinutes = plannedDurationMinutes,
    accumulatedMillis = accumulatedMillis,
    runningSince = runningSinceEpochMillis?.let(Instant::ofEpochMilli),
    interruptionCount = interruptionCount,
    lastInterruptionReason = lastInterruptionReason?.let(TimerInterruptionReason::valueOf),
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    endedAt = endedAtEpochMillis?.let(Instant::ofEpochMilli),
    correctedAt = correctedAtEpochMillis?.let(Instant::ofEpochMilli),
    note = note,
)

fun ProblemEntity.toDomainModel() = Problem(
    id = id,
    subjectId = subjectId,
    textbook = textbook,
    chapter = chapter,
    problemNumber = problemNumber,
    knowledgePoint = knowledgePoint,
    description = description,
    status = ProblemStatus.valueOf(status),
    independentThinkingMinutes = independentThinkingMinutes,
    usedAiHint = usedAiHint,
    blockerReason = blockerReason?.let(BlockerReason::valueOf),
    isMistake = isMistake,
    isDifficult = isDifficult,
    independentCompleted = independentCompleted,
    retryCount = retryCount,
    secondAttemptIndependent = secondAttemptIndependent,
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

fun SelfControlEntryEntity.toDomainModel() = SelfControlEntry(
    id = id,
    date = LocalDate.ofEpochDay(dateEpochDay),
    procrastinationReason = procrastinationReason?.let(ProcrastinationReason::valueOf),
    voluntaryInterruption = voluntaryInterruption,
    note = note,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
)

fun LearningGoalsEntity.toDomainModel() = LearningGoals(
    dailyStudyMinutes = dailyStudyMinutes,
    weeklyStudyMinutes = weeklyStudyMinutes,
    dailyTaskTarget = dailyTaskTarget,
)
