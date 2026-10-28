package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.ExamEntity
import com.zhixu.studyos.core.data.local.ExamGoalDao
import com.zhixu.studyos.core.data.local.ExerciseLearningDao
import com.zhixu.studyos.core.data.local.GoalMilestoneEntity
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.LongTermGoalEntity
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.exam.Exam
import com.zhixu.studyos.core.exam.ExamCountdownCalculator
import com.zhixu.studyos.core.exam.ExamReviewPlan
import com.zhixu.studyos.core.exam.ExamReviewPlanItem
import com.zhixu.studyos.core.exam.ExamReviewPlanner
import com.zhixu.studyos.core.exam.GoalMilestone
import com.zhixu.studyos.core.exam.GoalProgressCalculator
import com.zhixu.studyos.core.exam.GoalProgress
import com.zhixu.studyos.core.exam.LongTermGoal
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ExamGoalSnapshot(
    val exams: List<Exam> = emptyList(),
    val goals: List<LongTermGoal> = emptyList(),
    val milestones: Map<Long, List<GoalMilestone>> = emptyMap(),
)

data class ExamTaskGenerationResult(
    val plan: ExamReviewPlan,
    val createdTaskIds: List<Long>,
    val skippedExisting: Int,
)

interface ExamGoalRepository {
    fun observeSnapshot(): Flow<ExamGoalSnapshot>
    suspend fun getSnapshot(): ExamGoalSnapshot
    suspend fun saveExam(exam: Exam): Long
    suspend fun deleteExam(examId: Long)
    suspend fun saveGoal(goal: LongTermGoal): Long
    suspend fun deleteGoal(goalId: Long)
    suspend fun saveMilestone(milestone: GoalMilestone): Long
    suspend fun toggleMilestone(milestoneId: Long, completed: Boolean)
    suspend fun deleteMilestone(milestoneId: Long)
    suspend fun nextExam(today: LocalDate): Exam?
    suspend fun goalProgress(goalId: Long, today: LocalDate): GoalProgress?
    suspend fun generateReviewTasks(
        examId: Long,
        today: LocalDate,
        availableDaysHint: Int? = null,
    ): ExamTaskGenerationResult
}

