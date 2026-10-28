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
import com.zhixu.studyos.core.finance.BudgetReminderPolicy
import com.zhixu.studyos.core.model.BudgetReminderLevel
import com.zhixu.studyos.core.model.MonthFinanceSummary

/**
 * 低干扰的预算通知：低重要性、无声音、无徽标，每月最多两次。
 * 通知权限未授权或系统关闭通知时返回 false，不记为已提醒。
 */
object BudgetNotifier {
    private const val CHANNEL_ID = "finance_budget_reminder"
    private const val NOTIFICATION_ID = 5001

    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(
        context: Context,
        summary: MonthFinanceSummary,
        level: BudgetReminderLevel,
    ): Boolean {
        val (title, body) = BudgetReminderPolicy.notificationContent(summary, level) ?: return false
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
            "预算提醒",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "本月预算接近或超过上限时提醒，每月最多两次"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
