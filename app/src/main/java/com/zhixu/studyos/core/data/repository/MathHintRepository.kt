package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.ai.AiTaskRequest
import com.zhixu.studyos.core.ai.AiTaskResult
import com.zhixu.studyos.core.ai.AiTaskService
import com.zhixu.studyos.core.ai.AiTaskType
import com.zhixu.studyos.core.data.local.ExerciseHintRecordEntity
import com.zhixu.studyos.core.data.local.ExerciseLearningDao
import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.LearningDao
import com.zhixu.studyos.core.data.local.SavedProofEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.exercise.ExerciseProblemType
import com.zhixu.studyos.core.math.HintLevel
import com.zhixu.studyos.core.math.HintProgression
import com.zhixu.studyos.core.math.MathHint
import com.zhixu.studyos.core.math.MathHintOutcome
import com.zhixu.studyos.core.math.MathHintPromptBuilder
import com.zhixu.studyos.core.math.MathProblemContext
import com.zhixu.studyos.core.math.ProofCheck
import java.time.Clock
import java.time.Instant

data class ProofCheckOutcome(
    val check: ProofCheck,
    val model: String,
    val usedCache: Boolean,
    val costMicros: Long,
    val rawJson: String,
)

data class HintRecordView(
    val id: Long,
    val level: HintLevel,
    val requestedAt: Instant,
    val rawContent: String,
    val usedCache: Boolean,
    val model: String,
    val costMicros: Long,
)

data class SavedProofView(
    val id: Long,
    val content: String,
    val source: String,
    val note: String,
    val createdAt: Instant,
)

interface MathHintRepository {
    suspend fun buildContext(problemType: ExerciseProblemType, problemId: Long): MathProblemContext
    suspend fun highestHintLevel(problemType: ExerciseProblemType, problemId: Long): HintLevel?
    suspend fun hintRecords(problemType: ExerciseProblemType, problemId: Long): List<HintRecordView>
    suspend fun requestHint(
        problemType: ExerciseProblemType,
        problemId: Long,
        level: HintLevel,
        userThoughts: String,
        allowJump: Boolean = false,
        allowReferenceProof: Boolean = false,
        forceRefresh: Boolean = false,
        now: Instant = Instant.now(),
    ): MathHintOutcome
    suspend fun checkProof(
        problemType: ExerciseProblemType,
        problemId: Long,
        proof: String,
        otherMethods: Boolean = false,
        forceRefresh: Boolean = false,
        now: Instant = Instant.now(),
    ): ProofCheckOutcome
    suspend fun saveProof(
        problemType: ExerciseProblemType,
        problemId: Long,
        content: String,
        source: String,
        note: String = "",
    ): Long
    suspend fun savedProofs(problemType: ExerciseProblemType, problemId: Long): List<SavedProofView>
    suspend fun reportError(
        problemType: ExerciseProblemType,
        problemId: Long,
        hintLevel: Int?,
        reason: String,
        note: String = "",
    )
}

