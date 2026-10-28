package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiDayStat
import com.zhixu.studyos.core.model.AiGoalSummary
import com.zhixu.studyos.core.model.AiProblemSignal
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiSchedulingSummary
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiSubjectStat
import com.zhixu.studyos.core.model.AiTaskStat
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemReviewStatus
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.TimingSession
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把本地学习数据汇总成 [AiStudySnapshot]。
 *
 * 只包含学习相关统计与任务摘要：**财务数据从不进入该快照**。
 * 纯 Kotlin 计算，不访问 Android API，便于测试。
 */
class AiSnapshotBuilder(
    private val clock: Clock,
    private val zoneId: ZoneId,
) {
    fun build(
        today: LocalDate,
        tasks: List<StudyTask>,
        timings: List<TimingSession>,
        manualProblems: List<Problem>,
        bankProblems: List<ProblemBankEntry>,
        subjects: List<Subject>,
        goals: LearningGoals,
        settings: SchedulingSettings,
    ): AiStudySnapshot {
        val windowStart = today.minusDays(WINDOW_DAYS - 1L)
        val windowEnd = today
        val subjectNames = subjects.associate { it.id to it.name }
        val completedTimings = timings.filter {
            it.status == com.zhixu.studyos.core.model.StudyTimerStatus.COMPLETED &&
                !it.studyDate.isBefore(windowStart) &&
                !it.studyDate.isAfter(windowEnd)
        }

        val studyMinutesBySubject = completedTimings
            .groupBy { subjectNames[it.subjectId] ?: "未归档科目" }
            .mapValues { (_, items) -> items.sumOf { (it.accumulatedMillis / 60_000L).toInt() } }

        val sessionsByDate = tasks.flatMap { task -> task.sessions.map { task to it } }
        val scheduledSubjectCount = sessionsByDate
            .filter { (_, session) -> session.date in windowStart..windowEnd }
            .groupingBy { (task, _) -> task.subject.ifBlank { "未分类" } }
            .eachCount()
        val completedBySubject = tasks
            .filter { task ->
                val completed = task.completedAt ?: return@filter false
                val date = completed.atZone(zoneId).toLocalDate()
                task.status == TaskStatus.COMPLETED && date in windowStart..windowEnd
            }
            .groupingBy { it.subject.ifBlank { "未分类" } }
            .eachCount()

        val subjectStatNames = (subjectNames.values + scheduledSubjectCount.keys + completedBySubject.keys)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        val subjectStats = subjectStatNames.map { name ->
            AiSubjectStat(
                subjectName = name,
                studyMinutes = studyMinutesBySubject[name] ?: 0,
                completedTasks = completedBySubject[name] ?: 0,
                scheduledTasks = scheduledSubjectCount[name] ?: 0,
            )
        }

        val days = (0 until WINDOW_DAYS.toInt()).map { offset -> windowStart.plusDays(offset.toLong()) }
        val recentDays = days.map { date ->
            AiDayStat(
                date = date,
                studyMinutes = completedTimings
                    .filter { it.studyDate == date }
                    .sumOf { (it.accumulatedMillis / 60_000L).toInt() },
                completedTasks = tasks.count { task ->
                    task.status == TaskStatus.COMPLETED &&
                        task.completedAt?.atZone(zoneId)?.toLocalDate() == date
                },
            )
        }

        val horizonEnd = today.plusDays(UPCOMING_DAYS)
        val upcoming = tasks
            .filter { it.status != TaskStatus.COMPLETED }
            .filter { task ->
                val dueInHorizon = task.dueDate?.let { !it.isBefore(today) && !it.isAfter(horizonEnd) } == true
                val sessionInHorizon = task.sessions.any { it.date in today..horizonEnd }
                dueInHorizon || sessionInHorizon || task.sessions.isEmpty()
            }
            .sortedWith(compareBy<StudyTask>({ it.dueDate ?: LocalDate.MAX }, { it.id }))
            .take(MAX_UPCOMING_TASKS)
            .map { task ->
                AiTaskStat(
                    taskId = task.id,
                    subject = task.subject.ifBlank { "未分类" },
                    title = task.title,
                    estimatedMinutes = task.estimatedMinutes,
                    dueDate = task.dueDate,
                    priority = task.priority.name,
                    scheduledMinutes = task.plannedMinutes,
                    postponeCount = task.postponeCount,
                )
            }

        val difficult = mutableListOf<AiProblemSignal>()
        manualProblems.forEach { problem ->
            if (problem.isMistake || problem.retryCount >= 2 || problem.isDifficult) {
                difficult += AiProblemSignal(
                    source = "习题记录",
                    label = listOf(problem.textbook, problem.chapter, problem.problemNumber)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    subject = subjectNames[problem.subjectId] ?: "未归档科目",
                    chapter = problem.chapter,
                    problemNumber = problem.problemNumber,
                    retryCount = problem.retryCount,
                    isMistake = problem.isMistake,
                    note = problem.knowledgePoint,
                )
            }
        }
        bankProblems.forEach { entry ->
            val slow = entry.actualMinutes != null && entry.estimatedMinutes != null &&
                entry.actualMinutes > entry.estimatedMinutes * 2
            val needsReview = entry.reviewStatus != ProblemReviewStatus.MASTERED &&
                entry.completionStatus == ProblemCompletionStatus.COMPLETED
            if (slow || (needsReview && entry.hasFormulaNeedingReview)) {
                difficult += AiProblemSignal(
                    source = "个人题库",
                    label = listOf(entry.textbook, entry.chapter, entry.problemNumber)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    subject = entry.courseName.ifBlank { "未分类" },
                    chapter = entry.chapter,
                    problemNumber = entry.problemNumber,
                    retryCount = 0,
                    isMistake = false,
                    note = entry.knowledgePoints.joinToString("、") { it.name },
                )
            }
        }

        return AiStudySnapshot(
            generatedAt = Instant.now(clock),
            windowStart = windowStart,
            windowEnd = windowEnd,
            subjects = subjectStats,
            recentDays = recentDays,
            upcomingTasks = upcoming,
            difficultProblems = difficult
                .sortedWith(compareBy({ it.subject }, { it.label }))
                .take(MAX_DIFFICULT_PROBLEMS),
            goals = AiGoalSummary(
                dailyStudyMinutes = goals.dailyStudyMinutes,
                weeklyStudyMinutes = goals.weeklyStudyMinutes,
                dailyTaskTarget = goals.dailyTaskTarget,
            ),
            scheduling = AiSchedulingSummary(
                dailyLimitMinutes = settings.dailyLimitMinutes,
                studyStartMinute = settings.studyStartMinute,
                studyEndMinuteExclusive = settings.studyEndMinuteExclusive,
                bufferMinutes = settings.bufferMinutes,
            ),
        )
    }

    /**
     * 序列化为发送给 AI 的 JSON。遵循隐私开关：
     * - 科目名称关闭时使用「科目1…」掩码（科目分配建议随后无法在本地映射，会被约束检查拒绝）；
     * - 任务标题关闭时使用「任务 #id」；
     * - 习题细节关闭时只发送按科目的数量摘要。
     * 字段顺序固定，便于计算稳定的缓存键。
     */
    fun toJson(snapshot: AiStudySnapshot, config: AiProviderConfig, zoneId: ZoneId): String {
        val subjectNames = (
            snapshot.subjects.map { it.subjectName } +
                snapshot.upcomingTasks.map { it.subject } +
                snapshot.difficultProblems.map { it.subject }
            )
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        val subjectMask = if (config.includeSubjectNames) {
            emptyMap()
        } else {
            subjectNames.mapIndexed { index, name -> name to "科目${index + 1}" }.toMap()
        }
        // 掩码必须覆盖 payload 里所有自由文本科目（未来任务可手填科目），
        // 不能只覆盖科目表/近七天有记录的名称；查不到时也不能回退发送原文。
        fun subjectName(raw: String): String = when {
            raw.isBlank() -> raw
            config.includeSubjectNames -> raw
            else -> subjectMask[raw] ?: "科目-其他"
        }
        fun taskTitle(task: AiTaskStat): String =
            if (config.includeTaskTitles) task.title else "任务 #${task.taskId}"

        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put(
            "window",
            JSONObject()
                .put("start", snapshot.windowStart.toString())
                .put("end", snapshot.windowEnd.toString())
                .put("timezone", zoneId.id),
        )
        root.put(
            "totals",
            JSONObject()
                .put("studyMinutes", snapshot.totalStudyMinutes)
                .put("completedTasks", snapshot.completedTaskCount)
                .put("scheduledTasks", snapshot.subjects.sumOf { it.scheduledTasks }),
        )
        root.put(
            "subjects",
            JSONArray().apply {
                snapshot.subjects.forEach { stat ->
                    put(
                        JSONObject()
                            .put("name", subjectName(stat.subjectName))
                            .put("studyMinutes", stat.studyMinutes)
                            .put("completedTasks", stat.completedTasks)
                            .put("scheduledTasks", stat.scheduledTasks)
                            .put("completionRatePercent", stat.completionRatePercent ?: JSONObject.NULL),
                    )
                }
            },
        )
        root.put(
            "recentDays",
            JSONArray().apply {
                snapshot.recentDays.forEach { day ->
                    put(
                        JSONObject()
                            .put("date", day.date.toString())
                            .put("studyMinutes", day.studyMinutes)
                            .put("completedTasks", day.completedTasks),
                    )
                }
            },
        )
        root.put(
            "upcomingTasks",
            JSONArray().apply {
                snapshot.upcomingTasks.forEach { task ->
                    put(
                        JSONObject()
                            .put("id", task.taskId)
                            .put("subject", subjectName(task.subject))
                            .put("title", taskTitle(task))
                            .put("estimatedMinutes", task.estimatedMinutes)
                            .put("dueDate", task.dueDate?.toString() ?: JSONObject.NULL)
                            .put("priority", task.priority)
                            .put("scheduledMinutes", task.scheduledMinutes)
                            .put("postponeCount", task.postponeCount),
                    )
                }
            },
        )
        root.put(
            "difficultProblems",
            if (config.includeProblemStatements) {
                JSONObject()
                    .put("detailsIncluded", true)
                    .put(
                        "items",
                        JSONArray().apply {
                            snapshot.difficultProblems.forEach { problem ->
                                put(
                                    JSONObject()
                                        .put("source", problem.source)
                                        .put("label", problem.label)
                                        .put("subject", subjectName(problem.subject))
                                        .put("chapter", problem.chapter)
                                        .put("problemNumber", problem.problemNumber)
                                        .put("retryCount", problem.retryCount)
                                        .put("isMistake", problem.isMistake)
                                        .put("note", problem.note),
                                )
                            }
                        },
                    )
            } else {
                // 只发送按科目的数量摘要，不含题号、章节、知识点等细节
                val counts = snapshot.difficultProblems
                    .groupingBy { subjectName(it.subject) }
                    .eachCount()
                    .toSortedMap()
                JSONObject()
                    .put("detailsIncluded", false)
                    .put(
                        "summary",
                        JSONArray().apply {
                            counts.forEach { (name, count) ->
                                put(JSONObject().put("subject", name).put("count", count))
                            }
                        },
                    )
            },
        )
        root.put(
            "goals",
            JSONObject()
                .put("dailyStudyMinutes", snapshot.goals.dailyStudyMinutes ?: JSONObject.NULL)
                .put("weeklyStudyMinutes", snapshot.goals.weeklyStudyMinutes ?: JSONObject.NULL)
                .put("dailyTaskTarget", snapshot.goals.dailyTaskTarget ?: JSONObject.NULL),
        )
        root.put(
            "scheduling",
            JSONObject()
                .put("dailyLimitMinutes", snapshot.scheduling.dailyLimitMinutes)
                .put("studyStartMinute", snapshot.scheduling.studyStartMinute)
                .put("studyEndMinuteExclusive", snapshot.scheduling.studyEndMinuteExclusive)
                .put("bufferMinutes", snapshot.scheduling.bufferMinutes),
        )
        root.put(
            "privacy",
            JSONObject()
                .put("subjectNamesMasked", !config.includeSubjectNames)
                .put("taskTitlesMasked", !config.includeTaskTitles)
                .put("problemDetailsIncluded", config.includeProblemStatements),
        )
        return root.toString()
    }

    companion object {
        const val WINDOW_DAYS = 7L
        const val UPCOMING_DAYS = 7L
        const val MAX_UPCOMING_TASKS = 30
        const val MAX_DIFFICULT_PROBLEMS = 15
    }
}
