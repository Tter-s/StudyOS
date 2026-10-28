package com.zhixu.studyos.core.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StudyOSDatabaseTest {
    private lateinit var database: StudyOSDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndObserve_onlyReturnsTasksScheduledForRequestedDayWithSessions() = runTest {
        val dao = database.studyTaskDao()
        val todayId = dao.insert(task(title = "今天的任务", status = "SCHEDULED"))
        val tomorrowId = dao.insert(task(title = "明天的任务", status = "SCHEDULED"))
        dao.insertSessions(
            listOf(
                session(todayId, day = 20_000, start = 9 * 60, end = 10 * 60),
                session(tomorrowId, day = 20_001, start = 9 * 60, end = 10 * 60),
            ),
        )

        val today = dao.observeByDate(20_000).first()

        assertEquals(1, today.size)
        assertEquals("今天的任务", today.single().task.title)
        assertEquals(1, today.single().sessions.size)
        assertEquals(2, dao.count())
    }

    @Test
    fun previousDayCrossMidnightSession_appearsOnFollowingDay() = runTest {
        val dao = database.studyTaskDao()
        val id = dao.insert(task(title = "跨日复习", status = "SCHEDULED"))
        dao.insertSessions(listOf(session(id, day = 20_000, start = 23 * 60, end = 25 * 60)))

        val nextDay = dao.observeByDate(20_001).first()

        assertEquals(1, nextDay.size)
        assertEquals(id, nextDay.single().task.id)
    }

    @Test
    fun presetSubjects_areSeededIdempotently() = runTest {
        StudyOSDatabase.seedPresetSubjects(database.openHelper.writableDatabase)
        StudyOSDatabase.seedPresetSubjects(database.openHelper.writableDatabase)

        val subjects = database.learningDao().getSubjects()
        assertEquals(5, subjects.size)
        assertEquals(listOf("数学分析", "高等代数", "大学英语", "空间解析几何", "编程"), subjects.map { it.name })
    }

    private fun task(title: String, status: String) = StudyTaskEntity(
        title = title,
        description = "",
        subject = "数学分析",
        priority = "MEDIUM",
        estimatedMinutes = 45,
        dueDateEpochDay = null,
        status = status,
        completedAtEpochMillis = null,
        seriesId = null,
        recurrenceType = "NONE",
        recurrenceInterval = 1,
        recurrenceUntilEpochDay = null,
        sortOrder = 0,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

    private fun session(taskId: Long, day: Long, start: Int, end: Int) = TaskSessionEntity(
        taskId = taskId,
        dateEpochDay = day,
        startMinute = start,
        endMinuteExclusive = end,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )
}
