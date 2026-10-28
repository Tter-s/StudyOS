package com.zhixu.studyos.core.data.repository

import androidx.room.withTransaction
import com.zhixu.studyos.core.data.local.CourseEntity
import com.zhixu.studyos.core.data.local.CourseExceptionEntity
import com.zhixu.studyos.core.data.local.OneOffEventEntity
import com.zhixu.studyos.core.data.local.RoutineBlockEntity
import com.zhixu.studyos.core.data.local.ScheduleDao
import com.zhixu.studyos.core.data.local.SemesterEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.local.toDomainModel
import com.zhixu.studyos.core.importer.RejectedImportCourse
import com.zhixu.studyos.core.importer.ScheduleImportAnalyzer
import com.zhixu.studyos.core.importer.ScheduleImportConflictException
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.ScheduleConflict
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.schedule.ScheduleConflictDetector
import com.zhixu.studyos.core.schedule.ScheduleValidation
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface ScheduleRepository {
    fun observeSnapshot(): Flow<ScheduleSnapshot>
    suspend fun saveSemester(startDate: LocalDate, zoneId: ZoneId)
    suspend fun saveCourse(course: Course): List<ScheduleConflict>
    suspend fun importCourses(courses: List<Course>): List<Long>
    suspend fun deleteCourse(courseId: Long)
    suspend fun saveException(exception: CourseException): List<ScheduleConflict>
    suspend fun deleteException(exceptionId: Long)
    suspend fun saveEvent(event: OneOffEvent): List<ScheduleConflict>
    suspend fun deleteEvent(eventId: Long)
    suspend fun saveRoutine(routine: RoutineBlock)
    suspend fun deleteRoutine(routineId: Long)
}

