package com.zhixu.studyos

import android.app.Application
import androidx.work.WorkManager
import com.zhixu.studyos.core.backup.BackupRecovery
import com.zhixu.studyos.core.backup.clearRestoreMarker
import com.zhixu.studyos.core.backup.isRestoreCommitted
import com.zhixu.studyos.di.AppContainer
import com.zhixu.studyos.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 应用入口只持有依赖容器。
 *
 * 所有组件通过 AppContainer 懒加载；V3.1 不再安排学习/预算/考试/睡前提醒，
 * 并会在启动时取消旧版本遗留的 WorkManager 任务。
 */
class StudyOSApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 若上一次恢复中断，先读数据库哨兵判断事务是否已提交，再完成/回滚文件替换。
        // 只有存在 journal 时才打开数据库，正常启动没有额外开销。
        if (BackupRecovery.hasPending(filesDir)) {
            runBlocking(Dispatchers.IO) {
                runCatching {
                    BackupRecovery.recover(
                        filesDir = filesDir,
                        isDatabaseCommitted = { token -> isRestoreCommitted(container.appDatabase, token) },
                        clearDatabaseMarker = { token -> clearRestoreMarker(container.appDatabase, token) },
                    )
                }
            }
        }
        // 低频兜底刷新桌面小组件；系统不会以分钟级轮询读取数据库。
        runCatching { WidgetUpdater.schedulePeriodicRefresh(this) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.budgetReminderScheduler.sync(false) }
            runCatching { container.planReminderScheduler.sync(false, 7, 30) }
            runCatching { container.sleepModeScheduler.cancel() }
            runCatching { WorkManager.getInstance(this@StudyOSApplication).cancelUniqueWork("studyos_exam_reminders") }
        }
    }
}
