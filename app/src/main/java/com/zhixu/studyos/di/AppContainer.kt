package com.zhixu.studyos.di

import android.content.Context
import com.zhixu.studyos.BuildConfig
import com.zhixu.studyos.core.ai.AiConnectivity
import com.zhixu.studyos.core.ai.AiPlanningService
import com.zhixu.studyos.core.ai.AiSnapshotBuilder
import com.zhixu.studyos.core.ai.AiTaskService
import com.zhixu.studyos.core.ai.AiStudyDataSource
import com.zhixu.studyos.core.ai.LocalAiStudyDataSource
import com.zhixu.studyos.core.ai.AndroidAiConnectivity
import com.zhixu.studyos.core.ai.network.AiHttpTransport
import com.zhixu.studyos.core.ai.network.UrlConnectionAiHttpTransport
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.backup.AppBackupService
import com.zhixu.studyos.core.backup.BackupService
import com.zhixu.studyos.core.backup.DataExportService
import com.zhixu.studyos.core.backup.OfflineDataExportService
import com.zhixu.studyos.core.ai.security.KeystoreApiKeyStore
import com.zhixu.studyos.core.bank.DefaultExercisePageSourceFactory
import com.zhixu.studyos.core.bank.ExerciseImportPipeline
import com.zhixu.studyos.core.bank.ExerciseOcrRecognizer
import com.zhixu.studyos.core.bank.ExercisePageSourceFactory
import com.zhixu.studyos.core.bank.ExerciseProblemParser
import com.zhixu.studyos.core.bank.HeuristicProblemAnalyzer
import com.zhixu.studyos.core.bank.ProblemDocumentStore
import com.zhixu.studyos.core.bank.ProblemPageRenderer
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.export.ContentResolverAppFileStore
import com.zhixu.studyos.core.data.export.ContentResolverFinanceDocumentStore
import com.zhixu.studyos.core.data.export.FinanceDocumentStore
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.preferences.AiSettingsRepository
import com.zhixu.studyos.core.data.preferences.UserPreferencesRepository
import com.zhixu.studyos.core.data.repository.AiRepository
import com.zhixu.studyos.core.data.repository.DailyLogRepository
import com.zhixu.studyos.core.data.repository.FinanceRepository
import com.zhixu.studyos.core.data.repository.HomeRepository
import com.zhixu.studyos.core.data.repository.LearningRepository
import com.zhixu.studyos.core.data.repository.OfflineAiRepository
import com.zhixu.studyos.core.data.repository.OfflineDailyLogRepository
import com.zhixu.studyos.core.data.repository.OfflineFinanceRepository
import com.zhixu.studyos.core.data.repository.OfflineLearningRepository
import com.zhixu.studyos.core.data.repository.OfflineProblemBankRepository
import com.zhixu.studyos.core.data.repository.OfflineScheduleRepository
import com.zhixu.studyos.core.data.repository.OfflineSchedulingRepository
import com.zhixu.studyos.core.data.repository.OfflineTaskRepository
import com.zhixu.studyos.core.data.repository.ProblemBankRepository
import com.zhixu.studyos.core.data.repository.ScheduleRepository
import com.zhixu.studyos.core.data.repository.SchedulingRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.history.OfflineTaskHistoryRepository
import com.zhixu.studyos.core.history.TaskHistoryRepository
import com.zhixu.studyos.core.data.repository.SleepRepository
import com.zhixu.studyos.core.data.repository.OfflineSleepRepository
import com.zhixu.studyos.core.data.repository.VocabularyRepository
import com.zhixu.studyos.core.data.repository.OfflineVocabularyRepository
import com.zhixu.studyos.core.data.repository.KnowledgeRepository
import com.zhixu.studyos.core.data.repository.OfflineKnowledgeRepository
import com.zhixu.studyos.core.data.repository.ExerciseRepository
import com.zhixu.studyos.core.data.repository.OfflineExerciseRepository
import com.zhixu.studyos.core.data.repository.MathHintRepository
import com.zhixu.studyos.core.data.repository.OfflineMathHintRepository
import com.zhixu.studyos.core.data.repository.ExamGoalRepository
import com.zhixu.studyos.core.data.repository.OfflineExamGoalRepository
import com.zhixu.studyos.core.data.repository.ReportRepository
import com.zhixu.studyos.core.data.repository.OfflineReportRepository
import com.zhixu.studyos.core.data.repository.HomeCardRepository
import com.zhixu.studyos.core.data.repository.OfflineHomeCardRepository
import com.zhixu.studyos.core.ownership.AppSecureBackupService
import com.zhixu.studyos.core.ownership.DataOwnershipService
import com.zhixu.studyos.core.ownership.OfflineDataOwnershipService
import com.zhixu.studyos.core.ownership.SecureBackupService
import com.zhixu.studyos.core.sync.FileSyncWatermarkStore
import com.zhixu.studyos.core.sync.LocalSyncService
import com.zhixu.studyos.core.importer.ScheduleImportService
import com.zhixu.studyos.core.importer.ocr.MlKitScheduleImportService
import com.zhixu.studyos.core.model.ProblemAnalysisEngine
import com.zhixu.studyos.core.reminder.BudgetReminderScheduler
import com.zhixu.studyos.core.reminder.PlanReminderScheduler
import com.zhixu.studyos.core.reminder.WorkManagerPlanReminderScheduler
import com.zhixu.studyos.core.reminder.WorkManagerBudgetReminderScheduler
import com.zhixu.studyos.core.reminder.SleepModeScheduler
import com.zhixu.studyos.core.reminder.WorkManagerSleepModeScheduler
import java.time.Clock
import java.time.ZoneId

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: StudyOSDatabase by lazy {
        StudyOSDatabase.build(appContext)
    }

    /** 供桌面小组件读取本地数据；不暴露写操作。 */
    val appDatabase: StudyOSDatabase get() = database

    val taskRepository: TaskRepository by lazy {
        OfflineTaskRepository(database, database.studyTaskDao(), clock)
    }

    /** V3.1 极简记录：每日待办、总结与娱乐时间。 */
    val dailyLogRepository: DailyLogRepository by lazy {
        OfflineDailyLogRepository(database.dailyLogDao(), clock)
    }

    val homeRepository: HomeRepository get() = taskRepository

    val scheduleRepository: ScheduleRepository by lazy {
        OfflineScheduleRepository(database.scheduleDao(), clock, database)
    }

    val learningRepository: LearningRepository by lazy {
        OfflineLearningRepository(database, database.learningDao(), clock)
    }

    val financeRepository: FinanceRepository by lazy {
        OfflineFinanceRepository(database, database.financeDao())
    }

    val financeDocumentStore: FinanceDocumentStore by lazy {
        ContentResolverFinanceDocumentStore(appContext)
    }

    val budgetReminderScheduler: BudgetReminderScheduler by lazy {
        WorkManagerBudgetReminderScheduler(appContext)
    }

    val scheduleImportService: ScheduleImportService by lazy {
        MlKitScheduleImportService()
    }

    val schedulingRepository: SchedulingRepository by lazy {
        OfflineSchedulingRepository(database, database.schedulingDao(), database.studyTaskDao())
    }

    val planReminderScheduler: PlanReminderScheduler by lazy {
        WorkManagerPlanReminderScheduler(appContext, clock)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext)
    }

    // ---------- V0.8 个人题库 ----------

    val problemDocumentStore: ProblemDocumentStore by lazy {
        ProblemDocumentStore(appContext)
    }

    val exercisePageSourceFactory: ExercisePageSourceFactory by lazy {
        DefaultExercisePageSourceFactory(problemDocumentStore)
    }

    /** V0.8 只启用本地规则分析；AI 引擎在 V0.9 通过 ProblemAnalysisEngine 接口接入。 */
    val problemAnalysisEngine: ProblemAnalysisEngine by lazy {
        HeuristicProblemAnalyzer()
    }

    val problemBankRepository: ProblemBankRepository by lazy {
        OfflineProblemBankRepository(database, database.problemBankDao(), clock)
    }

    val exerciseImportPipeline: ExerciseImportPipeline by lazy {
        ExerciseImportPipeline(
            dao = database.problemBankDao(),
            sourceFactory = exercisePageSourceFactory,
            parser = ExerciseProblemParser(analysisEngine = problemAnalysisEngine),
            ocrRecognizer = ExerciseOcrRecognizer(),
            clock = clock,
        )
    }

    val problemPageRenderer: ProblemPageRenderer by lazy {
        ProblemPageRenderer(exercisePageSourceFactory)
    }

    // ---------- V0.9 可选 AI 规划 ----------

    val aiSettingsRepository: AiSettingsRepository by lazy {
        AiSettingsRepository(appContext)
    }

    val apiKeyStore: ApiKeyStore by lazy {
        KeystoreApiKeyStore(appContext)
    }

    val aiRepository: AiRepository by lazy {
        OfflineAiRepository(database, database.aiDao(), clock)
    }

    val aiConnectivity: AiConnectivity by lazy {
        AndroidAiConnectivity(appContext)
    }

    val aiHttpTransport: AiHttpTransport by lazy {
        UrlConnectionAiHttpTransport()
    }

    val aiSnapshotBuilder: AiSnapshotBuilder by lazy {
        AiSnapshotBuilder(clock, ZoneId.systemDefault())
    }

    val aiStudyDataSource: AiStudyDataSource by lazy {
        LocalAiStudyDataSource(
            taskRepository = taskRepository,
            learningRepository = learningRepository,
            problemBankRepository = problemBankRepository,
            schedulingRepository = schedulingRepository,
            snapshotBuilder = aiSnapshotBuilder,
        )
    }

    val aiPlanningService: AiPlanningService by lazy {
        AiPlanningService(
            repository = aiRepository,
            keyStore = apiKeyStore,
            connectivity = aiConnectivity,
            transport = aiHttpTransport,
            snapshotBuilder = aiSnapshotBuilder,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
        )
    }

    // ---------- V1.0 完整备份、恢复与数据导出 ----------

    val appFileStore: AppFileStore by lazy {
        ContentResolverAppFileStore(appContext)
    }

    val backupService: BackupService by lazy {
        AppBackupService(
            database = database,
            filesDir = appContext.filesDir,
            fileStore = appFileStore,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
            appVersion = appVersion,
        )
    }

    val dataExportService: DataExportService by lazy {
        OfflineDataExportService(
            taskRepository = taskRepository,
            learningRepository = learningRepository,
            financeRepository = financeRepository,
            fileStore = appFileStore,
        )
    }

    // ---------- V2.0 历史、睡眠、词汇、知识图谱、考试目标、习题能力 ----------

    val historyRepository: TaskHistoryRepository by lazy {
        OfflineTaskHistoryRepository(
            dao = database.historyDao(),
            zoneId = ZoneId.systemDefault(),
            clock = clock,
        )
    }

    val sleepRepository: SleepRepository by lazy {
        OfflineSleepRepository(
            database = database,
            sleepDao = database.sleepDao(),
            scheduleDao = database.scheduleDao(),
            historyDao = database.historyDao(),
            clock = clock,
        )
    }

    val vocabularyRepository: VocabularyRepository by lazy {
        OfflineVocabularyRepository(
            database = database,
            dao = database.vocabularyDao(),
            taskRepository = taskRepository,
            historyDao = database.historyDao(),
            clock = clock,
            zoneId = ZoneId.systemDefault(),
        )
    }

    val knowledgeRepository: KnowledgeRepository by lazy {
        OfflineKnowledgeRepository(
            database = database,
            dao = database.knowledgeDao(),
            problemBankDao = database.problemBankDao(),
            historyDao = database.historyDao(),
            clock = clock,
        )
    }

    val examGoalRepository: ExamGoalRepository by lazy {
        OfflineExamGoalRepository(
            database = database,
            dao = database.examGoalDao(),
            exerciseLearningDao = database.exerciseLearningDao(),
            problemBankDao = database.problemBankDao(),
            taskRepository = taskRepository,
            historyDao = database.historyDao(),
            clock = clock,
        )
    }

    // ---------- V2.0 统一 AI 服务 ----------

    val aiTaskService: AiTaskService by lazy {
        AiTaskService(
            repository = aiRepository,
            configStore = aiSettingsRepository,
            keyStore = apiKeyStore,
            connectivity = aiConnectivity,
            transport = aiHttpTransport,
            aiDao = database.v2AiDao(),
            clock = clock,
            zoneId = ZoneId.systemDefault(),
        )
    }

    val exerciseRepository: ExerciseRepository by lazy {
        OfflineExerciseRepository(
            database = database,
            exerciseDao = database.exerciseLearningDao(),
            problemBankDao = database.problemBankDao(),
            problemBankRepository = problemBankRepository,
            learningDao = database.learningDao(),
            knowledgeRepository = knowledgeRepository,
            historyDao = database.historyDao(),
            clock = clock,
        )
    }

    val mathHintRepository: MathHintRepository by lazy {
        OfflineMathHintRepository(
            database = database,
            exerciseDao = database.exerciseLearningDao(),
            problemBankRepository = problemBankRepository,
            learningDao = database.learningDao(),
            historyDao = database.historyDao(),
            aiTaskService = aiTaskService,
            configStore = aiSettingsRepository,
            clock = clock,
        )
    }

    val reportRepository: ReportRepository by lazy {
        OfflineReportRepository(
            database = database,
            taskDao = database.studyTaskDao(),
            learningDao = database.learningDao(),
            exerciseDao = database.exerciseLearningDao(),
            vocabularyDao = database.vocabularyDao(),
            sleepDao = database.sleepDao(),
            examGoalDao = database.examGoalDao(),
            financeDao = database.financeDao(),
            problemBankDao = database.problemBankDao(),
            aiDao = database.v2AiDao(),
            aiTaskService = aiTaskService,
            fileStore = appFileStore,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
        )
    }

    val homeCardRepository: HomeCardRepository by lazy {
        OfflineHomeCardRepository(database.homeCardDao())
    }

    // ---------- V2.0 加密备份、数据所有权与本地同步 ----------

    val secureBackupService: SecureBackupService by lazy {
        AppSecureBackupService(
            database = database,
            filesDir = appContext.filesDir,
            fileStore = appFileStore,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
            appVersion = appVersion,
        )
    }

    val dataOwnershipService: DataOwnershipService by lazy {
        OfflineDataOwnershipService(
            database = database,
            filesDir = appContext.filesDir,
            fileStore = appFileStore,
            apiKeyStore = apiKeyStore,
            aiConfigStore = aiSettingsRepository,
            userPreferencesRepository = userPreferencesRepository,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
            appVersion = appVersion,
        )
    }

    val sleepModeScheduler: SleepModeScheduler by lazy {
        WorkManagerSleepModeScheduler(appContext)
    }

    val syncService: LocalSyncService by lazy {
        LocalSyncService(
            database = database,
            dao = database.syncDao(),
            fileStore = appFileStore,
            clock = clock,
            zoneId = ZoneId.systemDefault(),
            watermarks = FileSyncWatermarkStore(appContext.filesDir),
        )
    }

    val appVersion: String = BuildConfig.VERSION_NAME

    val clock: Clock = Clock.systemDefaultZone()
}
