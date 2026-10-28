package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.ExerciseAttemptEntity
import com.zhixu.studyos.core.data.local.ExerciseLearningDao
import com.zhixu.studyos.core.data.local.ExerciseProfileEntity
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.LearningDao
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.exercise.AbilitySnapshot
import com.zhixu.studyos.core.exercise.ExerciseAbilityEngine
import com.zhixu.studyos.core.exercise.ExerciseAttempt
import com.zhixu.studyos.core.exercise.ExerciseCandidate
import com.zhixu.studyos.core.exercise.ExerciseProblemType
import com.zhixu.studyos.core.exercise.ExerciseProfile
import com.zhixu.studyos.core.exercise.ExerciseRecommendation
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProblemReviewStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class SubmitAttemptRequest(
    val problemType: ExerciseProblemType,
    val problemId: Long,
    val durationMinutes: Int,
    val highestHintLevel: Int,
    val independent: Boolean,
    val correct: Boolean,
    val note: String = "",
)

data class SubmitAttemptResult(
    val attempt: ExerciseAttempt,
    val profile: ExerciseProfile,
    val suggestedEstimatedMinutes: Int?,
    /** 多次失败时基于知识图谱给出的前置知识复习建议；单次失败不会触发。 */
    val reviewSuggestion: String? = null,
)

interface ExerciseRepository {
    suspend fun attempts(problemType: ExerciseProblemType, problemId: Long): List<ExerciseAttempt>
    suspend fun profile(problemType: ExerciseProblemType, problemId: Long): ExerciseProfile?
    suspend fun submitAttempt(request: SubmitAttemptRequest, now: Instant = Instant.now()): SubmitAttemptResult
    suspend fun recommendations(limit: Int = 10): List<ExerciseRecommendation>
    suspend fun abilitySnapshot(): AbilitySnapshot
    suspend fun dueReviewProblemIds(limit: Int = 20): List<Long>
}

