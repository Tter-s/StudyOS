package com.zhixu.studyos.core.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhixu.studyos.StudyOSApplication
import com.zhixu.studyos.core.finance.BudgetReminderPolicy
import com.zhixu.studyos.core.finance.FinanceCalculator
import com.zhixu.studyos.core.model.BudgetReminderLevel
import java.time.YearMonth
import kotlinx.coroutines.flow.first

class BudgetReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? StudyOSApplication)?.container
            ?: return Result.success()
        val enabled = runCatching {
            container.userPreferencesRepository.budgetReminderEnabled.first()
        }.getOrDefault(false)
        if (!enabled) return Result.success()

        val snapshot = runCatching { container.financeRepository.getSnapshot() }
            .getOrElse { return Result.retry() }
        val now = container.clock.instant()
        val today = now.atZone(container.clock.zone).toLocalDate()
        val month = YearMonth.from(today)
        val summary = FinanceCalculator.monthSummary(snapshot, month, today)
        val level = BudgetReminderPolicy.levelFor(summary)
        if (level == BudgetReminderLevel.NONE) return Result.success()
        val state = runCatching {
            container.userPreferencesRepository.getBudgetReminderState()
        }.getOrElse { return Result.retry() }
        if (!BudgetReminderPolicy.shouldNotify(level, month, state)) return Result.success()

        val notified = BudgetNotifier.notify(applicationContext, summary, level)
        if (notified) {
            runCatching {
                container.userPreferencesRepository.recordBudgetReminder(month, level)
            }
        }
        return Result.success()
    }
}
