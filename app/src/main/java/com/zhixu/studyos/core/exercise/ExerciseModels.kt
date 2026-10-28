package com.zhixu.studyos.core.exercise

import java.time.Instant

/** 习题来源。手工习题与题库使用不同 ID 空间，以 problemType 区分。 */
enum class ExerciseProblemType { MANUAL, BANK }

data class ExerciseAttempt(
    val id: Long = 0,
    val problemType: ExerciseProblemType,
    val problemId: Long,
    val attemptIndex: Int,
    val startedAt: Instant,
    val submittedAt: Instant,
    val durationMinutes: Int,
    val highestHintLevel: Int,
    val independent: Boolean,
    val correct: Boolean,
    val note: String = "",
)

data class ExerciseProfile(
    val problemType: ExerciseProblemType,
    val problemId: Long,
    val personalDifficulty: Double? = null,
    val difficultySampleCount: Int = 0,
    val attemptCount: Int = 0,
    val independentCount: Int = 0,
    val hintLevelSum: Int = 0,
    val avgActualMinutes: Double = 0.0,
    val estimatedMinutes: Int? = null,
    val mastery: Double = 0.0,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val independentRate: Double
        get() = if (attemptCount == 0) 0.0 else independentCount.toDouble() / attemptCount
    val avgHintLevel: Double
        get() = if (attemptCount == 0) 0.0 else hintLevelSum.toDouble() / attemptCount
}

data class AbilityEstimate(
    val id: Long = 0,
    val scope: String,
    val scopeKey: String,
    val ability: Double,
    val sampleCount: Int,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val label: String
        get() = when {
            ability < 0.25 -> "基础巩固"
            ability < 0.5 -> "稳步提升"
            ability < 0.75 -> "中等挑战"
            else -> "综合拓展"
        }
}

enum class RecommendationKind(val label: String) {
    BASIC("基础巩固"),
    VARIATION("同类变式"),
    CHALLENGE("中等挑战"),
    COMPREHENSIVE("综合证明"),
    REVIEW("错题复习"),
}

data class ExerciseCandidate(
    val id: Long,
    val problemType: ExerciseProblemType,
    val title: String,
    val subject: String,
    val chapter: String,
    val knowledgePoints: List<String> = emptyList(),
    val estimatedDifficulty: Int? = null,
    val estimatedMinutes: Int? = null,
    val isMistake: Boolean = false,
    val needsReview: Boolean = false,
    val mastery: Double = 0.0,
    val personalDifficulty: Double? = null,
)

data class ExerciseRecommendation(
    val candidate: ExerciseCandidate,
    val kind: RecommendationKind,
    val score: Double,
    val reason: String,
)

data class AbilitySnapshot(
    val overall: AbilityEstimate?,
    val bySubject: List<AbilityEstimate>,
    val byChapter: List<AbilityEstimate>,
    val dataSufficient: Boolean,
    val explanation: String,
)

/**
 * 个人习题难度与能力匹配：可解释的规则与统计，不声称具备训练数据驱动的精准预测。
 *
 * 输入只来自真实尝试记录：是否独立、用时、使用过的最高提示等级、重复尝试与复习结果。
 * 当数据不足时明确显示“数据不足”，不生成看似精确的能力值。
 */
object ExerciseAbilityEngine {
    private const val MIN_SAMPLES_FOR_ABILITY = 3