class OfflineExamGoalRepository(
    private val database: StudyOSDatabase,
    private val dao: ExamGoalDao,
    private val exerciseLearningDao: ExerciseLearningDao,
    private val problemBankDao: ProblemBankDao,
    private val taskRepository: TaskRepository,
    private val historyDao: HistoryDao,
    private val clock: Clock,
) : ExamGoalRepository {

    override fun observeSnapshot(): Flow<ExamGoalSnapshot> = combine(
        dao.observeExams(),
        dao.observeGoals(),
        dao.observeMilestones(),
    ) { exams, goals, milestones ->
        ExamGoalSnapshot(
            exams = exams.map { it.toDomainModel() },
            goals = goals.map { it.toDomainModel() },
            milestones = milestones.map { it.toDomainModel() }.groupBy { it.goalId },
        )
    }

    override suspend fun getSnapshot(): ExamGoalSnapshot = ExamGoalSnapshot(
        exams = dao.getExams().map { it.toDomainModel() },
        goals = dao.getGoals().map { it.toDomainModel() },
        milestones = dao.getMilestones().map { it.toDomainModel() }.groupBy { it.goalId },
    )

    override suspend fun saveExam(exam: Exam): Long {
        exam.validationError()?.let { throw IllegalArgumentException(it) }
        val now = clock.millis()
        return database.withTransaction {
            val existing = if (exam.id == 0L) null else {
                dao.getExam(exam.id) ?: throw IllegalArgumentException("考试不存在，可能已被删除")
            }
            val entity = ExamEntity(
                id = exam.id,
                name = exam.name.trim(),
                subject = exam.subject.trim(),
                dateEpochDay = exam.date.toEpochDay(),
                startMinute = exam.startMinute,
                durationMinutes = exam.durationMinutes,
                scope = exam.scope.trim(),
                targetScore = exam.targetScore.trim(),
                materials = exam.materials.trim(),
                reminderEnabled = exam.reminderEnabled,
                reminderDaysBefore = exam.reminderDaysBefore,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                revision = (existing?.revision ?: 0) + 1,
            )
            val id = if (existing == null) dao.insertExam(entity) else {
                dao.updateExam(entity)
                exam.id
            }
            historyDao.insert(
                TaskEventEntity(
                    objectType = "EXAM",
                    objectId = id,
                    eventType = if (existing == null) "EXAM_CREATED" else "EXAM_EDITED",
                    occurredAtEpochMillis = now,
                    beforeJson = existing?.let { org.json.JSONObject().put("name", it.name).put("date", it.dateEpochDay).toString() },
                    afterJson = org.json.JSONObject().put("name", entity.name).put("date", entity.dateEpochDay).toString(),
                    source = "USER",
                    reason = null,
                    sessionId = null,
                    revision = entity.revision,
                ),
            )
            id
        }
    }

    override suspend fun deleteExam(examId: Long) {
        database.withTransaction {
            dao.getExam(examId)?.let { existing ->
                historyDao.insert(
                    TaskEventEntity(
                        objectType = "EXAM",
                        objectId = examId,
                        eventType = "EXAM_EDITED",
                        occurredAtEpochMillis = clock.millis(),
                        beforeJson = org.json.JSONObject().put("name", existing.name).toString(),
                        afterJson = null,
                        source = "USER",
                        reason = "删除考试",
                        sessionId = null,
                        revision = existing.revision,
                    ),
                )
            }
            dao.deleteExam(examId)
        }
    }

    override suspend fun saveGoal(goal: LongTermGoal): Long {
        goal.validationError()?.let { throw IllegalArgumentException(it) }
        val now = clock.millis()
        return database.withTransaction {
            val existing = if (goal.id == 0L) null else {
                dao.getGoal(goal.id) ?: throw IllegalArgumentException("目标不存在，可能已被删除")
            }
            val entity = LongTermGoalEntity(
                id = goal.id,
                title = goal.title.trim(),
                category = goal.category.trim().ifEmpty { "自定义" },
                description = goal.description.trim(),
                startDateEpochDay = goal.startDate.toEpochDay(),
                targetDateEpochDay = goal.targetDate?.toEpochDay(),
                status = goal.status,
                progressSource = goal.progressSource,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                revision = (existing?.revision ?: 0) + 1,
            )
            val id = if (existing == null) dao.insertGoal(entity) else {
                dao.updateGoal(entity)
                goal.id
            }
            historyDao.insert(
                TaskEventEntity(
                    objectType = "GOAL",
                    objectId = id,
                    eventType = when {
                        existing == null -> "GOAL_CREATED"
                        entity.status == "COMPLETED" -> "GOAL_COMPLETED"
                        else -> "GOAL_EDITED"
                    },
                    occurredAtEpochMillis = now,
                    beforeJson = existing?.let { org.json.JSONObject().put("title", it.title).put("status", it.status).toString() },
                    afterJson = org.json.JSONObject().put("title", entity.title).put("status", entity.status).toString(),
                    source = "USER",
                    reason = null,
                    sessionId = null,
                    revision = entity.revision,
                ),
            )
            id
        }
    }

    override suspend fun deleteGoal(goalId: Long) {
        database.withTransaction {
            dao.getGoal(goalId)?.let { existing ->
                historyDao.insert(
                    TaskEventEntity(
                        objectType = "GOAL",
                        objectId = goalId,
                        eventType = "GOAL_EDITED",
                        occurredAtEpochMillis = clock.millis(),
                        beforeJson = org.json.JSONObject().put("title", existing.title).toString(),
                        afterJson = null,
                        source = "USER",
                        reason = "删除长期目标",
                        sessionId = null,
                        revision = existing.revision,
                    ),
                )
            }
            dao.deleteGoal(goalId)
        }
    }

    override suspend fun saveMilestone(milestone: GoalMilestone): Long {
        require(milestone.goalId > 0) { "请选择目标" }
        require(milestone.title.isNotBlank()) { "请填写里程碑名称" }
        require(milestone.weight in 1..10) { "里程碑权重应在 1–10 之间" }
        val now = clock.millis()
        return database.withTransaction {
            val goal = dao.getGoal(milestone.goalId) ?: throw IllegalArgumentException("目标不存在")
            val existing = if (milestone.id == 0L) null else dao.getMilestones()
                .firstOrNull { it.id == milestone.id }
            val entity = GoalMilestoneEntity(
                id = milestone.id,
                goalId = milestone.goalId,
                title = milestone.title.trim(),
                dueDateEpochDay = milestone.dueDate?.toEpochDay(),
                weight = milestone.weight.coerceIn(1, 10),
                status = milestone.status,
                completedAtEpochMillis = milestone.completedAt?.toEpochMilli(),
                sortOrder = milestone.sortOrder,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            )
            val id = if (existing == null) dao.insertMilestone(entity) else {
                dao.updateMilestone(entity)
                milestone.id
            }
            dao.touchGoal(goal.id, now)
            id
        }
    }

    override suspend fun toggleMilestone(milestoneId: Long, completed: Boolean) {
        val now = clock.millis()
        database.withTransaction {
            val milestone = dao.getMilestones().firstOrNull { it.id == milestoneId }
                ?: throw IllegalArgumentException("里程碑不存在")
            dao.updateMilestone(
                milestone.copy(
                    status = if (completed) "COMPLETED" else "PENDING",
                    completedAtEpochMillis = if (completed) now else null,
                    updatedAtEpochMillis = now,
                ),
            )
            dao.touchGoal(milestone.goalId, now)
        }
    }

    override suspend fun deleteMilestone(milestoneId: Long) {
        val now = clock.millis()
        database.withTransaction {
            val milestone = dao.getMilestones().firstOrNull { it.id == milestoneId } ?: return@withTransaction
            dao.deleteMilestone(milestoneId)
            dao.touchGoal(milestone.goalId, now)
        }
    }

    override suspend fun nextExam(today: LocalDate): Exam? =
        ExamCountdownCalculator.next(dao.getExams().map { it.toDomainModel() }, today)?.exam

    override suspend fun goalProgress(goalId: Long, today: LocalDate): GoalProgress? {
        val goal = dao.getGoal(goalId)?.toDomainModel() ?: return null
        val milestones = dao.getMilestones().filter { it.goalId == goalId }.map { it.toDomainModel() }
        return GoalProgressCalculator.calculate(goal, milestones, today)
    }

    /**
     * 根据考试范围、未完成习题与近期失败章节生成“待安排”复习任务。
     * 已存在同考试同一天的任务会跳过，重复点击不会无限堆积。
     */
    override suspend fun generateReviewTasks(
        examId: Long,
        today: LocalDate,
        availableDaysHint: Int?,
    ): ExamTaskGenerationResult {
        val exam = dao.getExam(examId)?.toDomainModel()
            ?: throw IllegalArgumentException("考试不存在，可能已被删除")
        val since = today.minusDays(60).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val bankProblems = problemBankDao.allProblemSummaries().associateBy { it.id }
        val failedByChapter = exerciseLearningDao.attemptsBetween(since, clock.millis())
            .asSequence()
            .filter { !it.correct && it.problemType == "BANK" }
            .mapNotNull { bankProblems[it.problemId] }
            .filter { problem -> matchesExamSubject(exam.subject, problem.courseName) }
            .filter { it.chapter.isNotBlank() }
            .groupBy { it.chapter }
            .map { (chapter, attempts) ->
                ExamReviewPlanner.WeakChapter(chapter = chapter, failureCount = attempts.size)
            }
        val unfinished = bankProblems.values.count { problem ->
            problem.completionStatus != "COMPLETED" &&
                matchesExamSubject(exam.subject, problem.courseName)
        }
        val plan = ExamReviewPlanner.build(
            exam = exam,
            today = today,
            weakChapters = failedByChapter,
            unfinishedProblemCount = unfinished,
            materials = exam.materials,
            availableDaysHint = availableDaysHint,
        )
        val created = mutableListOf<Long>()
        var skipped = 0
        plan.items.forEach { item ->
            val sourceId = "exam-$examId-${item.date}"
            val existing = database.studyTaskDao().findBySource("EXAM_REVIEW", sourceId)
            if (existing != null) {
                skipped += 1
                return@forEach
            }
            val result = taskRepository.saveTask(
                StudyTask(
                    title = item.title,
                    description = "${item.reason}\n考试范围：${exam.scope.ifBlank { "未填写" }}" +
                        "\n目标成绩：${exam.targetScore.ifBlank { "未设置" }}",
                    subject = item.subject,
                    estimatedMinutes = item.estimatedMinutes,
                    dueDate = exam.date,
                    status = TaskStatus.BACKLOG,
                    sourceType = "EXAM_REVIEW",
                    sourceId = sourceId,
                ),
            )
            created += result.createdTaskIds
        }
        return ExamTaskGenerationResult(
            plan = plan,
            createdTaskIds = created,
            skippedExisting = skipped,
        )
    }
}