class OfflineExerciseRepository(
    private val database: StudyOSDatabase,
    private val exerciseDao: ExerciseLearningDao,
    private val problemBankDao: ProblemBankDao,
    private val problemBankRepository: ProblemBankRepository,
    private val learningDao: LearningDao,
    private val knowledgeRepository: KnowledgeRepository,
    private val historyDao: HistoryDao,
    private val clock: Clock,
) : ExerciseRepository {

    override suspend fun attempts(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): List<ExerciseAttempt> = exerciseDao.attemptsFor(problemType.name, problemId).map { it.toDomainModel() }

    override suspend fun profile(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): ExerciseProfile? = exerciseDao.getProfile(problemType.name, problemId)?.toDomainModel()

    override suspend fun submitAttempt(
        request: SubmitAttemptRequest,
        now: Instant,
    ): SubmitAttemptResult {
        require(request.durationMinutes in 0..1440) { "本次用时应在 0–1440 分钟之间" }
        require(request.highestHintLevel in 0..5) { "提示等级无效" }
        val existing = exerciseDao.attemptsFor(request.problemType.name, request.problemId)
        val attemptIndex = existing.size + 1
        val nowMillis = now.toEpochMilli()
        val attemptStartMillis = nowMillis - request.durationMinutes * 60_000L
        // 只统计“本次尝试窗口内”的提示。历史上曾经看过提示，不能让之后独立
        // 完成的尝试永久变成非独立；这正是周报/成长独立率失真的来源。
        val recordedHintLevel = attemptHintLevel(
            hints = exerciseDao.hintsFor(request.problemType.name, request.problemId),
            attemptStartEpochMillis = attemptStartMillis,
            attemptEndEpochMillis = nowMillis,
        )
        val highestHintLevel = maxOf(request.highestHintLevel, recordedHintLevel)
        val independent = request.independent && highestHintLevel == 0
        var baseDifficulty: Int? = null
        var baseMinutes: Int? = null
        var knowledgeNames: List<String> = emptyList()
        var subject = ""
        var chapter = ""
        var title = "习题 #${request.problemId}"
        var isReviewAttempt = false
        var pendingBankUpdate: com.zhixu.studyos.core.model.ProblemBankEntry? = null
        var pendingManualUpdate: com.zhixu.studyos.core.data.local.ProblemEntity? = null
        when (request.problemType) {
            ExerciseProblemType.BANK -> {
                val problem = problemBankRepository.getProblem(request.problemId)
                    ?: throw IllegalArgumentException("题目不存在，可能已被删除")
                baseDifficulty = problem.estimatedDifficulty
                baseMinutes = problem.estimatedMinutes
                knowledgeNames = problem.knowledgePoints.map { it.name }
                subject = problem.courseName
                chapter = problem.chapter
                title = "${problem.chapter} 第 ${problem.problemNumber} 题"
                isReviewAttempt = problem.completionStatus == ProblemCompletionStatus.COMPLETED ||
                    attemptIndex > 1
                val actual = (problem.actualMinutes ?: 0) + request.durationMinutes
                val suggested = ExerciseAbilityEngine.suggestEstimatedMinutes(
                    problem.estimatedMinutes,
                    existing.map { it.toDomainModel() } + ExerciseAttempt(
                        problemType = request.problemType,
                        problemId = request.problemId,
                        attemptIndex = attemptIndex,
                        startedAt = now.minusSeconds(request.durationMinutes * 60L),
                        submittedAt = now,
                        durationMinutes = request.durationMinutes,
                        highestHintLevel = highestHintLevel,
                        independent = independent,
                        correct = request.correct,
                    ),
                )
                pendingBankUpdate = problem.copy(
                    actualMinutes = actual,
                    completionStatus = if (request.correct) {
                        ProblemCompletionStatus.COMPLETED
                    } else {
                        ProblemCompletionStatus.IN_PROGRESS
                    },
                    reviewStatus = if (request.correct) {
                        if (attemptIndex > 1) ProblemReviewStatus.REVIEWING else problem.reviewStatus
                    } else {
                        ProblemReviewStatus.REVIEWING
                    },
                    estimatedMinutes = suggested ?: problem.estimatedMinutes,
                )
            }
            ExerciseProblemType.MANUAL -> {
                val problem = learningDao.getProblem(request.problemId)
                    ?: throw IllegalArgumentException("习题不存在，可能已被删除")
                val subjectName = learningDao.getSubject(problem.subjectId)?.name.orEmpty()
                knowledgeNames = listOf(problem.knowledgePoint).filter(String::isNotBlank)
                subject = subjectName
                chapter = problem.chapter
                title = "${problem.chapter} 第 ${problem.problemNumber} 题".trim()
                isReviewAttempt = problem.status == ProblemStatus.COMPLETED.name || attemptIndex > 1
                pendingManualUpdate = problem.copy(
                    status = if (request.correct) ProblemStatus.COMPLETED.name else ProblemStatus.IN_PROGRESS.name,
                    independentCompleted = independent,
                    usedAiHint = problem.usedAiHint || highestHintLevel > 0,
                    isMistake = problem.isMistake || !request.correct,
                    isDifficult = problem.isDifficult || highestHintLevel >= 3 || !request.correct,
                    retryCount = (attemptIndex - 1).coerceAtLeast(problem.retryCount),
                    secondAttemptIndependent = if (attemptIndex > 1) independent else problem.secondAttemptIndependent,
                    completedAtEpochMillis = if (request.correct) nowMillis else null,
                    updatedAtEpochMillis = nowMillis,
                )
            }
        }
        return database.withTransaction {
            pendingBankUpdate?.let { problemBankRepository.updateProblem(it) }
            pendingManualUpdate?.let { learningDao.updateProblem(it) }
            exerciseDao.insertAttempt(
                ExerciseAttemptEntity(
                    problemType = request.problemType.name,
                    problemId = request.problemId,
                    attemptIndex = attemptIndex,
                    startedAtEpochMillis = now.minusSeconds(request.durationMinutes * 60L).toEpochMilli(),
                    submittedAtEpochMillis = nowMillis,
                    durationMinutes = request.durationMinutes,
                    highestHintLevel = highestHintLevel,
                    independent = independent,
                    correct = request.correct,
                    note = request.note.trim(),
                    createdAtEpochMillis = nowMillis,
                ),
            )
            val allAttempts = exerciseDao.attemptsFor(request.problemType.name, request.problemId)
                .map { it.toDomainModel() }
            val profile = ExerciseAbilityEngine.buildProfile(
                problemType = request.problemType,
                problemId = request.problemId,
                attempts = allAttempts,
                estimatedDifficulty = baseDifficulty,
                estimatedMinutes = baseMinutes,
                now = now,
            ) ?: throw IllegalStateException("无法建立习题档案")
            exerciseDao.upsertProfile(profile.toEntity(nowMillis))
            refreshAbilityEstimates(now)
            knowledgeRepository.applyAttemptSignal(
                subject = subject,
                knowledgeNames = knowledgeNames,
                correct = request.correct,
                independent = independent,
                hintLevel = highestHintLevel,
            )
            historyDao.insert(
                TaskEventEntity(
                    objectType = "EXERCISE",
                    objectId = request.problemId,
                    eventType = when {
                        attemptIndex > 1 -> "EXERCISE_RETRY"
                        request.correct && independent -> "INDEPENDENT_PROOF"
                        else -> "EXERCISE_FIRST_ATTEMPT"
                    },
                    occurredAtEpochMillis = nowMillis,
                    beforeJson = null,
                    afterJson = org.json.JSONObject()
                        .put("attemptIndex", attemptIndex)
                        .put("independent", independent)
                        .put("correct", request.correct)
                        .put("hintLevel", highestHintLevel)
                        .toString(),
                    source = "USER",
                    reason = "$title · 用时 ${request.durationMinutes} 分钟" +
                        if (highestHintLevel > 0) " · 最高提示 Level ${highestHintLevel}" else "",
                    sessionId = null,
                    revision = attemptIndex,
                ),
            )
            if (isReviewAttempt && request.correct) {
                historyDao.insert(
                    TaskEventEntity(
                        objectType = "EXERCISE",
                        objectId = request.problemId,
                        eventType = "REVIEW_SUCCEEDED",
                        occurredAtEpochMillis = nowMillis,
                        beforeJson = null,
                        afterJson = null,
                        source = "USER",
                        reason = "重复尝试后复习通过",
                        sessionId = null,
                        revision = attemptIndex,
                    ),
                )
            }
            val reviewSuggestion = if (!request.correct && attemptIndex >= 2 && knowledgeNames.isNotEmpty()) {
                runCatching {
                    val graph = knowledgeRepository.getGraph()
                    knowledgeNames.firstNotNullOfOrNull { name ->
                        graph.nodes.firstOrNull { it.name == name }?.let { node ->
                            com.zhixu.studyos.core.knowledge.KnowledgeReviewAdvisor
                                .suggest(node, graph, attemptIndex)
                                ?.message
                        }
                    }
                }.getOrNull()
            } else {
                null
            }
            SubmitAttemptResult(
                attempt = allAttempts.last(),
                profile = profile,
                suggestedEstimatedMinutes = ExerciseAbilityEngine.suggestEstimatedMinutes(baseMinutes, allAttempts),
                reviewSuggestion = reviewSuggestion,
            )
        }
    }

    override suspend fun recommendations(limit: Int): List<ExerciseRecommendation> {
        require(limit in 1..50) { "推荐数量应在 1–50 之间" }
        val summaries = problemBankDao.allProblemSummaries()
        val allProfiles = exerciseDao.recentProfiles(500).map { it.toDomainModel() }
        // 只按题库的 problemId 取档案；手工题同 ID 的档案不能串到题库题上。
        val profiles = allProfiles
            .filter { it.problemType == ExerciseProblemType.BANK }
            .associateBy { it.problemId }
        val knowledgeByProblem = problemBankDao.allKnowledgePointLinks().groupBy(
            { it.problemId },
            { it.name },
        )
        val overall = exerciseDao.getAbility("OVERALL", "ALL")?.ability
            ?: ExerciseAbilityEngine.estimateAbility("OVERALL", "ALL", allProfiles, clock.instant())?.ability
        val candidates = summaries.map { summary ->
            val profile = profiles[summary.id]
            ExerciseCandidate(
                id = summary.id,
                problemType = ExerciseProblemType.BANK,
                title = "${summary.chapter} 第 ${summary.problemNumber} 题",
                subject = summary.courseName,
                chapter = summary.chapter,
                knowledgePoints = knowledgeByProblem[summary.id].orEmpty(),
                estimatedDifficulty = summary.estimatedDifficulty,
                estimatedMinutes = summary.estimatedMinutes,
                isMistake = summary.reviewStatus == "REVIEWING" && summary.completionStatus != "COMPLETED",
                needsReview = summary.reviewStatus == "REVIEWING",
                mastery = profile?.mastery ?: 0.0,
                personalDifficulty = profile?.personalDifficulty,
            )
        }
        return ExerciseAbilityEngine.recommend(candidates, overall, limit)
    }

    override suspend fun abilitySnapshot(): AbilitySnapshot {
        val profiles = exerciseDao.recentProfiles(500).map { it.toDomainModel() }
        val summaries = problemBankDao.allProblemSummaries().associateBy { it.id }
        return ExerciseAbilityEngine.buildSnapshot(
            profiles = profiles,
            candidateSubjectById = summaries.mapValues { it.value.courseName },
            candidateChapterById = summaries.mapValues { it.value.chapter },
            now = clock.instant(),
        )
    }

    override suspend fun dueReviewProblemIds(limit: Int): List<Long> =
        problemBankDao.allProblemSummaries()
            .filter { it.reviewStatus == "REVIEWING" || it.completionStatus == "IN_PROGRESS" }
            .take(limit)
            .map { it.id }

    private suspend fun refreshAbilityEstimates(now: Instant) {
        val profiles = exerciseDao.recentProfiles(500).map { it.toDomainModel() }
        val summaries = problemBankDao.allProblemSummaries().associateBy { it.id }
        val snapshot = ExerciseAbilityEngine.buildSnapshot(
            profiles = profiles,
            candidateSubjectById = summaries.mapValues { it.value.courseName },
            candidateChapterById = summaries.mapValues { it.value.chapter },
            now = now,
        )
        val estimates = buildList {
            snapshot.overall?.let(::add)
            addAll(snapshot.bySubject)
            addAll(snapshot.byChapter)
        }
        estimates.forEach { estimate ->
            val existing = exerciseDao.getAbility(estimate.scope, estimate.scopeKey)
            exerciseDao.upsertAbility(
                com.zhixu.studyos.core.data.local.AbilityEstimateEntity(
                    id = existing?.id ?: 0,
                    scope = estimate.scope,
                    scopeKey = estimate.scopeKey,
                    ability = estimate.ability,
                    sampleCount = estimate.sampleCount,
                    updatedAtEpochMillis = now.toEpochMilli(),
                ),
            )
        }
    }
}