    fun buildProfile(
        problemType: ExerciseProblemType,
        problemId: Long,
        attempts: List<ExerciseAttempt>,
        estimatedDifficulty: Int?,
        estimatedMinutes: Int?,
        now: Instant,
    ): ExerciseProfile? {
        if (attempts.isEmpty()) {
            return ExerciseProfile(
                problemType = problemType,
                problemId = problemId,
                estimatedMinutes = estimatedMinutes,
                updatedAt = now,
            )
        }
        val sorted = attempts.sortedBy { it.attemptIndex }
        val count = sorted.size
        val independent = sorted.count { it.independent }
        val hintSum = sorted.sumOf { it.highestHintLevel.coerceIn(0, 5) }
        val avgMinutes = sorted.sumOf { it.durationMinutes }.toDouble() / count
        val failureCount = sorted.count { !it.correct }
        val baseDifficulty = (estimatedDifficulty ?: 3).coerceIn(1, 5).toDouble()
        val independentRate = independent.toDouble() / count
        val avgHint = hintSum.toDouble() / count
        val timeRatio = if (estimatedMinutes != null && estimatedMinutes > 0) {
            (avgMinutes / estimatedMinutes).coerceIn(0.2, 3.0)
        } else {
            1.0
        }
        val difficulty = (
            baseDifficulty +
                failureCount * 0.25 +
                avgHint * 0.30 +
                (timeRatio - 1.0).coerceAtLeast(0.0) * 0.8 -
                independentRate * 0.45
            ).coerceIn(1.0, 5.0)
        val mastery = (
            independentRate * 0.50 +
                (1.0 - avgHint / 5.0) * 0.25 +
                (1.0 / timeRatio).coerceIn(0.0, 1.0) * 0.15 +
                (1.0 - failureCount.toDouble() / count) * 0.10
            ).coerceIn(0.0, 1.0)
        return ExerciseProfile(
            problemType = problemType,
            problemId = problemId,
            personalDifficulty = roundedOneDecimal(difficulty),
            difficultySampleCount = count,
            attemptCount = count,
            independentCount = independent,
            hintLevelSum = hintSum,
            avgActualMinutes = roundedOneDecimal(avgMinutes),
            estimatedMinutes = estimatedMinutes,
            mastery = roundedOneDecimal(mastery),
            updatedAt = now,
        )
    }

    /** 用时修正：多次超时或提前完成时，逐步修正预计耗时。 */
    fun suggestEstimatedMinutes(
        currentEstimate: Int?,
        attempts: List<ExerciseAttempt>,
    ): Int? {
        if (attempts.size < 2) return null
        val actual = attempts.map { it.durationMinutes }.filter { it > 0 }.sorted()
        if (actual.size < 2) return null
        val median = if (actual.size % 2 == 1) {
            actual[actual.size / 2]
        } else {
            (actual[actual.size / 2 - 1] + actual[actual.size / 2]) / 2
        }
        val current = currentEstimate ?: return roundToFive(median).coerceAtLeast(5)
        val ratio = median.toDouble() / current
        return when {
            ratio >= 1.3 || ratio <= 0.7 -> roundToFive(median).coerceIn(5, 240)
            else -> null
        }
    }

    fun estimateAbility(
        scope: String,
        key: String,
        profiles: List<ExerciseProfile>,
        now: Instant,
    ): AbilityEstimate? {
        val withData = profiles.filter { it.attemptCount > 0 }
        val sampleCount = withData.sumOf { it.attemptCount }
        if (sampleCount < MIN_SAMPLES_FOR_ABILITY) return null
        // 尝试次数越多的题权重略高，但设置上限避免单题主导。
        val weighted = withData.sumOf { profile ->
            profile.mastery * minOf(profile.attemptCount, 5).toDouble()
        }
        val weight = withData.sumOf { minOf(it.attemptCount, 5).toDouble() }
        return AbilityEstimate(
            scope = scope,
            scopeKey = key,
            ability = roundedOneDecimal((weighted / weight).coerceIn(0.0, 1.0)),
            sampleCount = sampleCount,
            updatedAt = now,
        )
    }

    fun buildSnapshot(
        profiles: List<ExerciseProfile>,
        candidateSubjectById: Map<Long, String>,
        candidateChapterById: Map<Long, String>,
        now: Instant,
    ): AbilitySnapshot {
        val overall = estimateAbility("OVERALL", "ALL", profiles, now)
        // MANUAL 和 BANK 的 problemId 是两套 ID 空间；科目/章节映射只对应题库，
        // 不能把手工题的同 ID 档案算进题库科目，反之亦然。
        val bankProfiles = profiles.filter {
            it.problemType == ExerciseProblemType.BANK && candidateSubjectById.containsKey(it.problemId)
        }
        val bySubject = bankProfiles.groupBy { candidateSubjectById.getValue(it.problemId) }
            .mapNotNull { (subject, items) -> estimateAbility("SUBJECT", subject, items, now) }
            .sortedBy { it.scopeKey }
        val byChapter = bankProfiles.groupBy { candidateChapterById[it.problemId] ?: "未知章节" }
            .mapNotNull { (chapter, items) -> estimateAbility("CHAPTER", chapter, items, now) }
            .sortedBy { it.scopeKey }
        return AbilitySnapshot(
            overall = overall,
            bySubject = bySubject,
            byChapter = byChapter,
            dataSufficient = overall != null,
            explanation = if (overall == null) {
                "数据不足：有效尝试少于 ${MIN_SAMPLES_FOR_ABILITY} 次，暂不生成能力估计。"
            } else {
                "能力估计来自 ${overall.sampleCount} 次有效尝试，综合考虑独立性、提示等级、用时与结果；" +
                    "这是规则统计，不是标准化考试分数。"
            },
        )
    }

