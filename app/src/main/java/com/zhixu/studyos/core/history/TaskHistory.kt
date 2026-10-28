package com.zhixu.studyos.core.history

import com.zhixu.studyos.core.data.local.HistoryDao
import com.zhixu.studyos.core.data.local.TaskEventEntity
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** 历史对象类型。使用字符串持久化，便于未来扩展而不破坏旧数据。 */
enum class HistoryObjectType {
    TASK,
    EXERCISE,
    VOCABULARY,
    KNOWLEDGE,
    EXAM,
    GOAL,
    FINANCE,
    SLEEP,
}

/** 事件来源，至少区分用户、本地排程、AI 建议与系统。 */
enum class HistorySource { USER, LOCAL_SCHEDULER, AI_SUGGESTION, SYSTEM }

/**
 * 事件类型。覆盖 V2.0 第 2.2 节要求的关键事件；
 * `TASK_CANCELLED` 同时用于显式取消与删除前记录，删除不会级联清除历史。
 */
enum class TaskEventType(val label: String) {
    TASK_CREATED("创建任务"),
    TASK_EDITED("编辑任务"),
    PRIORITY_CHANGED("调整优先级"),
    DUE_DATE_CHANGED("修改截止日期"),
    AUTO_SCHEDULED("自动排程"),
    MANUAL_SCHEDULED("手动排程"),
    TASK_STARTED("开始任务"),
    TASK_PAUSED("暂停任务"),
    TASK_INTERRUPTED("任务中断"),
    TASK_COMPLETED("完成任务"),
    TASK_REOPENED("重新开启任务"),
    TASK_POSTPONED("任务延期"),
    TASK_CANCELLED("取消任务"),
    TASK_DELETED("删除任务"),
    SESSION_LOCKED("锁定时段"),
    SESSION_UNLOCKED("解锁时段"),
    AI_SUGGESTION_ACCEPTED("接受 AI 建议"),
    AI_SUGGESTION_REJECTED("拒绝 AI 建议"),
    EXERCISE_FIRST_ATTEMPT("习题第一次尝试"),
    EXERCISE_RETRY("习题再次尝试"),
    AI_HINT_USED("使用 AI 提示"),
    INDEPENDENT_PROOF("独立完成证明"),
    REVIEW_SUCCEEDED("复习成功"),
    REVIEW_FAILED("复习失败"),
    VOCABULARY_REVIEWED("词汇复习"),
    SLEEP_SCHEDULE_CHANGED("修改睡眠设置"),
    SLEEP_RECORDED("记录睡眠"),
    EXAM_CREATED("创建考试"),
    EXAM_EDITED("编辑考试"),
    GOAL_CREATED("创建目标"),
    GOAL_EDITED("编辑目标"),
    GOAL_COMPLETED("完成目标"),
    KNOWLEDGE_UPDATED("更新知识点"),
    KNOWLEDGE_DEPENDENCY_CHANGED("调整知识依赖"),
    FINANCE_EDITED("财务记录变更"),
}

data class TaskEvent(
    val id: Long = 0,
    val objectType: HistoryObjectType,
    val objectId: Long,
    val eventType: TaskEventType,
    val occurredAt: Instant,
    val beforeJson: String?,
    val afterJson: String?,
    val source: HistorySource,
    val reason: String? = null,
    val sessionId: Long? = null,
    val revision: Int = 0,
)

fun TaskEventEntity.toDomainModel(): TaskEvent = TaskEvent(
    id = id,
    objectType = runCatching { HistoryObjectType.valueOf(objectType) }.getOrDefault(HistoryObjectType.TASK),
    objectId = objectId,
    eventType = runCatching { TaskEventType.valueOf(eventType) }.getOrDefault(TaskEventType.TASK_EDITED),
    occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis),
    beforeJson = beforeJson,
    afterJson = afterJson,
    source = runCatching { HistorySource.valueOf(source) }.getOrDefault(HistorySource.SYSTEM),
    reason = reason,
    sessionId = sessionId,
    revision = revision,
)

/**
 * 历史记录器。所有写入都应由调用方放在业务事务内，
 * 保证业务数据和事件同时成功或同时回滚。
 */
