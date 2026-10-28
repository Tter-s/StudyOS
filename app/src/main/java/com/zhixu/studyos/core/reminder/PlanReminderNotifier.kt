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
import com.zhixu.studyos.MainActivity
import com.zhixu.studyos.R
import com.zhixu.studyos.core.scheduling.DailyPlanSummary
import com.zhixu.studyos.core.scheduling.DailyPlanSummaryBuilder

/**
 * 每日计划提醒：用户主动开启后，在设定时间附近提醒当天已确认的学习计划。
 * 只读取已保存的计划，不在后台自动生成或覆盖任何时段。
 */
object PlanReminderNotifier {
    private const val CHANNEL_ID = "study_plan_reminder"
    private const val NOTIFICATION_ID = 6001

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(context: Context, summary: DailyPlanSummary): Boolean {
        if (!notificationsAllowed(context)) return false
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = DailyPlanSummaryBuilder.notificationBody(summary)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_plan)
            .setContentTitle("今日学习计划")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "学习计划提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "每天在设定时间提醒当天的学习计划"
        }
        manager.createNotificationChannel(channel)
    }
}
