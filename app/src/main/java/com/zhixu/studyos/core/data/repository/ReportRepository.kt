package com.zhixu.studyos.core.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.zhixu.studyos.core.ai.AiTaskRequest
import com.zhixu.studyos.core.ai.AiTaskService
import com.zhixu.studyos.core.ai.AiTaskType
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.ExamGoalDao
import com.zhixu.studyos.core.data.local.ExerciseLearningDao
import com.zhixu.studyos.core.data.local.FinanceDao
import com.zhixu.studyos.core.data.local.LearningDao
import com.zhixu.studyos.core.data.local.ProblemBankDao
import com.zhixu.studyos.core.data.local.SleepDao
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.StudyTaskDao
import com.zhixu.studyos.core.data.local.V2AiDao
import com.zhixu.studyos.core.data.local.VocabularyDao
import com.zhixu.studyos.core.data.local.WeeklyReportEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.exercise.ExerciseProblemType
import com.zhixu.studyos.core.growth.GrowthAnalytics
import com.zhixu.studyos.core.growth.GrowthAnalyticsEngine
import com.zhixu.studyos.core.growth.GrowthRange
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.report.AiReportSections
import com.zhixu.studyos.core.report.AttemptFact
import com.zhixu.studyos.core.report.GoalFact
import com.zhixu.studyos.core.report.HintFact
import com.zhixu.studyos.core.report.LearningFactBundle
import com.zhixu.studyos.core.report.MilestoneFact
import com.zhixu.studyos.core.report.ReportMode
import com.zhixu.studyos.core.report.ReportPdfWriter
import com.zhixu.studyos.core.report.SelfControlFact
import com.zhixu.studyos.core.report.SleepFact
import com.zhixu.studyos.core.report.TaskFact
import com.zhixu.studyos.core.report.TransactionFact
import com.zhixu.studyos.core.report.TimingFact
import com.zhixu.studyos.core.report.VocabularyFact
import com.zhixu.studyos.core.report.VocabularyReviewFact
import com.zhixu.studyos.core.report.WeeklyReport
import com.zhixu.studyos.core.report.WeeklyReportEngine
import com.zhixu.studyos.core.report.WeeklyReportJson
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedWeeklySummary(
    val weekStart: LocalDate,
    val generatedAt: Instant,
    val studyMinutes: Int,
    val completedTasks: Int,
    val scheduledTasks: Int,
    val completionRatePercent: Int?,
    val aiSummary: String?,
    val aiModel: String?,
)

data class AiReportOutcome<T>(
    val report: T,
    val sections: AiReportSections,
    val model: String,
    val usedCache: Boolean,
    val costMicros: Long,
)

interface ReportRepository {
    suspend fun weeklyReport(weekStart: LocalDate): WeeklyReport
    suspend fun aiWeeklyReport(report: WeeklyReport, forceRefresh: Boolean = false): AiReportOutcome<WeeklyReport>
    suspend fun saveWeeklyReport(report: WeeklyReport)
    suspend fun savedWeeklySummaries(limit: Int = 12): List<SavedWeeklySummary>
    suspend fun growthAnalytics(range: GrowthRange, semesterStart: LocalDate?): GrowthAnalytics
    suspend fun aiGrowthReport(
        analytics: GrowthAnalytics,
        forceRefresh: Boolean = false,
    ): AiReportOutcome<GrowthAnalytics>
    suspend fun exportWeeklyPdf(uri: Uri, report: WeeklyReport): Long
}