class HistoryRecorder(
    private val dao: HistoryDao,
    private val clock: Clock,
) {
    suspend fun record(
        objectType: HistoryObjectType,
        objectId: Long,
        eventType: TaskEventType,
        beforeJson: String? = null,
        afterJson: String? = null,
        source: HistorySource = HistorySource.USER,
        reason: String? = null,
        sessionId: Long? = null,
        revision: Int = 0,
        occurredAt: Instant? = Instant.now(clock),
    ): Long = dao.insert(
        TaskEventEntity(
            objectType = objectType.name,
            objectId = objectId,
            eventType = eventType.name,
            occurredAtEpochMillis = (occurredAt ?: Instant.now(clock)).toEpochMilli(),
            beforeJson = beforeJson,
            afterJson = afterJson,
            source = source.name,
            reason = reason,
            sessionId = sessionId,
            revision = revision,
        ),
    )

    suspend fun recordTaskChange(
        before: StudyTask?,
        after: StudyTask,
        eventType: TaskEventType,
        source: HistorySource = HistorySource.USER,
        reason: String? = null,
        explicitEventType: Boolean = false,
        sessionId: Long? = null,
        occurredAt: Instant? = Instant.now(clock),
    ) {
        val derived = if (explicitEventType) eventType else deriveTaskEvent(before, after, clock.zone)
        record(
            objectType = HistoryObjectType.TASK,
            objectId = after.id,
            eventType = derived,
            beforeJson = before?.let(TaskSnapshotJson::encode),
            afterJson = TaskSnapshotJson.encode(after),
            source = source,
            reason = reason,
            sessionId = sessionId,
            revision = after.revision,
            occurredAt = occurredAt,
        )
    }

    companion object {
        /** 根据前后状态推断最具体的事件类型；无法判断时退回编辑事件。 */
        fun deriveTaskEvent(before: StudyTask?, after: StudyTask, zoneId: ZoneId): TaskEventType {
            if (before == null) return TaskEventType.TASK_CREATED
            if (before.isCompleted != after.isCompleted) {
                return if (after.isCompleted) TaskEventType.TASK_COMPLETED else TaskEventType.TASK_REOPENED
            }
            if (before.priority != after.priority) return TaskEventType.PRIORITY_CHANGED
            if (before.dueDate != after.dueDate) return TaskEventType.DUE_DATE_CHANGED
            if (before.status != after.status && after.status == TaskStatus.IN_PROGRESS) {
                return TaskEventType.TASK_STARTED
            }
            if (before.postponeCount < after.postponeCount) return TaskEventType.TASK_POSTPONED
            if (before.sessions != after.sessions) {
                return if (after.sessions.any { !it.isLocked }) {
                    TaskEventType.AUTO_SCHEDULED
                } else {
                    TaskEventType.MANUAL_SCHEDULED
                }
            }
            return TaskEventType.TASK_EDITED
        }
    }
}

/** 任务快照 JSON。历史数据不依赖 Room 实体字段顺序，新增字段不会让旧记录失效。 */
object TaskSnapshotJson {
    fun encode(task: StudyTask): String {
        val sessions = JSONArray()
        task.sessions.forEach { session ->
            sessions.put(
                JSONObject()
                    .put("date", session.date.toString())
                    .put("startMinute", session.startMinute)
                    .put("endMinuteExclusive", session.endMinuteExclusive)
                    .put("isLocked", session.isLocked),
            )
        }
        return JSONObject()
            .put("id", task.id)
            .put("title", task.title)
            .put("description", task.description)
            .put("subject", task.subject)
            .put("priority", task.priority.name)
            .put("estimatedMinutes", task.estimatedMinutes)
            .put("dueDate", task.dueDate?.toString() ?: JSONObject.NULL)
            .put("status", task.status.name)
            .put("postponeCount", task.postponeCount)
            .put("sourceType", task.sourceType)
            .put("revision", task.revision)
            .put("sessions", sessions)
            .toString()
    }

    fun decode(json: String?): TaskSnapshot? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val sessions = root.optJSONArray("sessions")
            TaskSnapshot(
                id = root.optLong("id"),
                title = root.optString("title"),
                priority = root.optString("priority"),
                estimatedMinutes = root.optInt("estimatedMinutes"),
                dueDate = root.optString("dueDate").takeIf { it.isNotBlank() && it != "null" },
                status = root.optString("status"),
                postponeCount = root.optInt("postponeCount"),
                sessionCount = sessions?.length() ?: 0,
                sessionMinutes = if (sessions == null) {
                    0
                } else {
                    (0 until sessions.length()).sumOf { index ->
                        val session = sessions.optJSONObject(index) ?: return@sumOf 0
                        session.optInt("endMinuteExclusive") - session.optInt("startMinute")
                    }
                },
            )
        }.getOrNull()
    }
}

