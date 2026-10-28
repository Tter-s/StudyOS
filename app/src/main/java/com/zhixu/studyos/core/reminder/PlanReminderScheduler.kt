package com.zhixu.studyos.core.reminder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.util.concurrent.TimeUnit

interface PlanReminderScheduler {
    fun sync(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        replaceExisting: Boolean = true,
    )
}

/**
 * 使用 WorkManager 的唯一每日任务。WorkManager 会在重启后恢复任务，
 * 但系统省电策略可能让实际送达时间晚于设定时间；本实现不申请精确闹钟权限。
 */
class WorkManagerPlanReminderScheduler(
    context: Context,
    private val clock: Clock,
) : PlanReminderScheduler {
    private val appContext = context.applicationContext

    override fun sync(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        replaceExisting: Boolean,
    ) {
        val workManager = WorkManager.getInstance(appContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = PlanReminderPolicy.nextDelayMillis(
            now = clock.instant().atZone(clock.zone),
            hour = hour,
            minute = minute,
        )
        val request = PeriodicWorkRequestBuilder<PlanReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "study_plan_reminder_daily"
    }
}
