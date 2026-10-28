package com.zhixu.studyos.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhixu.studyos.core.model.PRESET_FINANCE_CATEGORIES
import com.zhixu.studyos.core.model.PRESET_SUBJECTS

const val STUDYOS_DATABASE_VERSION = 10

@Database(
    entities = [
        StudyTaskEntity::class,
        TaskSessionEntity::class,
        SemesterEntity::class,
        CourseEntity::class,
        CourseExceptionEntity::class,
        OneOffEventEntity::class,
        RoutineBlockEntity::class,
        SubjectEntity::class,
        TimingSessionEntity::class,
        ProblemEntity::class,
        SelfControlEntryEntity::class,
        LearningGoalsEntity::class,
        FinanceCategoryEntity::class,
        FinanceTransactionEntity::class,
        FinanceMonthlyBudgetEntity::class,
        FinanceCategoryBudgetEntity::class,
        SchedulingSettingsEntity::class,
        SchedulingSubjectPreferenceEntity::class,
        ProblemDocumentEntity::class,
        ProblemDocumentPageEntity::class,
        ProblemImportBatchEntity::class,
        ProblemImportItemEntity::class,
        ProblemBankEntryEntity::class,
        ProblemBankFormulaEntity::class,
        ProblemBankKnowledgePointEntity::class,
        AiUsageDailyEntity::class,
        AiAnalysisCacheEntity::class,
        AiPlanRecordEntity::class,
        TaskEventEntity::class,
        SleepScheduleEntity::class,
        SleepDateOverrideEntity::class,
        SleepRecordEntity::class,
        SleepChangeHistoryEntity::class,
        VocabularyWordEntity::class,
        VocabularyReviewEntity::class,
        VocabularySettingsEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeDependencyEntity::class,
        KnowledgeProblemLinkEntity::class,
        ExamEntity::class,
        LongTermGoalEntity::class,
        GoalMilestoneEntity::class,
        ExerciseAttemptEntity::class,
        ExerciseHintRecordEntity::class,
        ExerciseProfileEntity::class,
        SavedProofEntity::class,
        ExerciseErrorReportEntity::class,
        AbilityEstimateEntity::class,
        AiAnalysisRecordEntity::class,
        WeeklyReportEntity::class,
        SyncOperationEntity::class,
        SyncBaselineEntity::class,
        SyncTombstoneEntity::class,
        SyncStateEntity::class,
        HomeCardPreferenceEntity::class,
        // V3.1 极简生活记录
        DailyTaskEntity::class,
        DailySummaryEntity::class,
        EntertainmentRecordEntity::class,
    ],
    version = STUDYOS_DATABASE_VERSION,
    exportSchema = true,
)
abstract class StudyOSDatabase : RoomDatabase() {
    abstract fun studyTaskDao(): StudyTaskDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun learningDao(): LearningDao
    abstract fun financeDao(): FinanceDao
    abstract fun schedulingDao(): SchedulingDao
    abstract fun problemBankDao(): ProblemBankDao
    abstract fun aiDao(): AiDao
    abstract fun historyDao(): HistoryDao
    abstract fun sleepDao(): SleepDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun examGoalDao(): ExamGoalDao
    abstract fun exerciseLearningDao(): ExerciseLearningDao
    abstract fun v2AiDao(): V2AiDao
    abstract fun syncDao(): SyncDao
    abstract fun homeCardDao(): HomeCardDao
    abstract fun dailyLogDao(): DailyLogDao

    companion object {
        private val presetSeedCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedPresetSubjects(db)
                seedPresetFinanceCategories(db)
                SyncTriggers.install(db)
            }
        }

        internal fun seedPresetSubjects(db: SupportSQLiteDatabase) {
            PRESET_SUBJECTS.forEach { subject ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `subjects` (`id`, `name`, `isPreset`) VALUES (${subject.id}, '${subject.name}', 1)",
                )
            }
        }

        internal fun seedPresetFinanceCategories(db: SupportSQLiteDatabase) {
            PRESET_FINANCE_CATEGORIES.forEach { category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `finance_categories` (`id`, `name`, `isPreset`, `sortOrder`) " +
                        "VALUES (${category.id}, '${category.name}', 1, ${category.sortOrder})",
                )
            }
        }

        fun build(context: Context): StudyOSDatabase = Room.databaseBuilder(
            context.applicationContext,
            StudyOSDatabase::class.java,
            "studyos.db",
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
            .addCallback(presetSeedCallback)
            .build()
    }
}