class OfflineScheduleRepository(
    private val scheduleDao: ScheduleDao,
    private val clock: Clock,
    private val database: StudyOSDatabase,
) : ScheduleRepository {
    override fun observeSnapshot(): Flow<ScheduleSnapshot> = combine(
        scheduleDao.observeSemester(),
        scheduleDao.observeCourses(),
        scheduleDao.observeExceptions(),
        scheduleDao.observeEvents(),
        scheduleDao.observeRoutines(),
    ) { semester, courses, exceptions, events, routines ->
        ScheduleSnapshot(
            semester = semester?.toDomainModel(),
            courses = courses.map { it.toDomainModel() },
            exceptions = exceptions.map { it.toDomainModel() },
            events = events.map { it.toDomainModel() },
            routines = routines.map { it.toDomainModel() },
        )
    }

    override suspend fun saveSemester(startDate: LocalDate, zoneId: ZoneId) {
        scheduleDao.upsertSemester(
            SemesterEntity(
                startDateEpochDay = startDate.toEpochDay(),
                zoneId = zoneId.id,
                updatedAtEpochMillis = clock.millis(),
            ),
        )
    }

    override suspend fun saveCourse(course: Course): List<ScheduleConflict> {
        ScheduleValidation.requireValidCourse(course)
        val snapshot = getSnapshot()
        val conflicts = ScheduleConflictDetector.findCourseConflicts(course, snapshot)
        if (conflicts.isNotEmpty()) return conflicts

        val now = clock.millis()
        val previous = if (course.id == 0L) null else scheduleDao.getCourse(course.id)
        if (course.id != 0L && previous == null) throw IllegalArgumentException("课程不存在，可能已被删除")
        val entity = CourseEntity(
            id = course.id,
            name = course.name.trim(),
            teacher = course.teacher?.trim()?.takeIf(String::isNotEmpty),
            classroom = course.classroom.trim(),
            dayOfWeek = course.dayOfWeek,
            startMinute = course.startMinute,
            endMinuteExclusive = course.endMinuteExclusive,
            startWeek = course.startWeek,
            endWeek = course.endWeek,
            weekParity = course.weekParity.name,
            colorArgb = course.colorArgb,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        if (course.id == 0L) scheduleDao.insertCourse(entity) else scheduleDao.updateCourse(entity)
        return emptyList()
    }

    override suspend fun importCourses(courses: List<Course>): List<Long> {
        require(courses.isNotEmpty()) { "没有可导入的课程" }
        courses.forEach(ScheduleValidation::requireValidCourse)
        return database.withTransaction {
            val snapshot = getSnapshot()
            requireNotNull(snapshot.semester) { "请先设置学期后再导入课程" }
            val existing = snapshot.courses.toMutableList()
            val accepted = mutableListOf<Course>()
            val rejected = mutableListOf<RejectedImportCourse>()
            courses.forEach { course ->
                val overlaps = ScheduleImportAnalyzer.findOverlaps(course, existing)
                if (overlaps.isNotEmpty()) {
                    rejected += RejectedImportCourse(course.name, overlaps)
                } else {
                    accepted += course
                    // Negative temporary ids keep batch candidates distinct while
                    // later candidates are checked against them.
                    existing += course.copy(id = -accepted.size.toLong())
                }
            }
            if (rejected.isNotEmpty()) throw ScheduleImportConflictException(rejected)

            val now = clock.millis()
            accepted.map { course ->
                scheduleDao.insertCourse(
                    CourseEntity(
                        name = course.name.trim(),
                        teacher = course.teacher?.trim()?.takeIf(String::isNotEmpty),
                        classroom = course.classroom.trim(),
                        dayOfWeek = course.dayOfWeek,
                        startMinute = course.startMinute,
                        endMinuteExclusive = course.endMinuteExclusive,
                        startWeek = course.startWeek,
                        endWeek = course.endWeek,
                        weekParity = course.weekParity.name,
                        colorArgb = course.colorArgb,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }
        }
    }

    override suspend fun deleteCourse(courseId: Long) = scheduleDao.deleteCourse(courseId)

    override suspend fun saveException(exception: CourseException): List<ScheduleConflict> {
        ScheduleValidation.requireValidException(exception)
        val snapshot = getSnapshot()
        val conflicts = ScheduleConflictDetector.findExceptionConflicts(exception, snapshot)
        if (conflicts.isNotEmpty()) return conflicts

        val now = clock.millis()
        val previous = if (exception.id == 0L) null else scheduleDao.getException(exception.id)
        scheduleDao.upsertException(
            CourseExceptionEntity(
                id = exception.id,
                courseId = exception.courseId,
                originalDateEpochDay = exception.originalDate.toEpochDay(),
                type = exception.type.name,
                targetDateEpochDay = exception.targetDate?.toEpochDay(),
                targetStartMinute = exception.targetStartMinute,
                targetEndMinuteExclusive = exception.targetEndMinuteExclusive,
                createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
        return emptyList()
    }

    override suspend fun deleteException(exceptionId: Long) = scheduleDao.deleteException(exceptionId)

    override suspend fun saveEvent(event: OneOffEvent): List<ScheduleConflict> {
        ScheduleValidation.requireValidEvent(event)
        val conflicts = ScheduleConflictDetector.findEventConflicts(event, getSnapshot(), clock.zone)
        if (conflicts.isNotEmpty()) return conflicts

        val now = clock.millis()
        val previous = if (event.id == 0L) null else scheduleDao.getEvent(event.id)
        if (event.id != 0L && previous == null) throw IllegalArgumentException("日程不存在，可能已被删除")
        val entity = OneOffEventEntity(
            id = event.id,
            title = event.title.trim(),
            dateEpochDay = event.date.toEpochDay(),
            startMinute = event.startMinute,
            endMinuteExclusive = event.endMinuteExclusive,
            location = event.location.trim(),
            colorArgb = event.colorArgb,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        if (event.id == 0L) scheduleDao.insertEvent(entity) else scheduleDao.updateEvent(entity)
        return emptyList()
    }

    override suspend fun deleteEvent(eventId: Long) = scheduleDao.deleteEvent(eventId)

    override suspend fun saveRoutine(routine: RoutineBlock) {
        ScheduleValidation.requireValidRoutine(routine)
        val now = clock.millis()
        val previous = if (routine.id == 0L) null else scheduleDao.getRoutine(routine.id)
        if (routine.id != 0L && previous == null) throw IllegalArgumentException("事务不存在，可能已被删除")
        val entity = RoutineBlockEntity(
            id = routine.id,
            title = routine.title.trim(),
            category = routine.category.name,
            repeatType = routine.repeat.name,
            dayOfWeek = routine.dayOfWeek,
            startMinute = routine.startMinute,
            endMinuteExclusive = routine.endMinuteExclusive,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        if (routine.id == 0L) scheduleDao.insertRoutine(entity) else scheduleDao.updateRoutine(entity)
    }

    override suspend fun deleteRoutine(routineId: Long) = scheduleDao.deleteRoutine(routineId)

    private suspend fun getSnapshot() = ScheduleSnapshot(
        semester = scheduleDao.getSemester()?.toDomainModel(),
        courses = scheduleDao.getCourses().map { it.toDomainModel() },
        exceptions = scheduleDao.getExceptions().map { it.toDomainModel() },
        events = scheduleDao.getEvents().map { it.toDomainModel() },
        routines = scheduleDao.getRoutines().map { it.toDomainModel() },
    )
}
