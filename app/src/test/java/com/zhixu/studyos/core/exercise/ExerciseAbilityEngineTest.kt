package com.zhixu.studyos.core.exercise

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseAbilityEngineTest {
    private fun attempt(
        index: Int,
        independent: Boolean = true,
        correct: Boolean = true,
        hint: Int = 0,
        minutes: Int = 10,
    ) = ExerciseAttempt(
        problemType = ExerciseProblemType.BANK,
        problemId = 1,
        attemptIndex = index,
        startedAt = Instant.EPOCH,
        submittedAt = Instant.EPOCH,
        durationMinutes = minutes,
        highestHintLevel = hint,
        independent = independent,
        correct = correct,
    )

    @Test
    fun profile_combinesIndependenceHintsTimeAndFailures() {
        val simple = ExerciseAbilityEngine.buildProfile(
            problemType = ExerciseProblemType.BANK,
            problemId = 1,
            attempts = listOf(attempt(1)),
            estimatedDifficulty = 2,
            estimatedMinutes = 10,
            now = Instant.EPOCH,
        )!!
        val hard = ExerciseAbilityEngine.buildProfile(
            problemType = ExerciseProblemType.BANK,
            problemId = 2,
            attempts = listOf(
                attempt(1, independent = false, correct = false, hint = 4, minutes = 30),
                attempt(2, independent = false, correct = true, hint = 3, minutes = 25),
            ),
            estimatedDifficulty = 3,
            estimatedMinutes = 10,
            now = Instant.EPOCH,
        )!!
        assertTrue(hard.personalDifficulty!! > simple.personalDifficulty!!)
        assertTrue(simple.mastery > hard.mastery)
        assertEquals(0.0, hard.independentRate, 0.0001)
    }

    @Test
    fun estimatedMinutes_isCorrectedOnlyWhenActualRepeatedlyDeviates() {
        val attempts = listOf(attempt(1, minutes = 30), attempt(2, minutes = 28))
        assertEquals(30, ExerciseAbilityEngine.suggestEstimatedMinutes(10, attempts))
        assertNull(ExerciseAbilityEngine.suggestEstimatedMinutes(30, attempts))
    }

    @Test
    fun ability_isNullWithTooLittleData() {
        val profile = ExerciseAbilityEngine.buildProfile(
            problemType = ExerciseProblemType.BANK,
            problemId = 1,
            attempts = listOf(attempt(1)),
            estimatedDifficulty = 3,
            estimatedMinutes = 10,
            now = Instant.EPOCH,
        )!!
        val snapshot = ExerciseAbilityEngine.buildSnapshot(
            profiles = listOf(profile),
            candidateSubjectById = mapOf(1L to "数学分析"),
            candidateChapterById = mapOf(1L to "极限"),
            now = Instant.EPOCH,
        )
        assertFalse(snapshot.dataSufficient)
        assertNull(snapshot.overall)
        assertTrue(snapshot.explanation.contains("数据不足"))
    }

    @Test
    fun ability_isEstimatedAfterEnoughAttempts() {
        val profiles = (1L..3L).map { id ->
            ExerciseAbilityEngine.buildProfile(
                problemType = ExerciseProblemType.BANK,
                problemId = id,
                attempts = listOf(attempt(1), attempt(2, minutes = 8)),
                estimatedDifficulty = 3,
                estimatedMinutes = 10,
                now = Instant.EPOCH,
            )!!
        }
        val snapshot = ExerciseAbilityEngine.buildSnapshot(
            profiles = profiles,
            candidateSubjectById = profiles.associate { it.problemId to "数学分析" },
            candidateChapterById = profiles.associate { it.problemId to "极限" },
            now = Instant.EPOCH,
        )
        assertTrue(snapshot.dataSufficient)
        assertNotNull(snapshot.overall)
        assertEquals(6, snapshot.overall!!.sampleCount)
    }

    @Test
    fun abilitySnapshot_doesNotMixManualAndBankProfilesWithTheSameId() {
        val bank = ExerciseAbilityEngine.buildProfile(
            problemType = ExerciseProblemType.BANK,
            problemId = 1,
            attempts = listOf(attempt(1), attempt(2), attempt(3)),
            estimatedDifficulty = 3,
            estimatedMinutes = 10,
            now = Instant.EPOCH,
        )!!
        val manual = ExerciseAbilityEngine.buildProfile(
            problemType = ExerciseProblemType.MANUAL,
            problemId = 1,
            attempts = listOf(attempt(1), attempt(2), attempt(3)),
            estimatedDifficulty = 3,
            estimatedMinutes = 10,
            now = Instant.EPOCH,
        )!!

        val snapshot = ExerciseAbilityEngine.buildSnapshot(
            profiles = listOf(bank, manual),
            candidateSubjectById = mapOf(1L to "数学分析"),
            candidateChapterById = mapOf(1L to "极限"),
            now = Instant.EPOCH,
        )

        assertEquals(6, snapshot.overall?.sampleCount)
        assertEquals(3, snapshot.bySubject.single().sampleCount)
    }

    @Test
    fun recommendations_includeMistakesAndAvoidExtremeOverreach() {
        val candidates = listOf(
            ExerciseCandidate(1, ExerciseProblemType.BANK, "错题", "数学分析", "极限", isMistake = true, estimatedDifficulty = 3),
            ExerciseCandidate(2, ExerciseProblemType.BANK, "基础", "数学分析", "极限", estimatedDifficulty = 1),
            ExerciseCandidate(3, ExerciseProblemType.BANK, "适中", "数学分析", "极限", estimatedDifficulty = 3),
            ExerciseCandidate(4, ExerciseProblemType.BANK, "超纲", "数学分析", "极限", estimatedDifficulty = 5),
        )
        val recommendations = ExerciseAbilityEngine.recommend(candidates, ability = 0.5, limit = 3)
        assertEquals(3, recommendations.size)
        assertTrue(recommendations.any { it.kind == RecommendationKind.REVIEW })
        val overreach = recommendations.firstOrNull { it.candidate.id == 4L }
        if (overreach != null) {
            assertTrue(overreach.kind == RecommendationKind.COMPREHENSIVE)
        }
        assertTrue(recommendations.none { it.candidate.id == 4L && it.score > 1.0 })
    }
}