private fun matchesExamSubject(examSubject: String, courseName: String): Boolean {
    if (examSubject.isBlank()) return true
    if (courseName.isBlank()) return false
    return courseName.contains(examSubject, ignoreCase = true) ||
        examSubject.contains(courseName.take(3), ignoreCase = true)
}

private fun ExamEntity.toDomainModel() = Exam(
    id = id,
    name = name,
    subject = subject,
    date = LocalDate.ofEpochDay(dateEpochDay),
    startMinute = startMinute,
    durationMinutes = durationMinutes,
    scope = scope,
    targetScore = targetScore,
    materials = materials,
    reminderEnabled = reminderEnabled,
    reminderDaysBefore = reminderDaysBefore,
    revision = revision,
)

private fun LongTermGoalEntity.toDomainModel() = LongTermGoal(
    id = id,
    title = title,
    category = category,
    description = description,
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    targetDate = targetDateEpochDay?.let(LocalDate::ofEpochDay),
    status = status,
    progressSource = progressSource,
    revision = revision,
)

private fun GoalMilestoneEntity.toDomainModel() = GoalMilestone(
    id = id,
    goalId = goalId,
    title = title,
    dueDate = dueDateEpochDay?.let(LocalDate::ofEpochDay),
    weight = weight,
    status = status,
    completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
    sortOrder = sortOrder,
)
