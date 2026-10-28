package com.zhixu.studyos.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.importer.ScheduleImportConflictException
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.WeekParity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflineScheduleRepositoryTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), zoneId)
    private lateinit var database: StudyOSDatabase
    private lateinit var repository: ScheduleRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyOSDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = OfflineScheduleRepository(database.scheduleDao(), clock, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun semesterCourseExceptionEventAndRoutine_persistTogether() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)
        repository.saveCourse(course())
        repository.saveException(
            CourseException(
                courseId = 1,
                originalDate = LocalDate.of(2026, 9, 7),
                type = CourseExceptionType.CANCEL,
            ),
        )
        repository.saveEvent(
            OneOffEvent(
                title = "班会",
                date = LocalDate.of(2026, 9, 8),
                startMinute = 18 * 60,
                endMinuteExclusive = 19 * 60,
                colorArgb = 0xFFFFB74D.toInt(),
            ),
        )
        repository.saveRoutine(
            RoutineBlock(
                title = "睡眠",
                category = RoutineCategory.SLEEP,
                repeat = RoutineRepeat.DAILY,
                startMinute = 23 * 60,
                endMinuteExclusive = 31 * 60,
            ),
        )

        val snapshot = repository.observeSnapshot().first()

        assertEquals(LocalDate.of(2026, 9, 7), snapshot.semester?.startDate)
        assertEquals(zoneId, snapshot.semester?.zoneId)
        assertEquals(1, snapshot.courses.size)
        assertEquals(1, snapshot.exceptions.size)
        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.routines.size)
    }

    @Test
    fun conflictingCourse_isReportedAndNotPersisted() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)
        repository.saveCourse(course())
        val conflicting = course(id = 0, name = "高等代数", startMinute = 8 * 60 + 30, endMinuteExclusive = 9 * 60 + 30)

        val conflicts = repository.saveCourse(conflicting)

        assertEquals(4, conflicts.size)
        val snapshot = repository.observeSnapshot().first()
        assertEquals(1, snapshot.courses.size)
        assertEquals("数学分析", snapshot.courses.single().name)
    }

    @Test
    fun importCourses_insertsAllAndKeepsExistingCourses() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)
        repository.saveCourse(course(name = "数学分析", startMinute = 8 * 60, endMinuteExclusive = 9 * 60))

        val ids = repository.importCourses(
            listOf(
                course(name = "高等代数", dayOfWeek = 2, startMinute = 10 * 60, endMinuteExclusive = 11 * 60),
                course(name = "大学英语", dayOfWeek = 3, startMinute = 14 * 60, endMinuteExclusive = 15 * 60),
            ),
        )

        assertEquals(2, ids.size)
        assertTrue(ids.all { it > 0L })
        val snapshot = repository.observeSnapshot().first()
        assertEquals(3, snapshot.courses.size)
        assertEquals("数学分析", snapshot.courses.first { it.id == 1L }.name)
        assertEquals(setOf("数学分析", "高等代数", "大学英语"), snapshot.courses.map { it.name }.toSet())
    }

    @Test
    fun importCourses_conflictRejectsWholeBatchWithoutWritingAnything() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)
        repository.saveCourse(course())

        try {
            repository.importCourses(
                listOf(
                    course(name = "大学英语", dayOfWeek = 5, startMinute = 14 * 60, endMinuteExclusive = 15 * 60),
                    course(name = "高等代数", startMinute = 8 * 60 + 30, endMinuteExclusive = 9 * 60 + 30),
                ),
            )
            fail("应当拒绝有冲突的整批导入")
        } catch (_: ScheduleImportConflictException) {
            // expected
        }

        val snapshot = repository.observeSnapshot().first()
        assertEquals(1, snapshot.courses.size)
        assertEquals("数学分析", snapshot.courses.single().name)
    }

    @Test
    fun importCourses_internalBatchConflictRejectsWholeBatch() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)

        try {
            repository.importCourses(
                listOf(
                    course(name = "高等代数", dayOfWeek = 2, startMinute = 10 * 60, endMinuteExclusive = 11 * 60),
                    course(name = "大学英语", dayOfWeek = 2, startMinute = 10 * 60, endMinuteExclusive = 11 * 60),
                ),
            )
            fail("应当拒绝批次内冲突")
        } catch (_: ScheduleImportConflictException) {
            // expected
        }

        assertTrue(repository.observeSnapshot().first().courses.isEmpty())
    }

    @Test
    fun importCourses_withoutSemesterIsRejected() = runTest {
        try {
            repository.importCourses(listOf(course(name = "高等代数")))
            fail("未设置学期时不应导入")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(repository.observeSnapshot().first().courses.isEmpty())
    }

    @Test
    fun importCourses_duplicateIsRejected() = runTest {
        repository.saveSemester(LocalDate.of(2026, 9, 7), zoneId)
        repository.saveCourse(course())

        try {
            repository.importCourses(listOf(course(name = "数学 分析")))
            fail("同名且时间重叠的课程应被拒绝")
        } catch (_: ScheduleImportConflictException) {
            // expected
        }

        assertEquals(1, repository.observeSnapshot().first().courses.size)
    }

    private fun course(
        id: Long = 0,
        name: String = "数学分析",
        dayOfWeek: Int = 1,
        startMinute: Int = 8 * 60,
        endMinuteExclusive: Int = 9 * 60,
    ) = Course(
        id = id,
        name = name,
        teacher = "王老师",
        classroom = "A101",
        dayOfWeek = dayOfWeek,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        startWeek = 1,
        endWeek = 4,
        weekParity = WeekParity.ALL,
        colorArgb = 0xFF64B5F6.toInt(),
    )
}