/**
 * 计算本次尝试实际使用的最高提示等级。
 *
 * 提示记录没有 attemptId，因此用本次尝试的时间窗口过滤：开始时间 = 提交时间 − 用时。
 * 窗口之外的旧提示不再污染当前尝试的独立性判定。
 */
internal fun attemptHintLevel(
    hints: List<com.zhixu.studyos.core.data.local.ExerciseHintRecordEntity>,
    attemptStartEpochMillis: Long,
    attemptEndEpochMillis: Long,
): Int = hints
    .asSequence()
    .filter { it.requestedAtEpochMillis in attemptStartEpochMillis..attemptEndEpochMillis }
    .maxOfOrNull { it.level }
    ?: 0

private fun ExerciseAttemptEntity.toDomainModel() = ExerciseAttempt(
    id = id,
    problemType = runCatching { ExerciseProblemType.valueOf(problemType) }
        .getOrDefault(ExerciseProblemType.BANK),
    problemId = problemId,
    attemptIndex = attemptIndex,
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    submittedAt = Instant.ofEpochMilli(submittedAtEpochMillis),
    durationMinutes = durationMinutes,
    highestHintLevel = highestHintLevel,
    independent = independent,
    correct = correct,
    note = note,
)

private fun ExerciseProfileEntity.toDomainModel() = ExerciseProfile(
    problemType = runCatching { ExerciseProblemType.valueOf(problemType) }
        .getOrDefault(ExerciseProblemType.BANK),
    problemId = problemId,
    personalDifficulty = personalDifficulty,
    difficultySampleCount = difficultySampleCount,
    attemptCount = attemptCount,
    independentCount = independentCount,
    hintLevelSum = hintLevelSum,
    avgActualMinutes = avgActualMinutes,
    estimatedMinutes = estimatedMinutes,
    mastery = mastery,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

private fun ExerciseProfile.toEntity(now: Long) = ExerciseProfileEntity(
    problemType = problemType.name,
    problemId = problemId,
    personalDifficulty = personalDifficulty,
    difficultySampleCount = difficultySampleCount,
    attemptCount = attemptCount,
    independentCount = independentCount,
    hintLevelSum = hintLevelSum,
    avgActualMinutes = avgActualMinutes,
    estimatedMinutes = estimatedMinutes,
    mastery = mastery,
    updatedAtEpochMillis = now,
)
