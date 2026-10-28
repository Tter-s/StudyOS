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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zhixu.studyos.MainActivity
import com.zhixu.studyos.R
import com.zhixu.studyos.StudyOSApplication
import com.zhixu.studyos.core.exam.ExamCountdownCalculator
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 考试提醒：每日一次的本地 WorkManager 检查，只读取考试表和通知权限，不联网。
 *
 * 用户为每场考试设置是否提醒与提前天数；同一天最多提醒一次。
 * 不申请精确闹钟权限，实际送达可能受系统省电策略影响。
 */
object ExamReminderScheduler {
    private const val UNIQUE_NAME = "studyos_exam_reminders"
    private const val CHECK_HOUR = 9

    fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(CHECK_HOUR, 0)).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = java.time.Duration.between(now, next)
        val request = PeriodicWorkRequestBuilder<ExamReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}

class ExamReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StudyOSApplication).container
        val today = LocalDate.now()
        val exams = runCatching { container.examGoalRepository.getSnapshot().exams }.getOrDefault(emptyList())
        val due = ExamCountdownCalculator.build(exams, today)
            .filter {
                it.phase != com.zhixu.studyos.core.exam.ExamPhase.PASSED &&
                    it.exam.reminderEnabled &&
                    it.daysRemaining <= it.exam.reminderDaysBefore &&
                    it.daysRemaining >= 0
            }
        if (due.isEmpty()) return Result.success()
        val prefs = applicationContext.getSharedPreferences("exam_reminders", Context.MODE_PRIVATE)
        val lastDate = prefs.getString("last_notified_date", null)
        if (lastDate == today.toString()) return Result.success()
        if (!notificationsAllowed(applicationContext)) return Result.success()
        ExamNotifier.notify(applicationContext, due)
        prefs.edit().putString("last_notified_date", today.toString()).apply()
        return Result.success()
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

object ExamNotifier {
    private const val CHANNEL_ID = "exam_reminders"
    private const val NOTIFICATION_ID = 3001

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(context: Context, countdowns: List<com.zhixu.studyos.core.exam.ExamCountdown>) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val summary = countdowns.take(3).joinToString("；") {
            "${it.exam.name} ${it.label}"
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_DESTINATION, "study")
            putExtra(MainActivity.EXTRA_SECTION, "EXAM_GOAL")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_plan)
            .setContentTitle("考试临近")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
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
                "考试提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "考试前的本地倒计时提醒，每天最多一次"
            },
        )
    }
}
