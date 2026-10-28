package com.zhixu.studyos.core.reminder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zhixu.studyos.core.finance.BudgetReminderPolicy
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

interface BudgetReminderScheduler {
    fun sync(enabled: Boolean)
}

/**
 * 预算提醒使用每日一次的唯一周期任务，首次检查定在次日或当天 09:00。
 * 关闭开关时取消任务；重新开启后重新排队。系统省电策略可能让检查延迟。
 */
class WorkManagerBudgetReminderScheduler(
    context: Context,
) : BudgetReminderScheduler {
    private val appContext = context.applicationContext

    override fun sync(enabled: Boolean) {
        val workManager = WorkManager.getInstance(appContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = BudgetReminderPolicy.initialDelayMillis(ZonedDateTime.now())
        val request = PeriodicWorkRequestBuilder<BudgetReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "finance_budget_reminder_daily"
    }
}
