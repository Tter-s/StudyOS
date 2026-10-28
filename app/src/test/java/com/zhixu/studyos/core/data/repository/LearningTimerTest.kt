package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.SubjectEntity
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.TimerInterruptionReason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LearningTimerTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), zoneId)
    private lateinit var database: StudyOSDatabase
    private lateinit var repository: LearningRepository
    private var subjectId = 0L

    @Before
    fun setUp() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                StudyOSDatabase::class.java,
            ).allowMainThreadQueries().build()
            repository = OfflineLearningRepository(database, database.learningDao(), clock)
            subjectId = database.learningDao().insertSubject(
                SubjectEntity(name = "数学分析", isPreset = true),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stopwatchPersistsOnlyActiveForegroundSegments() = runTest {
        val start = Instant.parse("2026-10-25T01:00:00Z")
        val created = repository.startTiming(
            subjectId = subjectId,
            taskId = null,
            mode = StudyTimerMode.STOPWATCH,
            plannedDurationMinutes = null,
            now = start,
        )

        repository.checkpointTiming(created.id, start.plusSeconds(5))
        repository.pauseTiming(created.id, TimerInterruptionReason.USER, start.plusSeconds(8))
        val paused = repository.getActiveTiming()
        assertEquals(StudyTimerStatus.PAUSED, paused?.status)
        assertEquals(8_000L, paused?.accumulatedMillis)
        assertEquals(1, paused?.interruptionCount)

        repository.resumeTiming(created.id, start.plusSeconds(3_608))
        repository.finishTiming(created.id, start.plusSeconds(3_618))

        val completed = repository.observeSnapshot().first().timings.single()
        assertEquals(StudyTimerStatus.COMPLETED, completed.status)
        assertEquals(18_000L, completed.accumulatedMillis)
        assertEquals(LocalDate.of(2026, 10, 25), completed.studyDate)
    }

    @Test
    fun processRecoveryDiscardsUnknownBackgroundGap() = runTest {
        val start = Instant.parse("2026-10-25T01:00:00Z")
        val created = repository.startTiming(
            subjectId = subjectId,
            taskId = null,
            mode = StudyTimerMode.STOPWATCH,
            plannedDurationMinutes = null,
            now = start,
        )
        repository.checkpointTiming(created.id, start.plusSeconds(5))

        val recovered = repository.recoverRunningSessions(start.plusSeconds(3_600))

        val session = database.learningDao().getTimingSession(created.id)!!.toDomainModel()
        assertEquals(1, recovered)
        assertEquals(StudyTimerStatus.PAUSED, session.status)
        assertEquals(5_000L, session.accumulatedMillis)
        assertEquals(TimerInterruptionReason.PROCESS_RECOVERY, session.lastInterruptionReason)
    }

    @Test
    fun manualEntryCanBeCorrectedAndDeleted() = runTest {
        val manual = repository.addManualTiming(
            subjectId = subjectId,
            taskId = null,
            studyDate = LocalDate.of(2026, 10, 24),
            startedAt = Instant.parse("2026-10-24T10:00:00Z"),
            durationMinutes = 45,
            note = "补录",
            now = clock.instant(),
        )

        assertEquals(45 * 60_000L, manual.accumulatedMillis)
        repository.correctTiming(manual.id, 60, "修正后", clock.instant())
        val corrected = repository.observeSnapshot().first().timings.single()
        assertEquals(60 * 60_000L, corrected.accumulatedMillis)
        assertEquals("修正后", corrected.note)
        assertNotNull(corrected.correctedAt)

        repository.deleteTiming(manual.id)
        assertTrue(repository.observeSnapshot().first().timings.isEmpty())
    }

    @Test
    fun pomodoroRemainingDoesNotAdvanceWhilePaused() = runTest {
        val start = Instant.parse("2026-10-25T02:00:00Z")
        repository.startTiming(
            subjectId = subjectId,
            taskId = null,
            mode = StudyTimerMode.POMODORO,
            plannedDurationMinutes = 25,
            now = start,
        )
        repository.pauseTiming(
            repository.getActiveTiming()!!.id,
            TimerInterruptionReason.USER,
            start.plusSeconds(10 * 60),
        )

        val paused = repository.getActiveTiming()!!
        assertEquals(15 * 60_000L, paused.remainingMillis(start.plusSeconds(90 * 60)))
        assertEquals(10 * 60_000L, paused.accumulatedMillis)
    }

    @Test
    fun onlyOneTimerCanBeActiveAndPomodoroRequiresValidLimit() = runTest {
        val start = clock.instant()
        repository.startTiming(
            subjectId = subjectId,
            taskId = null,
            mode = StudyTimerMode.POMODORO,
            plannedDurationMinutes = 25,
            now = start,
        )
        val second = runCatching {
            repository.startTiming(
                subjectId = subjectId,
                taskId = null,
                mode = StudyTimerMode.STOPWATCH,
                plannedDurationMinutes = null,
                now = start.plusSeconds(1),
            )
        }
        assertTrue(second.isFailure)

        repository.finishTiming(repository.getActiveTiming()!!.id, start.plusSeconds(10))
        assertNull(repository.getActiveTiming())
    }
}
