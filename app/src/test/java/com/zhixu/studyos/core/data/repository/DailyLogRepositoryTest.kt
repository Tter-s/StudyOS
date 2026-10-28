package com.zhixu.studyos.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DailyLogRepositoryTest {
    private lateinit var database: StudyOSDatabase
    private lateinit var repository: DailyLogRepository
    private val date = LocalDate.of(2026, 10, 26)
    private val clock = Clock.fixed(
        Instant.parse("2026-10-26T12:00:00Z"),
        ZoneId.of("Asia/Shanghai"),
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, StudyOSDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OfflineDailyLogRepository(database.dailyLogDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addRenameCompleteAndDeleteTask() = runTest {
        val id = repository.addTask(date, "  读书  ")
        repository.addTask(date, "散步")

        val tasks = repository.observeTasks(date).first()
        assertEquals(2, tasks.size)
        assertEquals("读书", tasks.first().title)
        assertEquals(0, tasks.count { it.isCompleted })

        repository.setTaskCompleted(id, true)
        val completed = repository.observeTasks(date).first().first { it.id == id }
        assertTrue(completed.isCompleted)
        assertEquals(clock.millis(), completed.completedAtEpochMillis)

        repository.renameTask(id, "读一章书")
        assertEquals("读一章书", repository.observeTasks(date).first().first { it.id == id }.title)

        repository.setTaskCompleted(id, false)
        assertNull(repository.observeTasks(date).first().first { it.id == id }.completedAtEpochMillis)

        repository.deleteTask(id)
        assertEquals(1, repository.observeTasks(date).first().size)
    }

    @Test
    fun tasksAreScopedToTheirOwnDay() = runTest {
        repository.addTask(date, "今天")
        repository.addTask(date.plusDays(1), "明天")

        assertEquals(listOf("今天"), repository.observeTasks(date).first().map { it.title })
        assertEquals(listOf("明天"), repository.observeTasks(date.plusDays(1)).first().map { it.title })
    }

    @Test
    fun summaryAndEntertainmentRoundTrip() = runTest {
        repository.saveSummary(date, "  今天做了三件事  ")
        assertEquals("今天做了三件事", repository.observeSummary(date).first())

        repository.saveEntertainment(date, 95)
        assertEquals(95, repository.observeEntertainment(date).first())

        repository.saveEntertainment(date, 120)
        assertEquals(120, repository.observeEntertainment(date).first())

        repository.deleteEntertainment(date)
        assertNull(repository.observeEntertainment(date).first())

        repository.saveSummary(date, "   ")
        assertEquals("", repository.observeSummary(date).first())
    }

    @Test
    fun historyContainsDaysWithAnyRecord() = runTest {
        repository.addTask(date, "完成一件事")
        repository.setTaskCompleted(repository.observeTasks(date).first().single().id, true)
        repository.saveSummary(date.minusDays(1), "昨天的总结")
        repository.saveEntertainment(date.minusDays(2), 30)
        repository.addTask(date.plusDays(1), "明天的待办")

        val history = repository.observeHistory().first()
        assertTrue(history.none { it.date == date.plusDays(1) })
        val today = history.first { it.date == date }
        assertEquals(1, today.totalTasks)
        assertEquals(1, today.completedTasks)
        val yesterday = history.first { it.date == date.minusDays(1) }
        assertEquals("昨天的总结", yesterday.summary)
        val twoDaysAgo = history.first { it.date == date.minusDays(2) }
        assertEquals(30, twoDaysAgo.entertainmentMinutes)
    }

    @Test
    fun invalidInputIsRejected() = runTest {
        var message: String? = null
        runCatching { repository.addTask(date, "   ") }.onFailure { message = it.message }
        assertTrue(message?.contains("请输入") == true)

        message = null
        runCatching { repository.saveEntertainment(date, 24 * 60 + 1) }
            .onFailure { message = it.message }
        assertTrue(message != null)
    }
}
