package com.zhixu.studyos.core.data.repository

import com.zhixu.studyos.core.data.local.ExerciseHintRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseAttemptHintTest {

    @Test
    fun oldHintsOutsideAttemptWindow_doNotAffectIndependence() {
        val hints = listOf(
            hint(level = 4, at = 1_000),
            hint(level = 2, at = 20_000),
        )

        val level = attemptHintLevel(
            hints = hints,
            attemptStartEpochMillis = 10_000,
            attemptEndEpochMillis = 30_000,
        )

        assertEquals(2, level)
    }

    @Test
    fun hintInsideWindow_isCounted() {
        val level = attemptHintLevel(
            hints = listOf(hint(level = 3, at = 10_000)),
            attemptStartEpochMillis = 10_000,
            attemptEndEpochMillis = 10_000,
        )

        assertEquals(3, level)
    }

    @Test
    fun noHints_returnsZero() {
        assertEquals(0, attemptHintLevel(emptyList(), 0, 10_000))
    }

    private fun hint(level: Int, at: Long) = ExerciseHintRecordEntity(
        problemType = "BANK",
        problemId = 1,
        level = level,
        requestedAtEpochMillis = at,
        content = "hint",
        usedCache = false,
        model = "test",
        costMicros = 0,
    )
}