data class TaskSnapshot(
    val id: Long,
    val title: String,
    val priority: String,
    val estimatedMinutes: Int,
    val dueDate: String?,
    val status: String,
    val postponeCount: Int,
    val sessionCount: Int,
    val sessionMinutes: Int,
)

/** 便于 UI 展示的事件摘要。 */
data class TaskEventView(
    val event: TaskEvent,
    val timeLabel: String,
    val title: String,
    val detail: String,
    val sourceLabel: String,
)

object TaskHistoryViews {
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun toView(event: TaskEvent, zoneId: ZoneId = ZoneId.systemDefault()): TaskEventView {
        val before = TaskSnapshotJson.decode(event.beforeJson)
        val after = TaskSnapshotJson.decode(event.afterJson)
        val detail = when (event.eventType) {
            TaskEventType.TASK_CREATED -> listOfNotNull(
                after?.priority?.let(::priorityLabel),
                after?.estimatedMinutes?.let { "$it 分钟" },
                after?.dueDate?.let { "截止 $it" },
            ).joinToString(" · ")
            TaskEventType.PRIORITY_CHANGED -> "${priorityLabel(before?.priority)} → ${priorityLabel(after?.priority)}"
            TaskEventType.DUE_DATE_CHANGED -> "${before?.dueDate ?: "无截止日期"} → ${after?.dueDate ?: "无截止日期"}"
            TaskEventType.TASK_POSTPONED -> "${after?.postponeCount ?: 0} 次延期 · 截止 ${after?.dueDate ?: "未设置"}"
            TaskEventType.AUTO_SCHEDULED, TaskEventType.MANUAL_SCHEDULED ->
                "${after?.sessionCount ?: 0} 个学习时段，共 ${after?.sessionMinutes ?: 0} 分钟"
            TaskEventType.TASK_COMPLETED -> "任务标记为完成"
            TaskEventType.TASK_REOPENED -> "任务重新回到未完成"
            TaskEventType.TASK_STARTED -> "开始学习"
            TaskEventType.TASK_PAUSED -> "暂停学习"
            TaskEventType.SESSION_LOCKED -> "锁定一个学习时段"
            TaskEventType.SESSION_UNLOCKED -> "解锁一个学习时段"
            TaskEventType.AI_SUGGESTION_ACCEPTED -> event.reason ?: "用户接受 AI 建议"
            TaskEventType.AI_SUGGESTION_REJECTED -> event.reason ?: "用户拒绝 AI 建议"
            TaskEventType.EXERCISE_FIRST_ATTEMPT -> "第一次独立尝试"
            TaskEventType.EXERCISE_RETRY -> "再次尝试"
            TaskEventType.AI_HINT_USED -> event.reason ?: "使用分级提示"
            TaskEventType.INDEPENDENT_PROOF -> "未使用提示，独立完成"
            TaskEventType.REVIEW_SUCCEEDED -> "复习通过"
            TaskEventType.REVIEW_FAILED -> "复习未通过"
            TaskEventType.VOCABULARY_REVIEWED -> event.reason ?: "词汇复习"
            TaskEventType.SLEEP_SCHEDULE_CHANGED -> event.reason ?: "作息设置变更"
            TaskEventType.SLEEP_RECORDED -> event.reason ?: "记录睡眠"
            TaskEventType.TASK_DELETED, TaskEventType.TASK_CANCELLED -> event.reason ?: "取消或删除任务"
            else -> event.reason ?: ""
        }
        val sourceLabel = when (event.source) {
            HistorySource.USER -> "用户"
            HistorySource.LOCAL_SCHEDULER -> "本地排程"
            HistorySource.AI_SUGGESTION -> "AI 建议"
            HistorySource.SYSTEM -> "系统"
        }
        return TaskEventView(
            event = event,
            timeLabel = LocalDateTime.ofInstant(event.occurredAt, zoneId).format(timeFormatter),
            title = event.eventType.label,
            detail = detail,
            sourceLabel = sourceLabel,
        )
    }

    private fun priorityLabel(priority: String?): String = when (priority) {
        "HIGH" -> "高优先级"
        "MEDIUM" -> "中优先级"
        "LOW" -> "低优先级"
        else -> "未知优先级"
    }
}