    /**
     * 推荐时兼顾基础、变式、挑战与错题：不会长期只推简单题，
     * 也不会持续推送明显超过当前能力（> +0.6）的题目。
     */
    fun recommend(
        candidates: List<ExerciseCandidate>,
        ability: Double?,
        limit: Int = 10,
    ): List<ExerciseRecommendation> {
        if (candidates.isEmpty()) return emptyList()
        val effectiveAbility = ability ?: 0.4
        val scored = candidates.map { candidate ->
            val difficulty = (candidate.personalDifficulty ?: candidate.estimatedDifficulty?.toDouble() ?: 3.0)
            val normalizedDifficulty = ((difficulty - 1.0) / 4.0).coerceIn(0.0, 1.0)
            val gap = normalizedDifficulty - effectiveAbility
            val kind = when {
                candidate.needsReview || candidate.isMistake -> RecommendationKind.REVIEW
                normalizedDifficulty <= 0.25 -> RecommendationKind.BASIC
                gap < -0.2 -> RecommendationKind.VARIATION
                gap <= 0.2 -> RecommendationKind.CHALLENGE
                else -> RecommendationKind.COMPREHENSIVE
            }
            val reviewBonus = if (kind == RecommendationKind.REVIEW) 0.35 else 0.0
            val challengeBonus = if (kind == RecommendationKind.CHALLENGE) 0.20 else 0.0
            val overreachPenalty = if (gap > 0.6) (gap - 0.6) * 1.5 else 0.0
            val masteryGap = (1.0 - candidate.mastery).coerceIn(0.0, 1.0) * 0.25
            val score = 1.0 - kotlin.math.abs(gap) + reviewBonus + challengeBonus + masteryGap - overreachPenalty
            val reason = when (kind) {
                RecommendationKind.REVIEW -> "近期失败或标记为错题，适合复习巩固"
                RecommendationKind.BASIC -> "巩固基础概念，建立稳定起点"
                RecommendationKind.VARIATION -> "与当前能力接近，适合作为同类变式练习"
                RecommendationKind.CHALLENGE -> "略高于当前舒适区，适合中等挑战"
                RecommendationKind.COMPREHENSIVE -> "综合性较强，建议在基础稳定后尝试"
            }
            ExerciseRecommendation(
                candidate = candidate,
                kind = kind,
                score = score,
                reason = reason,
            )
        }.sortedByDescending { it.score }
        if (scored.size <= limit) return scored
        // 先保证错题/变式/挑战各有一项，再补足分数最高项，避免单一类型刷屏。
        val picked = linkedMapOf<Long, ExerciseRecommendation>()
        listOf(
            RecommendationKind.REVIEW,
            RecommendationKind.VARIATION,
            RecommendationKind.CHALLENGE,
            RecommendationKind.BASIC,
        ).forEach { kind ->
            scored.firstOrNull { it.kind == kind }?.let { picked[it.candidate.id] = it }
        }
        scored.forEach { recommendation ->
            if (picked.size < limit) picked.putIfAbsent(recommendation.candidate.id, recommendation)
        }
        return picked.values.sortedByDescending { it.score }.take(limit)
    }

    private fun roundedOneDecimal(value: Double): Double =
        Math.round(value * 10.0) / 10.0

    private fun roundToFive(minutes: Int): Int =
        ((minutes + 2) / 5) * 5
}
