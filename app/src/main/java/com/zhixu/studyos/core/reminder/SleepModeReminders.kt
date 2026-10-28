package com.zhixu.studyos.core.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhixu.studyos.MainActivity
import com.zhixu.studyos.R
import com.zhixu.studyos.StudyOSApplication
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.schedule.ScheduleEngine
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import com.zhixu.studyos.core.sleep.SleepPhase
import com.zhixu.studyos.core.sleep.formatSleepMinute
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 睡前模式提醒。
 *
 * 到“睡前缓冲开始”时发送一条本地通知，展示明日第一节课与待办任务数量，
 * 提醒结束当前学习会话。它不会、也不能控制系统免打扰模式；用户可以随时关闭。
 * 每次只安排下一次提醒，不进行高频轮询。
 */
interface SleepModeScheduler {
    suspend fun sync()
    fun cancel()
}

class WorkManagerSleepModeScheduler(private val context: Context) : SleepModeScheduler {
    override suspend fun sync() {
        val container = (context.applicationContext as StudyOSApplication).container
        val schedule = runCatching { container.sleepRepository.getSchedule() }.getOrNull() ?: return
        if (!schedule.bedtimeModeEnabled) {
            cancel()
            return
        }
        val overrides = runCatching { container.sleepRepository.getOverrides() }.getOrDefault(emptyMap())
        val now = ZonedDateTime.now()
        val today = now.toLocalDate()

        fun nightStart(date: LocalDate): ZonedDateTime {
            val effective = SleepProtectionEngine.effectiveForNight(date, schedule, overrides)
            val bedDate = if (effective.bedMinute < 12 * 60) date.plusDays(1) else date
            return bedDate.atStartOfDay(now.zone)
                .plusMinutes((effective.bedMinute - schedule.preSleepBufferMinutes).toLong())
        }

        val target = listOf(nightStart(today), nightStart(today.plusDays(1)))
            .firstOrNull { it.isAfter(now) }
            ?: nightStart(today.plusDays(2))
        val delay = Duration.between(now, target).toMillis().coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SleepModeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val UNIQUE_NAME = "studyos_sleep_mode"
    }
}

class SleepModeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StudyOSApplication).container
        val schedule = runCatching { container.sleepRepository.getSchedule() }.getOrNull()
        if (schedule == null || !schedule.bedtimeModeEnabled) return Result.success()
        val now = ZonedDateTime.now()
        val today = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute
        val overrides = runCatching { container.sleepRepository.getOverrides() }.getOrDefault(emptyMap())
        // 系统可能延迟执行；只在睡前缓冲开始后 15 分钟内提醒，避免凌晨骚扰。
        // 跨午夜/凌晨入睡的作息也要按绝对时间判断。
        val nearBed = listOf(today.minusDays(1), today, today.plusDays(1)).any { date ->
            val effective = SleepProtectionEngine.effectiveForNight(date, schedule, overrides)
            val bedDate = if (effective.bedMinute < 12 * 60) date.plusDays(1) else date
            val start = bedDate.atStartOfDay(now.zone)
                .plusMinutes((effective.bedMinute - schedule.preSleepBufferMinutes).toLong())
            val delta = Duration.between(start, now).toMinutes()
            delta in 0..15
        }
        val inWindow = nearBed
        if (inWindow && notificationsAllowed(applicationContext)) {
            val beforeMidnight = nowMinute >= 6 * 60
            val day = if (beforeMidnight) today.plusDays(1) else today
            val dayLabel = if (beforeMidnight) "明日" else "今日"
            val scheduleSnapshot = runCatching {
                container.scheduleRepository.observeSnapshot().first()
            }.getOrNull()
            val firstCourse = scheduleSnapshot?.let { snap ->
                val zone = snap.semester?.zoneId ?: java.time.ZoneId.systemDefault()
                ScheduleEngine.daySchedule(snap, day, zone).items
                    .firstOrNull { it.instance.type == ScheduleItemType.COURSE }
                    ?.let {
                        "${it.instance.title} ${formatSleepMinute(it.segmentStartMinute)}"
                    }
            }
            val taskCount = runCatching {
                container.taskRepository.observeTasks(day.toEpochDay()).first().count { !it.isCompleted }
            }.getOrDefault(0)
            SleepModeNotifier.notify(applicationContext, firstCourse, taskCount, dayLabel)
        }
        // 无论是否发送，都安排下一次；关闭睡前模式时 sync 会取消。
        WorkManagerSleepModeScheduler(applicationContext).sync()
        return Result.success()
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

object SleepModeNotifier {
    private const val CHANNEL_ID = "sleep_mode"
    private const val NOTIFICATION_ID = 3002

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(context: Context, firstCourse: String?, tomorrowTaskCount: Int, dayLabel: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DESTINATION, "home")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = buildString {
            append("建议结束当前学习会话，准备休息。")
            append(if (firstCourse.isNullOrBlank()) "${dayLabel}暂无课程。" else "${dayLabel}第一节课：$firstCourse。")
            append("${dayLabel}还有 $tomorrowTaskCount 项未完成任务，可以留到之后再处理。")
            append("此提醒仅由知序 StudyOS 发送，不会修改系统免打扰设置。")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_plan)
            .setContentTitle("睡前缓冲提醒")
            .setContentText("建议结束学习会话，准备休息")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "睡前模式",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "睡前缓冲提醒；不会修改系统免打扰"
            },
        )
    }
}
