package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM semester_settings WHERE id = 1 LIMIT 1")
    fun observeSemester(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semester_settings WHERE id = 1 LIMIT 1")
    suspend fun getSemester(): SemesterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSemester(settings: SemesterEntity)

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, startMinute, id")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, startMinute, id")
    suspend fun getCourses(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourse(id: Long): CourseEntity?

    @Insert
    suspend fun insertCourse(course: CourseEntity): Long

    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourse(id: Long)

    @Query("SELECT * FROM course_exceptions ORDER BY originalDateEpochDay, id")
    fun observeExceptions(): Flow<List<CourseExceptionEntity>>

    @Query("SELECT * FROM course_exceptions ORDER BY originalDateEpochDay, id")
    suspend fun getExceptions(): List<CourseExceptionEntity>

    @Query("SELECT * FROM course_exceptions WHERE id = :id LIMIT 1")
    suspend fun getException(id: Long): CourseExceptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: CourseExceptionEntity): Long

    @Query("DELETE FROM course_exceptions WHERE id = :id")
    suspend fun deleteException(id: Long)

    @Query("SELECT * FROM one_off_events ORDER BY dateEpochDay, startMinute, id")
    fun observeEvents(): Flow<List<OneOffEventEntity>>

    @Query("SELECT * FROM one_off_events ORDER BY dateEpochDay, startMinute, id")
    suspend fun getEvents(): List<OneOffEventEntity>

    @Query("SELECT * FROM one_off_events WHERE id = :id LIMIT 1")
    suspend fun getEvent(id: Long): OneOffEventEntity?

    @Insert
    suspend fun insertEvent(event: OneOffEventEntity): Long

    @Update
    suspend fun updateEvent(event: OneOffEventEntity)

    @Query("DELETE FROM one_off_events WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("SELECT * FROM routine_blocks ORDER BY category, startMinute, id")
    fun observeRoutines(): Flow<List<RoutineBlockEntity>>

    @Query("SELECT * FROM routine_blocks ORDER BY category, startMinute, id")
    suspend fun getRoutines(): List<RoutineBlockEntity>

    @Query("SELECT * FROM routine_blocks WHERE id = :id LIMIT 1")
    suspend fun getRoutine(id: Long): RoutineBlockEntity?

    @Insert
    suspend fun insertRoutine(routine: RoutineBlockEntity): Long

    @Update
    suspend fun updateRoutine(routine: RoutineBlockEntity)

    @Query("DELETE FROM routine_blocks WHERE id = :id")
    suspend fun deleteRoutine(id: Long)
}
