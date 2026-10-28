package com.zhixu.studyos.core.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "semester_settings")
data class SemesterEntity(
    @PrimaryKey val id: Int = 1,
    val startDateEpochDay: Long,
    val zoneId: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "courses",
    indices = [Index(value = ["dayOfWeek"])],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val teacher: String?,
    val classroom: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekParity: String,
    val colorArgb: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "course_exceptions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseId", "originalDateEpochDay"], unique = true),
        Index(value = ["originalDateEpochDay"]),
    ],
)
data class CourseExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val originalDateEpochDay: Long,
    val type: String,
    val targetDateEpochDay: Long?,
    val targetStartMinute: Int?,
    val targetEndMinuteExclusive: Int?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "one_off_events",
    indices = [Index(value = ["dateEpochDay"])],
)
data class OneOffEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dateEpochDay: Long,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val location: String,
    val colorArgb: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "routine_blocks")
data class RoutineBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val repeatType: String,
    val dayOfWeek: Int?,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

fun SemesterEntity.toDomainModel() = SemesterSettings(
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    zoneId = ZoneId.of(zoneId),
)

fun CourseEntity.toDomainModel() = Course(
    id = id,
    name = name,
    teacher = teacher,
    classroom = classroom,
    dayOfWeek = dayOfWeek,
    startMinute = startMinute,
    endMinuteExclusive = endMinuteExclusive,
    startWeek = startWeek,
    endWeek = endWeek,
    weekParity = WeekParity.valueOf(weekParity),
    colorArgb = colorArgb,
)

fun CourseExceptionEntity.toDomainModel() = CourseException(
    id = id,
    courseId = courseId,
    originalDate = LocalDate.ofEpochDay(originalDateEpochDay),
    type = CourseExceptionType.valueOf(type),
    targetDate = targetDateEpochDay?.let(LocalDate::ofEpochDay),
    targetStartMinute = targetStartMinute,
    targetEndMinuteExclusive = targetEndMinuteExclusive,
)

fun OneOffEventEntity.toDomainModel() = OneOffEvent(
    id = id,
    title = title,
    date = LocalDate.ofEpochDay(dateEpochDay),
    startMinute = startMinute,
    endMinuteExclusive = endMinuteExclusive,
    location = location,
    colorArgb = colorArgb,
)

fun RoutineBlockEntity.toDomainModel() = RoutineBlock(
    id = id,
    title = title,
    category = RoutineCategory.valueOf(category),
    repeat = RoutineRepeat.valueOf(repeatType),
    dayOfWeek = dayOfWeek,
    startMinute = startMinute,
    endMinuteExclusive = endMinuteExclusive,
)