/** 任务历史分析；只基于真实事件与当前任务数据，不推断不存在的记录。 */
data class TaskHistoryAnalytics(
    val totalEvents: Int,
    val postponeCount: Int,
    val reopenCount: Int,
    val planModificationCount: Int,
    val estimatedVsActualMinutes: List<EstimateVsActual>,
    val averageDelayDays: Double?,
    val longBacklogTasks: List<Long>,
    val exerciseAttemptSummary: String?,
)

data class EstimateVsActual(
    val taskId: Long,
    val title: String,
    val estimatedMinutes: Int,
    val actualMinutes: Int,
) {
    val differenceMinutes: Int get() = actualMinutes - estimatedMinutes
}

object TaskHistoryAnalyzer {
    /** 统计延期与计划修改频率；没有数据时返回空值而不是伪造 0。 */
    fun analyze(
        events: List<TaskEvent>,
        tasks: List<StudyTask>,
        exerciseAttempts: Map<String, Int> = emptyMap(),
        actualMinutesByTask: Map<Long, Int> = emptyMap(),
    ): TaskHistoryAnalytics {
        val postpones = events.count { it.eventType == TaskEventType.TASK_POSTPONED }
        val reopens = events.count { it.eventType == TaskEventType.TASK_REOPENED }
        val modifications = events.count {
            it.eventType == TaskEventType.AUTO_SCHEDULED ||
                it.eventType == TaskEventType.MANUAL_SCHEDULED ||
                it.eventType == TaskEventType.TASK_POSTPONED
        }
        val comparisons = tasks.mapNotNull { task ->
            val actual = actualMinutesByTask[task.id] ?: return@mapNotNull null
            EstimateVsActual(
                taskId = task.id,
                title = task.title,
                estimatedMinutes = task.estimatedMinutes,
                actualMinutes = actual,
            )
        }
        val delays = events.mapNotNull { event ->
            if (event.eventType != TaskEventType.TASK_POSTPONED) return@mapNotNull null
            val due = TaskSnapshotJson.decode(event.afterJson)?.dueDate ?: return@mapNotNull null
            runCatching { LocalDate.parse(due) }.getOrNull()
        }.zipWithNext { previous, current -> java.time.temporal.ChronoUnit.DAYS.between(previous, current) }
        val backlog = tasks.filter { task ->
            !task.isCompleted && task.postponeCount >= 3
        }.map(StudyTask::id)
        val exerciseSummary = exerciseAttempts.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("；") { (key, count) -> "$key 共 $count 次" }
            .ifBlank { null }
        return TaskHistoryAnalytics(
            totalEvents = events.size,
            postponeCount = postpones,
            reopenCount = reopens,
            planModificationCount = modifications,
            estimatedVsActualMinutes = comparisons,
            averageDelayDays = delays.takeIf { it.isNotEmpty() }?.average(),
            longBacklogTasks = backlog,
            exerciseAttemptSummary = exerciseSummary,
        )
    }
}

interface TaskHistoryRepository {
    fun observeTaskHistory(taskId: Long): Flow<List<TaskEventView>>
    suspend fun getTaskHistory(taskId: Long, limit: Int = 200): List<TaskEvent>
    suspend fun recentEvents(limit: Int = 200): List<TaskEvent>
    suspend fun deleteTaskHistory(taskId: Long)
}

class OfflineTaskHistoryRepository(
    private val dao: HistoryDao,
    private val zoneId: ZoneId,
    private val clock: Clock,
) : TaskHistoryRepository {
    override fun observeTaskHistory(taskId: Long): Flow<List<TaskEventView>> =
        dao.observeForObject(HistoryObjectType.TASK.name, taskId)
            .map { rows -> rows.map { TaskHistoryViews.toView(it.toDomainModel(), zoneId) } }

    override suspend fun getTaskHistory(taskId: Long, limit: Int): List<TaskEvent> =
        dao.eventsForObject(HistoryObjectType.TASK.name, taskId, limit).map { it.toDomainModel() }

    override suspend fun recentEvents(limit: Int): List<TaskEvent> =
        dao.recentEvents(limit).map { it.toDomainModel() }

    override suspend fun deleteTaskHistory(taskId: Long) {
        dao.deleteHistoryForObject(HistoryObjectType.TASK.name, taskId)
    }
}