class OfflineMathHintRepository(
    private val database: StudyOSDatabase,
    private val exerciseDao: ExerciseLearningDao,
    private val problemBankRepository: ProblemBankRepository,
    private val learningDao: LearningDao,
    private val historyDao: HistoryDao,
    private val aiTaskService: AiTaskService,
    private val configStore: AiConfigStore,
    private val clock: Clock,
) : MathHintRepository {

    override suspend fun buildContext(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): MathProblemContext = when (problemType) {
        ExerciseProblemType.BANK -> {
            val problem = problemBankRepository.getProblem(problemId)
                ?: throw IllegalArgumentException("题目不存在，可能已被删除")
            MathProblemContext(
                problemType = problemType.name,
                problemId = problemId,
                subject = problem.courseName,
                chapter = problem.chapter,
                title = "${problem.chapter} 第 ${problem.problemNumber} 题",
                statement = problem.statement,
                formulas = problem.formulas.map { it.latex },
                knowledgePoints = problem.knowledgePoints.map { it.name },
            )
        }
        ExerciseProblemType.MANUAL -> {
            val problem = learningDao.getProblem(problemId)
                ?: throw IllegalArgumentException("习题不存在，可能已被删除")
            val subject = learningDao.getSubject(problem.subjectId)?.name.orEmpty()
            MathProblemContext(
                problemType = problemType.name,
                problemId = problemId,
                subject = subject,
                chapter = problem.chapter,
                title = "${problem.chapter} 第 ${problem.problemNumber} 题".trim(),
                statement = problem.description,
                knowledgePoints = listOf(problem.knowledgePoint).filter(String::isNotBlank),
            )
        }
    }

    override suspend fun highestHintLevel(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): HintLevel? = exerciseDao.hintsFor(problemType.name, problemId)
        .maxByOrNull { it.level }
        ?.let { HintLevel.fromNumber(it.level) }

    override suspend fun hintRecords(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): List<HintRecordView> = exerciseDao.hintsFor(problemType.name, problemId).map { record ->
        HintRecordView(
            id = record.id,
            level = HintLevel.fromNumber(record.level),
            requestedAt = Instant.ofEpochMilli(record.requestedAtEpochMillis),
            rawContent = record.content,
            usedCache = record.usedCache,
            model = record.model,
            costMicros = record.costMicros,
        )
    }

    override suspend fun requestHint(
        problemType: ExerciseProblemType,
        problemId: Long,
        level: HintLevel,
        userThoughts: String,
        allowJump: Boolean,
        allowReferenceProof: Boolean,
        forceRefresh: Boolean,
        now: Instant,
    ): MathHintOutcome {
        require(level != HintLevel.LEVEL_0) { "Level 0 是独立思考，不请求 AI" }
        require(userThoughts.length <= 4000) { "思路草稿过长，请精简后再请求提示" }
        val highest = highestHintLevel(problemType, problemId)
        if (HintProgression.isJump(highest, level) && !allowJump) {
            throw IllegalArgumentException(
                "提示需要逐级解锁。若要跳过当前等级，请先明确确认跳级。",
            )
        }
        if (level == HintLevel.LEVEL_5 && !allowReferenceProof) {
            throw IllegalArgumentException("查看完整参考证明需要明确确认；在此之前请先尝试思考。")
        }
        val context = buildContext(problemType, problemId)
        val prompts = MathHintPromptBuilder.hintPrompts(
            context = context.copy(userThoughts = userThoughts.trim()),
            level = level,
        )
        val config = configStore.getConfig()
        val cacheKey = aiTaskService.cacheKey(
            taskType = AiTaskType.MATH_HINT,
            promptVersion = MathHintPromptBuilder.PROMPT_VERSION,
            model = config.model,
            payload = prompts.cachePayload,
        )
        var parsed: MathHint? = null
        val result = aiTaskService.run(
            request = AiTaskRequest(
                taskType = AiTaskType.MATH_HINT,
                promptVersion = MathHintPromptBuilder.PROMPT_VERSION,
                systemPrompt = prompts.system,
                userPrompt = prompts.user,
                cacheKey = cacheKey,
                objectType = problemType.name,
                objectId = problemId,
                maxOutputTokens = if (level == HintLevel.LEVEL_5) 2400 else 1200,
                note = "Level ${level.ordinal} ${level.label}",
            ),
            forceRefresh = forceRefresh,
            validateContent = { content ->
                parsed = MathHintPromptBuilder.parseHint(content, level)
            },
        )
        val hint = parsed
            ?.takeIf { it.level == level }
            ?: MathHintPromptBuilder.parseHint(result.content, level)
        val recordId = saveHintRecord(problemType, problemId, level, result, hint, now)
        return MathHintOutcome(
            hint = hint,
            model = result.model,
            usedCache = result.usedCache,
            costMicros = result.costMicros,
            requestedAt = now,
            recordId = recordId,
        )
    }

    private suspend fun saveHintRecord(
        problemType: ExerciseProblemType,
        problemId: Long,
        level: HintLevel,
        result: AiTaskResult,
        hint: MathHint,
        now: Instant,
    ): Long = database.withTransaction {
        val id = exerciseDao.insertHint(
            ExerciseHintRecordEntity(
                problemType = problemType.name,
                problemId = problemId,
                level = level.ordinal,
                requestedAtEpochMillis = now.toEpochMilli(),
                content = result.content,
                usedCache = result.usedCache,
                model = result.model,
                costMicros = result.costMicros,
                createdProof = level == HintLevel.LEVEL_5,
            ),
        )
        historyDao.insert(
            TaskEventEntity(
                objectType = "EXERCISE",
                objectId = problemId,
                eventType = "AI_HINT_USED",
                occurredAtEpochMillis = now.toEpochMilli(),
                beforeJson = null,
                afterJson = org.json.JSONObject()
                    .put("level", level.ordinal)
                    .put("uncertain", hint.uncertain)
                    .toString(),
                source = "USER",
                reason = "Level ${level.ordinal} ${level.label}" +
                    if (result.usedCache) "（使用缓存）" else "",
                sessionId = null,
                revision = level.ordinal,
            ),
        )
        id
    }

    override suspend fun checkProof(
        problemType: ExerciseProblemType,
        problemId: Long,
        proof: String,
        otherMethods: Boolean,
        forceRefresh: Boolean,
        now: Instant,
    ): ProofCheckOutcome {
        require(proof.isNotBlank()) { "请先填写你的证明" }
        require(proof.length <= 8000) { "证明内容过长，请精简后再提交" }
        val context = buildContext(problemType, problemId)
        val prompts = MathHintPromptBuilder.proofCheckPrompts(context, proof.trim(), otherMethods)
        val config = configStore.getConfig()
        val cacheKey = aiTaskService.cacheKey(
            taskType = AiTaskType.PROOF_CHECK,
            promptVersion = MathHintPromptBuilder.PROOF_PROMPT_VERSION,
            model = config.model,
            payload = prompts.cachePayload,
        )
        var parsed: ProofCheck? = null
        val result = aiTaskService.run(
            request = AiTaskRequest(
                taskType = AiTaskType.PROOF_CHECK,
                promptVersion = MathHintPromptBuilder.PROOF_PROMPT_VERSION,
                systemPrompt = prompts.system,
                userPrompt = prompts.user,
                cacheKey = cacheKey,
                objectType = problemType.name,
                objectId = problemId,
                maxOutputTokens = 1600,
                note = "用户提交自己的证明",
            ),
            forceRefresh = forceRefresh,
            validateContent = { content ->
                parsed = MathHintPromptBuilder.parseProofCheck(content)
            },
        )
        return ProofCheckOutcome(
            check = parsed ?: MathHintPromptBuilder.parseProofCheck(result.content),
            model = result.model,
            usedCache = result.usedCache,
            costMicros = result.costMicros,
            rawJson = result.content,
        )
    }

    override suspend fun saveProof(
        problemType: ExerciseProblemType,
        problemId: Long,
        content: String,
        source: String,
        note: String,
    ): Long {
        require(content.isNotBlank()) { "证明内容为空" }
        require(content.length <= 20000) { "证明内容过长" }
        return exerciseDao.insertSavedProof(
            SavedProofEntity(
                problemType = problemType.name,
                problemId = problemId,
                content = content.trim(),
                source = source,
                note = note.trim(),
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun savedProofs(
        problemType: ExerciseProblemType,
        problemId: Long,
    ): List<SavedProofView> = exerciseDao.savedProofs(problemType.name, problemId).map {
        SavedProofView(
            id = it.id,
            content = it.content,
            source = it.source,
            note = it.note,
            createdAt = Instant.ofEpochMilli(it.createdAtEpochMillis),
        )
    }

    override suspend fun reportError(
        problemType: ExerciseProblemType,
        problemId: Long,
        hintLevel: Int?,
        reason: String,
        note: String,
    ) {
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty()) { "请选择或填写问题原因" }
        exerciseDao.insertErrorReport(
            com.zhixu.studyos.core.data.local.ExerciseErrorReportEntity(
                problemType = problemType.name,
                problemId = problemId,
                hintLevel = hintLevel,
                reason = trimmed.take(500),
                note = note.trim().take(2000),
                createdAtEpochMillis = clock.millis(),
            ),
        )
    }
}
