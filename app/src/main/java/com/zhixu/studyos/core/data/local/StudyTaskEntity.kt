package com.zhixu.studyos.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "study_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["seriesId"]),
        Index(value = ["dueDateEpochDay"]),
        Index(value = ["globalId"]),
    ],
)
data class StudyTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val subject: String,
    val priority: String,
    val estimatedMinutes: Int,
    val dueDateEpochDay: Long?,
    val status: String,
    val completedAtEpochMillis: Long?,
    val seriesId: String?,
    val recurrenceType: String,
    val recurrenceInterval: Int,
    val recurrenceUntilEpochDay: Long?,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "0") val postponeCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val globalId: String = "",
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
    /** 生成来源：USER、VOCABULARY、EXAM_REVIEW、AI_SUGGESTION。 */
    @ColumnInfo(defaultValue = "'USER'") val sourceType: String = "USER",
    val sourceId: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "task_sessions",
    foreignKeys = [
        ForeignKey(
            entity = StudyTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["dateEpochDay"]),
    ],
)
data class TaskSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val dateEpochDay: Long,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "1") val isLocked: Boolean = true,
)

data class TaskWithSessionsEntity(
    @Embedded val task: StudyTaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val sessions: List<TaskSessionEntity>,
)

fun StudyTaskEntity.toDomainModel(sessionEntities: List<TaskSessionEntity> = emptyList()): StudyTask = StudyTask(
    id = id,
    title = title,
    description = description,
    subject = subject,
    priority = TaskPriority.valueOf(priority),
    estimatedMinutes = estimatedMinutes,
    dueDate = dueDateEpochDay?.let(LocalDate::ofEpochDay),
    status = TaskStatus.valueOf(status),
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
    seriesId = seriesId,
    recurrence = TaskRecurrence.valueOf(recurrenceType),
    recurrenceInterval = recurrenceInterval,
    recurrenceUntil = recurrenceUntilEpochDay?.let(LocalDate::ofEpochDay),
    sortOrder = sortOrder,
    postponeCount = postponeCount,
    globalId = globalId,
    sourceType = sourceType,
    sourceId = sourceId,
    revision = revision,
    sessions = sessionEntities.sortedWith(compareBy({ it.dateEpochDay }, { it.startMinute })).map {
        it.toDomainModel()
    },
)

fun TaskSessionEntity.toDomainModel(): TaskSession = TaskSession(
    id = id,
    date = LocalDate.ofEpochDay(dateEpochDay),
    startMinute = startMinute,
    endMinuteExclusive = endMinuteExclusive,
    isLocked = isLocked,
)

fun TaskWithSessionsEntity.toDomainModel(): StudyTask = task.toDomainModel(sessions)