class OfflineReportRepository(
    private val database: StudyOSDatabase,
    private val taskDao: StudyTaskDao,
    private val learningDao: LearningDao,
    private val exerciseDao: ExerciseLearningDao,
    private val vocabularyDao: VocabularyDao,
    private val sleepDao: SleepDao,
    private val examGoalDao: ExamGoalDao,
    private val financeDao: FinanceDao,
    private val problemBankDao: ProblemBankDao,
    private val aiDao: V2AiDao,
    private val aiTaskService: AiTaskService,
    private val fileStore: AppFileStore,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ReportRepository {

    override suspend fun weeklyReport(weekStart: LocalDate): WeeklyReport {
        // 只读取本周及必要范围内的明细；任务/词汇/目标等小表仍保留全量用于积压与总量。
        val facts = collectFacts(weekStart, weekStart.plusDays(6))
        return WeeklyReportEngine.build(
            facts = facts,
            weekStart = weekStart,
            generatedAt = Instant.now(clock),
            mode = ReportMode.OFFLINE,
        )
    }

    override suspend fun aiWeeklyReport(
        report: WeeklyReport,
        forceRefresh: Boolean,
    ): AiReportOutcome<WeeklyReport> {
        val payload = weeklyAiPayload(report)
        val prompts = weeklyAiPrompts(payload)
        var sections: AiReportSections? = null
        val result = aiTaskService.run(
            request = AiTaskRequest(
                taskType = AiTaskType.WEEKLY_REPORT,
                promptVersion = WEEKLY_AI_PROMPT_VERSION,
                systemPrompt = prompts.first,
                userPrompt = prompts.second,
                cacheKey = aiTaskService.cacheKey(
                    AiTaskType.WEEKLY_REPORT,
                    WEEKLY_AI_PROMPT_VERSION,
                    "weekly",
                    payload,
                ),
                objectType = "WEEK",
                objectId = report.weekStart.toEpochDay(),
                maxOutputTokens = 1600,
                note = "周报 AI 分析（聚合数据）",
            ),
            forceRefresh = forceRefresh,
            validateContent = { content -> sections = parseSections(content, strict = true) },
        )
        val parsed = sections ?: parseSections(result.content)
        val updated = report.copy(
            mode = ReportMode.AI,
            aiSummary = result.content,
            aiSections = parsed,
        )
        saveWeeklyReport(
            updated.copy(aiSummary = buildAiSummaryText(parsed)),
            model = result.model,
        )
        return AiReportOutcome(
            report = updated,
            sections = parsed,
            model = result.model,
            usedCache = result.usedCache,
            costMicros = result.costMicros,
        )
    }

    override suspend fun saveWeeklyReport(report: WeeklyReport) {
        saveWeeklyReport(report, model = null)
    }

    private suspend fun saveWeeklyReport(report: WeeklyReport, model: String?) {
        val existing = aiDao.getWeeklyReport(report.weekStart.toEpochDay())
        val aiSummary = report.aiSections?.let { buildAiSummaryText(it) }
        aiDao.upsertWeeklyReport(
            WeeklyReportEntity(
                id = existing?.id ?: 0,
                weekStartEpochDay = report.weekStart.toEpochDay(),
                generatedAtEpochMillis = report.generatedAt.toEpochMilli(),
                reportJson = WeeklyReportJson.encode(report),
                aiSummary = aiSummary ?: existing?.aiSummary,
                aiGeneratedAtEpochMillis = if (aiSummary != null) clock.millis() else existing?.aiGeneratedAtEpochMillis,
                aiModel = model ?: existing?.aiModel,
            ),
        )
    }

    override suspend fun savedWeeklySummaries(limit: Int): List<SavedWeeklySummary> =
        aiDao.recentWeeklyReports(limit).map { row ->
            val json = runCatching { JSONObject(row.reportJson) }.getOrNull()
            SavedWeeklySummary(
                weekStart = LocalDate.ofEpochDay(row.weekStartEpochDay),
                generatedAt = Instant.ofEpochMilli(row.generatedAtEpochMillis),
                studyMinutes = json?.optInt("studyMinutes", 0) ?: 0,
                completedTasks = json?.optInt("completedTasks", 0) ?: 0,
                scheduledTasks = json?.optInt("scheduledTasks", 0) ?: 0,
                completionRatePercent = json?.takeIf { it.has("completionRate") && !it.isNull("completionRate") }
                    ?.optInt("completionRate"),
                aiSummary = row.aiSummary,
                aiModel = row.aiModel,
            )
        }

    override suspend fun growthAnalytics(
        range: GrowthRange,
        semesterStart: LocalDate?,
    ): GrowthAnalytics {
        // 月度趋势需要最近 12 个月，学期对比取 24 个月；不再读取全部历史明细。
        val lookbackStart = minOf(
            range.start,
            java.time.YearMonth.from(range.end).minusMonths(24).atDay(1),
        )
        val facts = collectFacts(lookbackStart, range.end)
        return GrowthAnalyticsEngine.build(
            facts = facts,
            range = range,
            today = LocalDate.now(clock.withZone(zoneId)),
            semesterStart = semesterStart,
        )
    }

    override suspend fun aiGrowthReport(
        analytics: GrowthAnalytics,
        forceRefresh: Boolean,
    ): AiReportOutcome<GrowthAnalytics> {
        val payload = growthAiPayload(analytics)
        val prompts = growthAiPrompts(payload)
        var sections: AiReportSections? = null
        val result = aiTaskService.run(
            request = AiTaskRequest(
                taskType = AiTaskType.GROWTH_REPORT,
                promptVersion = GROWTH_AI_PROMPT_VERSION,
                systemPrompt = prompts.first,
                userPrompt = prompts.second,
                cacheKey = aiTaskService.cacheKey(
                    AiTaskType.GROWTH_REPORT,
                    GROWTH_AI_PROMPT_VERSION,
                    "growth",
                    payload,
                ),
                objectType = "GROWTH",
                objectId = analytics.range.start.toEpochDay(),
                maxOutputTokens = 1800,
                note = "成长报告 AI 分析（聚合数据）",
            ),
            forceRefresh = forceRefresh,
            validateContent = { content -> sections = parseSections(content, strict = true) },
        )
        val parsed = sections ?: parseSections(result.content)
        return AiReportOutcome(
            report = analytics,
            sections = parsed,
            model = result.model,
            usedCache = result.usedCache,
            costMicros = result.costMicros,
        )
    }

    override suspend fun exportWeeklyPdf(uri: Uri, report: WeeklyReport): Long =
        fileStore.writeBinary(uri) { output ->
            withContext(Dispatchers.Default) {
                ReportPdfWriter.write(report, output, report.aiSections)
            }
            fileStore.describe(uri).second
        }

    // ---------------- 事实采集 ----------------

    private suspend fun collectFacts(
        start: LocalDate? = null,
        end: LocalDate? = null,
    ): LearningFactBundle {
        val startEpochDay = start?.toEpochDay()
        val endEpochDay = end?.toEpochDay()
        val startMillis = start?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()
        val endMillis = end?.plusDays(1)?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()
            ?.let { it - 1 }
        val taskRows = taskDao.getAllWithSessions()
        val tasks = taskRows.map { row ->
            val task = row.toDomainModel()
            val firstSession = task.sessions.minByOrNull { it.date }
            TaskFact(
                id = task.id,
                title = task.title,
                subject = task.subject,
                estimatedMinutes = task.estimatedMinutes,
                scheduledMinutes = task.sessions.sumOf { it.endMinuteExclusive - it.startMinute },
                scheduledDate = firstSession?.date,
                completed = task.isCompleted,
                completedDate = task.completedAt?.atZone(zoneId)?.toLocalDate(),
                dueDate = task.dueDate,
                postponeCount = task.postponeCount,
                sourceType = task.sourceType,
                sessions = task.sessions.map { session ->
                    com.zhixu.studyos.core.report.TaskSessionFact(
                        date = session.date,
                        minutes = (session.endMinuteExclusive - session.startMinute).coerceAtLeast(0),
                    )
                },
            )
        }
        val subjects = learningDao.getSubjects().associate { it.id to it.name }
        val timingRows = if (startEpochDay != null && endEpochDay != null) {
            learningDao.getTimingSessionsBetween(startEpochDay, endEpochDay)
        } else {
            learningDao.getTimingSessions()
        }
        val timings = timingRows
            .filter { it.status == StudyTimerStatus.COMPLETED.name && it.accumulatedMillis > 0 }
            .map {
                TimingFact(
                    date = LocalDate.ofEpochDay(it.dateEpochDay),
                    subject = subjects[it.subjectId] ?: "未分类",
                    minutes = (it.accumulatedMillis / 60_000L).toInt(),
                )
            }
        val attemptRows = if (startMillis != null && endMillis != null) {
            exerciseDao.attemptsBetween(startMillis, endMillis)
        } else {
            exerciseDao.allAttempts()
        }
        // 只在确有对应类型的尝试时才加载题库/手工题映射，避免周报无关的大表读取。
        val bankSubjects = if (attemptRows.any { it.problemType == "BANK" }) {
            problemBankDao.allProblemSummaries().associateBy { it.id }
        } else {
            emptyMap()
        }
        val manualProblems = if (attemptRows.any { it.problemType == "MANUAL" }) {
            learningDao.getProblems().associateBy { it.id }
        } else {
            emptyMap()
        }
        val attempts = attemptRows.map { attempt ->
            val subject: String
            val chapter: String
            when (runCatching { ExerciseProblemType.valueOf(attempt.problemType) }
                .getOrDefault(ExerciseProblemType.BANK)) {
                ExerciseProblemType.BANK -> {
                    val problem = bankSubjects[attempt.problemId]
                    subject = problem?.courseName.orEmpty()
                    chapter = problem?.chapter.orEmpty()
                }
                ExerciseProblemType.MANUAL -> {
                    val problem = manualProblems[attempt.problemId]
                    subject = problem?.let { subjects[it.subjectId] }.orEmpty()
                    chapter = problem?.chapter.orEmpty()
                }
            }
            AttemptFact(
                date = Instant.ofEpochMilli(attempt.submittedAtEpochMillis).atZone(zoneId).toLocalDate(),
                subject = subject,
                chapter = chapter,
                independent = attempt.independent,
                correct = attempt.correct,
                highestHintLevel = attempt.highestHintLevel,
                durationMinutes = attempt.durationMinutes,
                attemptIndex = attempt.attemptIndex,
            )
        }
        val hintRows = if (startMillis != null && endMillis != null) {
            exerciseDao.hintsBetween(startMillis, endMillis)
        } else {
            exerciseDao.allHints()
        }
        val hints = hintRows.map {
            HintFact(
                date = Instant.ofEpochMilli(it.requestedAtEpochMillis).atZone(zoneId).toLocalDate(),
                level = it.level,
                usedCache = it.usedCache,
            )
        }
        val vocabulary = vocabularyDao.allWords().map {
            VocabularyFact(
                id = it.id,
                word = it.word,
                listName = it.listName,
                createdAt = Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zoneId).toLocalDate(),
                status = it.status,
            )
        }
        val reviewRows = if (startMillis != null && endMillis != null) {
            vocabularyDao.reviewsBetween(startMillis, endMillis)
        } else {
            vocabularyDao.allReviews()
        }
        val vocabularyReviews = reviewRows.map {
            VocabularyReviewFact(
                date = Instant.ofEpochMilli(it.reviewedAtEpochMillis).atZone(zoneId).toLocalDate(),
                recalled = it.recalled,
                rating = it.rating,
            )
        }
        val sleepRows = if (startEpochDay != null && endEpochDay != null) {
            sleepDao.recordsBetween(startEpochDay, endEpochDay)
        } else {
            sleepDao.allRecords()
        }
        val sleep = sleepRows.map { row ->
            val bed = row.bedTimeEpochMillis?.let(Instant::ofEpochMilli)
            val wake = row.wakeTimeEpochMillis?.let(Instant::ofEpochMilli)
            val minutes = if (bed != null && wake != null) {
                java.time.Duration.between(bed, wake).toMinutes().takeIf { it in 1..(24 * 60) }
            } else {
                null
            }
            SleepFact(
                date = LocalDate.ofEpochDay(row.dateEpochDay),
                durationMinutes = minutes,
                quality = row.quality,
                nextDayEnergy = row.nextDayEnergy,
            )
        }
        val selfControlRows = if (startEpochDay != null && endEpochDay != null) {
            learningDao.getSelfControlEntriesBetween(startEpochDay, endEpochDay)
        } else {
            learningDao.getSelfControlEntries()
        }
        val selfControl = selfControlRows.map { entry ->
            SelfControlFact(
                date = LocalDate.ofEpochDay(entry.dateEpochDay),
                interruptions = 1,
                voluntaryInterruptions = if (entry.voluntaryInterruption) 1 else 0,
                procrastinationRecorded = entry.procrastinationReason != null,
            )
        }
        val transactionRows = if (startEpochDay != null && endEpochDay != null) {
            financeDao.getTransactionsBetween(startEpochDay, endEpochDay)
        } else {
            financeDao.getTransactions()
        }
        val transactions = transactionRows.map { row ->
            val net = when (row.type) {
                "EXPENSE" -> row.amountFen
                "REFUND" -> -row.amountFen
                else -> 0L
            }
            TransactionFact(
                date = LocalDate.ofEpochDay(row.dateEpochDay),
                netSpentFen = net,
                categoryName = "",
            )
        }
        val goals = examGoalDao.getGoals().map {
            GoalFact(
                id = it.id,
                title = it.title,
                category = it.category,
                status = it.status,
                startDate = LocalDate.ofEpochDay(it.startDateEpochDay),
                targetDate = it.targetDateEpochDay?.let(LocalDate::ofEpochDay),
            )
        }
        val milestones = examGoalDao.getMilestones().map {
            MilestoneFact(
                goalId = it.goalId,
                completed = it.status == "COMPLETED",
                doneDate = it.completedAtEpochMillis?.let { millis ->
                    Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
                },
                weight = it.weight,
            )
        }
        return LearningFactBundle(
            tasks = tasks,
            timings = timings,
            attempts = attempts,
            hints = hints,
            vocabulary = vocabulary,
            vocabularyReviews = vocabularyReviews,
            sleep = sleep,
            selfControl = selfControl,
            transactions = transactions,
            goals = goals,
            milestones = milestones,
        )
    }

    // ---------------- AI 提示与解析 ----------------

    private fun weeklyAiPayload(report: WeeklyReport): String {
        val subjects = JSONArray()
        report.subjects.forEach { subject ->
            subjects.put(
                JSONObject()
                    .put("subject", subject.subject)
                    .put("minutes", subject.studyMinutes)
                    .put("scheduled", subject.scheduledTasks)
                    .put("completed", subject.completedTasks),
            )
        }
        return JSONObject()
            .put("week", report.weekStart.toString())
            .put("studyMinutes", report.overview.studyMinutes)
            .put("activeDays", report.overview.activeDays)
            .put("scheduledTasks", report.overview.scheduledTasks)
            .put("completedTasks", report.overview.completedTasks)
            .put("subjects", subjects)
            .put("exerciseAttempts", report.exercise.attempts)
            .put("independentRate", report.exercise.independentRatePercent ?: JSONObject.NULL)
            .put("firstAttemptIndependentRate", report.exercise.firstAttemptIndependentRatePercent ?: JSONObject.NULL)
            .put("hintUses", report.exercise.hintUseCount)
            .put("vocabularyReviews", report.vocabulary.reviews)
            .put("vocabularyRecallRate", report.vocabulary.recallRatePercent ?: JSONObject.NULL)
            .put("averageSleepMinutes", report.sleep.averageDurationMinutes ?: JSONObject.NULL)
            .put("sleepRecordedDays", report.sleep.recordedDays)
            .put("postponedTasks", report.backlog.postponedTasks)
            .put("longBacklogTasks", report.backlog.longBacklogTasks)
            .put("offlineSuggestions", JSONArray(report.suggestions))
            .toString()
    }

    private fun weeklyAiPrompts(payload: String): Pair<String, String> {
        val system = """
你是学生的学习数据分析助手。你会收到一份只含聚合统计的周报（不含财务流水、不含睡眠原始时刻、不含任务标题）。
请只基于这些数据，用中文输出一个 JSON 对象；不要 Markdown 围栏，不要额外文字：
{
  "facts": ["对客观数据的简短复述，最多 5 条"],
  "speculation": ["可能的原因或趋势解读，必须标注为推测，最多 4 条"],
  "suggestions": ["下周可执行的温和建议，最多 4 条"],
  "disclaimer": "一句话提醒：统计相关性不代表因果，AI 不能替代你的判断"
}
禁止：预测成绩/排名；把相关性说成因果；使用焦虑化或指责性语言；编造缺失数据。
        """.trimIndent()
        return system to "周报聚合数据：\n$payload"
    }

    private fun growthAiPayload(analytics: GrowthAnalytics): String {
        val metrics = JSONArray()
        // 成长报告只发送非财务指标；财务聚合金额也不进入第三方 AI。
        analytics.metrics.filterNot { it.key == "finance" }.forEach { metric ->
            metrics.put(
                JSONObject()
                    .put("label", metric.label)
                    .put("value", metric.value ?: "数据不足")
                    .put("definition", metric.definition),
            )
        }
        val semesters = JSONArray()
        analytics.semesterComparison.forEach { semester ->
            semesters.put(
                JSONObject()
                    .put("label", semester.label)
                    .put("studyMinutes", semester.bucket.studyMinutes)
                    .put("completionRate", semester.bucket.completionRatePercent ?: JSONObject.NULL)
                    .put("independentRate", semester.bucket.independentRatePercent ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("range", analytics.range.label)
            .put("metrics", metrics)
            .put("semesters", semesters)
            .toString()
    }

    private fun growthAiPrompts(payload: String): Pair<String, String> {
        val system = """
你是长期学习成长分析助手。输入是本地统计聚合指标，不含财务流水、睡眠原始记录或题目原文。
请区分客观数据、推测与建议，用中文输出 JSON：
{
  "facts": ["客观数据观察，最多 5 条"],
  "speculation": ["长期趋势的可能解释，最多 4 条，必须说明是推测"],
  "suggestions": ["下一阶段建议，最多 4 条"],
  "disclaimer": "一句话提醒"
}
不得预测成绩或排名，不得把相关性解释为已证明的因果；数据不足的指标不要编造。
        """.trimIndent()
        return system to "成长分析聚合数据：\n$payload"
    }

    private fun parseSections(content: String, strict: Boolean = false): AiReportSections {
        val json = runCatching { JSONObject(com.zhixu.studyos.core.ai.AiPlanParser.extractJson(content)) }.getOrNull()
            ?: run {
                if (strict) throw IllegalArgumentException("AI 报告不是合法的 JSON 对象")
                return WeeklyReportEngine.parseAiSections(content)
            }
        fun list(key: String): List<String> = json.optJSONArray(key)?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty)
        }.orEmpty()
        val sections = AiReportSections(
            facts = list("facts"),
            speculation = list("speculation"),
            suggestions = list("suggestions"),
            disclaimer = json.optString("disclaimer").ifBlank {
                "AI 内容仅为参考，不能替代你的判断；[推测] 不是已证明的事实。"
            },
        )
        if (strict && sections.facts.isEmpty() && sections.speculation.isEmpty() && sections.suggestions.isEmpty()) {
            throw IllegalArgumentException("AI 报告缺少 facts/speculation/suggestions 内容")
        }
        return sections
    }

    private fun buildAiSummaryText(sections: AiReportSections): String = buildString {
        sections.facts.forEach { appendLine("[数据] $it") }
        sections.speculation.forEach { appendLine("[推测] $it") }
        sections.suggestions.forEach { appendLine("[建议] $it") }
        append(sections.disclaimer)
    }.trim()

    companion object {
        const val WEEKLY_AI_PROMPT_VERSION = "weekly-report-v1"
        const val GROWTH_AI_PROMPT_VERSION = "growth-report-v1"
    }
}
