package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.SubjectEntity
import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProcrastinationReason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LearningRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
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
                SubjectEntity(name = "高等代数", isPreset = true),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun problemRetryPreservesFirstAttemptAndRecordsSecondIndependence() = runTest {
        val initial = problem()
        val problemId = repository.saveProblem(initial, clock.instant())
        val first = repository.observeSnapshot().first().problems.single()
        assertTrue(first.isDifficult)
        assertFalse(first.isMistake)
        assertEquals(ProblemStatus.COMPLETED, first.status)
        assertFalse(first.latestAttemptIndependent)

        repository.retryProblem(problemId, clock.instant())
        val retrying = repository.observeSnapshot().first().problems.single()
        assertEquals(ProblemStatus.IN_PROGRESS, retrying.status)
        assertEquals(1, retrying.retryCount)
        assertNull(retrying.completedAt)

        repository.saveProblem(
            retrying.copy(
                status = ProblemStatus.COMPLETED,
                secondAttemptIndependent = true,
                completedAt = null,
            ),
            clock.instant(),
        )
        val completedAgain = repository.observeSnapshot().first().problems.single()

        assertEquals(ProblemStatus.COMPLETED, completedAgain.status)
        assertEquals(1, completedAgain.retryCount)
        assertFalse(completedAgain.independentCompleted!!)
        assertTrue(completedAgain.secondAttemptIndependent!!)
        assertTrue(completedAgain.latestAttemptIndependent)
    }

    @Test
    fun selfControlEntryRequiresMeaningfulContentAndCanBeDeleted() = runTest {
        val rejected = runCatching {
            repository.addSelfControlEntry(
                date = LocalDate.of(2026, 10, 25),
                procrastinationReason = null,
                voluntaryInterruption = false,
                note = "",
                now = clock.instant(),
            )
        }
        assertTrue(rejected.isFailure)

        val entry = repository.addSelfControlEntry(
            date = LocalDate.of(2026, 10, 25),
            procrastinationReason = ProcrastinationReason.UNCLEAR_START,
            voluntaryInterruption = true,
            note = "先做最小一步",
            now = clock.instant(),
        )
        assertEquals(ProcrastinationReason.UNCLEAR_START, repository.observeSnapshot().first().selfControlEntries.single().procrastinationReason)
        repository.deleteSelfControlEntry(entry.id)
        assertTrue(repository.observeSnapshot().first().selfControlEntries.isEmpty())
    }

    @Test
    fun goalsAndCustomSubjectsPersist() = runTest {
        val goals = LearningGoals(dailyStudyMinutes = 90, weeklyStudyMinutes = 600, dailyTaskTarget = 3)
        repository.saveGoals(goals, clock.instant())
        assertEquals(goals, repository.observeSnapshot().first().goals)

        repository.addSubject("数学建模")
        val subjects = repository.observeSnapshot().first().subjects
        assertTrue(subjects.any { it.name == "数学建模" && !it.isPreset })
        assertTrue(
            runCatching { repository.addSubject("数学建模") }.isFailure,
        )
    }

    private fun problem() = Problem(
        subjectId = subjectId,
        textbook = "高等代数",
        chapter = "第 3 章",
        problemNumber = "3.2",
        knowledgePoint = "线性空间",
        description = "",
        status = ProblemStatus.COMPLETED,
        independentThinkingMinutes = 25,
        usedAiHint = true,
        blockerReason = BlockerReason.PROOF_CONSTRUCTION,
        isMistake = false,
        isDifficult = false,
        independentCompleted = false,
        retryCount = 0,
        completedAt = clock.instant(),
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
    )
}
