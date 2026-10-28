package com.zhixu.studyos.core.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhixu.studyos.StudyOSApplication
import com.zhixu.studyos.core.scheduling.DailyPlanSummaryBuilder
import java.time.LocalDate
import kotlinx.coroutines.flow.first

class PlanReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? StudyOSApplication)?.container
            ?: return Result.success()
        val settings = runCatching { container.schedulingRepository.getSettings() }
            .getOrElse { return Result.retry() }
        if (!settings.planReminderEnabled) return Result.success()

        val tasks = runCatching { container.taskRepository.observeAllTasks().first() }
            .getOrElse { return Result.retry() }
        val schedule = runCatching { container.scheduleRepository.observeSnapshot().first() }
            .getOrElse { return Result.retry() }
        val zoneId = schedule.semester?.zoneId ?: container.clock.zone
        val today = LocalDate.now(container.clock.withZone(zoneId))
        val summary = DailyPlanSummaryBuilder.build(tasks, today)
        PlanReminderNotifier.notify(applicationContext, summary)
        return Result.success()
    }
}
