package com.zhixu.studyos.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(event: TaskEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<TaskEventEntity>)

    @Query("SELECT * FROM task_events WHERE objectType = :objectType AND objectId = :objectId ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeForObject(objectType: String, objectId: Long): Flow<List<TaskEventEntity>>

    @Query("SELECT * FROM task_events WHERE objectType = :objectType AND objectId = :objectId ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun eventsForObject(objectType: String, objectId: Long, limit: Int = 200): List<TaskEventEntity>

    @Query("SELECT * FROM task_events WHERE occurredAtEpochMillis BETWEEN :startMillis AND :endMillis ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun eventsBetween(startMillis: Long, endMillis: Long, limit: Int = 500): List<TaskEventEntity>

    @Query("SELECT COUNT(*) FROM task_events WHERE eventType = :eventType AND occurredAtEpochMillis BETWEEN :startMillis AND :endMillis")
    suspend fun countEvents(eventType: String, startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM task_events ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<TaskEventEntity>

    @Query("SELECT MIN(occurredAtEpochMillis) FROM task_events")
    suspend fun earliestEventMillis(): Long?

    @Query("DELETE FROM task_events WHERE objectType = :objectType AND objectId = :objectId AND id = :eventId")
    suspend fun deleteEvent(objectType: String, objectId: Long, eventId: Long)

    @Query("DELETE FROM task_events WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun deleteHistoryForObject(objectType: String, objectId: Long)

    @Query("DELETE FROM task_events")
    suspend fun deleteAll()
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_schedule WHERE id = 1")
    fun observeSchedule(): Flow<SleepScheduleEntity?>

    @Query("SELECT * FROM sleep_schedule WHERE id = 1")
    suspend fun getSchedule(): SleepScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(entity: SleepScheduleEntity)

    @Query("SELECT * FROM sleep_date_overrides ORDER BY dateEpochDay")
    fun observeOverrides(): Flow<List<SleepDateOverrideEntity>>

    @Query("SELECT * FROM sleep_date_overrides ORDER BY dateEpochDay")
    suspend fun getOverrides(): List<SleepDateOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(entity: SleepDateOverrideEntity)

    @Query("DELETE FROM sleep_date_overrides WHERE dateEpochDay = :dateEpochDay")
    suspend fun deleteOverride(dateEpochDay: Long)

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay DESC LIMIT :limit")
    fun observeRecords(limit: Int = 90): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_records WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY dateEpochDay")
    suspend fun recordsBetween(startEpochDay: Long, endEpochDay: Long): List<SleepRecordEntity>

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun recentRecords(limit: Int): List<SleepRecordEntity>

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay")
    suspend fun allRecords(): List<SleepRecordEntity>

    @Query("SELECT * FROM sleep_records WHERE dateEpochDay = :dateEpochDay")
    suspend fun getRecord(dateEpochDay: Long): SleepRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(entity: SleepRecordEntity)

    @Query("DELETE FROM sleep_records WHERE dateEpochDay = :dateEpochDay")
    suspend fun deleteRecord(dateEpochDay: Long)

    @Insert
    suspend fun insertChangeHistory(entity: SleepChangeHistoryEntity)

    @Query("SELECT * FROM sleep_change_history ORDER BY changedAtEpochMillis DESC LIMIT :limit")
    fun observeChangeHistory(limit: Int = 50): Flow<List<SleepChangeHistoryEntity>>
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary_words ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<VocabularyWordEntity>>

    @Query("SELECT * FROM vocabulary_words ORDER BY word COLLATE NOCASE")
    suspend fun allWords(): List<VocabularyWordEntity>

    @Query("SELECT * FROM vocabulary_reviews ORDER BY reviewedAtEpochMillis")
    suspend fun allReviews(): List<VocabularyReviewEntity>

    @Query("SELECT COUNT(*) FROM vocabulary_words")
    fun observeWordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status != 'SUSPENDED' AND dueDateEpochDay <= :epochDay")
    fun observeDueCount(epochDay: Long): Flow<Int>

    @Query(
        "SELECT * FROM vocabulary_words WHERE status != 'SUSPENDED' AND dueDateEpochDay <= :epochDay " +
            "ORDER BY CASE status WHEN 'NEW' THEN 1 ELSE 0 END, dueDateEpochDay, id LIMIT :limit",
    )
    suspend fun dueWords(epochDay: Long, limit: Int): List<VocabularyWordEntity>

    @Query(
        "SELECT * FROM vocabulary_words WHERE status = 'NEW' AND dueDateEpochDay <= :epochDay " +
            "ORDER BY dueDateEpochDay, id LIMIT :limit",
    )
    suspend fun newWords(epochDay: Long, limit: Int): List<VocabularyWordEntity>

    @Query(
        "SELECT * FROM vocabulary_words WHERE status NOT IN ('NEW','SUSPENDED') AND dueDateEpochDay <= :epochDay " +
            "ORDER BY dueDateEpochDay, id LIMIT :limit",
    )
    suspend fun reviewWords(epochDay: Long, limit: Int): List<VocabularyWordEntity>

    @Query(
        "SELECT * FROM vocabulary_words WHERE (:listName = '' OR listName = :listName) " +
            "AND (:search = '' OR word LIKE '%' || :search || '%' OR meaning LIKE '%' || :search || '%') " +
            "ORDER BY word COLLATE NOCASE LIMIT :limit OFFSET :offset",
    )
    suspend fun pageWords(listName: String, search: String, limit: Int, offset: Int): List<VocabularyWordEntity>

    @Query(
        "SELECT COUNT(*) FROM vocabulary_words WHERE (:listName = '' OR listName = :listName) " +
            "AND (:search = '' OR word LIKE '%' || :search || '%' OR meaning LIKE '%' || :search || '%')",
    )
    suspend fun countWords(listName: String, search: String): Int

    /** 今天第一次进入复习的词数；用于扣减每日新词额度。 */
    @Query(
        "SELECT COUNT(*) FROM (" +
            "SELECT wordId, MIN(reviewedAtEpochMillis) AS firstReviewed " +
            "FROM vocabulary_reviews GROUP BY wordId" +
            ") AS first_reviews WHERE firstReviewed >= :startMillis AND firstReviewed < :endMillis",
    )
    suspend fun countFirstReviewsBetween(startMillis: Long, endMillis: Long): Int

    /** 今天至少复习过一次的不同词数；用于扣减每日复习额度。 */
    @Query(
        "SELECT COUNT(DISTINCT wordId) FROM vocabulary_reviews " +
            "WHERE reviewedAtEpochMillis >= :startMillis AND reviewedAtEpochMillis < :endMillis",
    )
    suspend fun countReviewedWordsBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM vocabulary_words WHERE id = :id")
    suspend fun getWord(id: Long): VocabularyWordEntity?

    @Query("SELECT * FROM vocabulary_words WHERE normalizedWord = :normalized")
    suspend fun findWord(normalized: String): VocabularyWordEntity?

    @Query("SELECT DISTINCT listName FROM vocabulary_words ORDER BY listName")
    suspend fun listNames(): List<String>

    @Insert
    suspend fun insertWord(entity: VocabularyWordEntity): Long

    @Update
    suspend fun updateWord(entity: VocabularyWordEntity)

    @Query("DELETE FROM vocabulary_words WHERE id = :id")
    suspend fun deleteWord(id: Long)

    @Query("SELECT * FROM vocabulary_reviews WHERE wordId = :wordId ORDER BY reviewedAtEpochMillis DESC LIMIT :limit")
    suspend fun reviewsForWord(wordId: Long, limit: Int = 50): List<VocabularyReviewEntity>

    @Query("SELECT * FROM vocabulary_reviews WHERE reviewedAtEpochMillis BETWEEN :startMillis AND :endMillis ORDER BY reviewedAtEpochMillis")
    suspend fun reviewsBetween(startMillis: Long, endMillis: Long): List<VocabularyReviewEntity>

    @Query("SELECT * FROM vocabulary_reviews ORDER BY reviewedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentReviews(limit: Int): List<VocabularyReviewEntity>

    @Insert
    suspend fun insertReview(entity: VocabularyReviewEntity): Long

    @Query("SELECT * FROM vocabulary_settings WHERE id = 1")
    fun observeSettings(): Flow<VocabularySettingsEntity?>

    @Query("SELECT * FROM vocabulary_settings WHERE id = 1")
    suspend fun getSettings(): VocabularySettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: VocabularySettingsEntity)

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status = 'MASTERED'")
    suspend fun masteredCount(): Int

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status = 'NEW'")
    suspend fun countNewWords(): Int

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status IN ('LEARNING','REVIEWING')")
    suspend fun countLearningWords(): Int

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status != 'SUSPENDED' AND dueDateEpochDay <= :epochDay")
    suspend fun countDue(epochDay: Long): Int

    @Query("SELECT COUNT(*) FROM vocabulary_words WHERE status != 'SUSPENDED' AND status != 'NEW' AND dueDateEpochDay <= :epochDay")
    suspend fun countDueReview(epochDay: Long): Int

    @Query("SELECT COUNT(*) FROM vocabulary_words")
    suspend fun countAllWords(): Int

    @Query("SELECT COUNT(*) FROM vocabulary_reviews WHERE reviewedAtEpochMillis >= :sinceMillis")
    suspend fun reviewCountSince(sinceMillis: Long): Int
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_nodes ORDER BY subject, chapter, name")
    fun observeNodes(): Flow<List<KnowledgeNodeEntity>>

    @Query("SELECT * FROM knowledge_nodes ORDER BY subject, chapter, name")
    suspend fun getNodes(): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE id = :id")
    suspend fun getNode(id: Long): KnowledgeNodeEntity?

    @Query("SELECT * FROM knowledge_nodes WHERE subject = :subject AND name = :name")
    suspend fun findNode(subject: String, name: String): KnowledgeNodeEntity?

    @Insert
    suspend fun insertNode(entity: KnowledgeNodeEntity): Long

    @Update
    suspend fun updateNode(entity: KnowledgeNodeEntity)

    @Query("DELETE FROM knowledge_nodes WHERE id = :id")
    suspend fun deleteNode(id: Long)

    @Query("SELECT * FROM knowledge_dependencies ORDER BY fromNodeId, toNodeId")
    fun observeDependencies(): Flow<List<KnowledgeDependencyEntity>>

    @Query("SELECT * FROM knowledge_dependencies")
    suspend fun getDependencies(): List<KnowledgeDependencyEntity>

    @Query("SELECT * FROM knowledge_dependencies WHERE id = :id")
    suspend fun getDependency(id: Long): KnowledgeDependencyEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependency(entity: KnowledgeDependencyEntity): Long

    @Query("DELETE FROM knowledge_dependencies WHERE id = :id")
    suspend fun deleteDependency(id: Long)

    @Query("DELETE FROM knowledge_dependencies WHERE fromNodeId = :fromNodeId AND toNodeId = :toNodeId")
    suspend fun deleteDependencyBetween(fromNodeId: Long, toNodeId: Long)

    @Query("SELECT * FROM knowledge_problem_links WHERE nodeId = :nodeId")
    suspend fun linksForNode(nodeId: Long): List<KnowledgeProblemLinkEntity>

    @Query("SELECT * FROM knowledge_problem_links WHERE problemId = :problemId")
    suspend fun linksForProblem(problemId: Long): List<KnowledgeProblemLinkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProblemLink(entity: KnowledgeProblemLinkEntity): Long

    @Query("DELETE FROM knowledge_problem_links WHERE nodeId = :nodeId AND problemId = :problemId")
    suspend fun deleteProblemLink(nodeId: Long, problemId: Long)

    @Query("SELECT COUNT(*) FROM knowledge_nodes")
    suspend fun nodeCount(): Int
}

@Dao
interface ExamGoalDao {
    @Query("SELECT * FROM exams ORDER BY dateEpochDay")
    fun observeExams(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams ORDER BY dateEpochDay")
    suspend fun getExams(): List<ExamEntity>

    @Query("SELECT * FROM exams WHERE id = :id")
    suspend fun getExam(id: Long): ExamEntity?

    @Insert
    suspend fun insertExam(entity: ExamEntity): Long

    @Update
    suspend fun updateExam(entity: ExamEntity)

    @Query("DELETE FROM exams WHERE id = :id")
    suspend fun deleteExam(id: Long)

    @Query("SELECT * FROM long_term_goals ORDER BY status, targetDateEpochDay, id")
    fun observeGoals(): Flow<List<LongTermGoalEntity>>

    @Query("SELECT * FROM long_term_goals ORDER BY status, targetDateEpochDay, id")
    suspend fun getGoals(): List<LongTermGoalEntity>

    @Query("SELECT * FROM long_term_goals WHERE id = :id")
    suspend fun getGoal(id: Long): LongTermGoalEntity?

    @Insert
    suspend fun insertGoal(entity: LongTermGoalEntity): Long

    @Update
    suspend fun updateGoal(entity: LongTermGoalEntity)

    @Query("DELETE FROM long_term_goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    @Query("UPDATE long_term_goals SET updatedAtEpochMillis = :updatedAt, revision = revision + 1 WHERE id = :id")
    suspend fun touchGoal(id: Long, updatedAt: Long)

    @Query("SELECT * FROM goal_milestones ORDER BY goalId, sortOrder, id")
    fun observeMilestones(): Flow<List<GoalMilestoneEntity>>

    @Query("SELECT * FROM goal_milestones ORDER BY goalId, sortOrder, id")
    suspend fun getMilestones(): List<GoalMilestoneEntity>

    @Insert
    suspend fun insertMilestone(entity: GoalMilestoneEntity): Long

    @Update
    suspend fun updateMilestone(entity: GoalMilestoneEntity)

    @Query("DELETE FROM goal_milestones WHERE id = :id")
    suspend fun deleteMilestone(id: Long)
}

@Dao
interface ExerciseLearningDao {
    @Insert
    suspend fun insertAttempt(entity: ExerciseAttemptEntity): Long

    @Query("SELECT * FROM exercise_attempts WHERE problemType = :problemType AND problemId = :problemId ORDER BY attemptIndex, id")
    suspend fun attemptsFor(problemType: String, problemId: Long): List<ExerciseAttemptEntity>

    @Query("SELECT * FROM exercise_attempts WHERE submittedAtEpochMillis BETWEEN :startMillis AND :endMillis ORDER BY submittedAtEpochMillis")
    suspend fun attemptsBetween(startMillis: Long, endMillis: Long): List<ExerciseAttemptEntity>

    @Query("SELECT COUNT(*) FROM exercise_attempts WHERE submittedAtEpochMillis BETWEEN :startMillis AND :endMillis")
    suspend fun attemptCountBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM exercise_attempts ORDER BY submittedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentAttempts(limit: Int): List<ExerciseAttemptEntity>

    @Query("SELECT * FROM exercise_attempts ORDER BY submittedAtEpochMillis")
    suspend fun allAttempts(): List<ExerciseAttemptEntity>

    @Query("SELECT * FROM exercise_attempts WHERE problemType = :problemType AND problemId = :problemId ORDER BY submittedAtEpochMillis DESC LIMIT 1")
    suspend fun latestAttempt(problemType: String, problemId: Long): ExerciseAttemptEntity?

    @Insert
    suspend fun insertHint(entity: ExerciseHintRecordEntity): Long

    @Query("SELECT * FROM exercise_hint_records WHERE problemType = :problemType AND problemId = :problemId ORDER BY level, requestedAtEpochMillis")
    suspend fun hintsFor(problemType: String, problemId: Long): List<ExerciseHintRecordEntity>

    @Query("SELECT * FROM exercise_hint_records WHERE problemType = :problemType AND problemId = :problemId AND level = :level ORDER BY requestedAtEpochMillis DESC LIMIT 1")
    suspend fun latestHint(problemType: String, problemId: Long, level: Int): ExerciseHintRecordEntity?

    @Query("SELECT COUNT(*) FROM exercise_hint_records WHERE requestedAtEpochMillis BETWEEN :startMillis AND :endMillis")
    suspend fun hintCountBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM exercise_hint_records ORDER BY requestedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentHints(limit: Int): List<ExerciseHintRecordEntity>

    @Query("SELECT * FROM exercise_hint_records ORDER BY requestedAtEpochMillis")
    suspend fun allHints(): List<ExerciseHintRecordEntity>

    @Query(
        "SELECT * FROM exercise_hint_records WHERE requestedAtEpochMillis BETWEEN :startMillis AND :endMillis " +
            "ORDER BY requestedAtEpochMillis",
    )
    suspend fun hintsBetween(startMillis: Long, endMillis: Long): List<ExerciseHintRecordEntity>

    @Query("SELECT * FROM exercise_profiles WHERE problemType = :problemType AND problemId = :problemId")
    suspend fun getProfile(problemType: String, problemId: Long): ExerciseProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(entity: ExerciseProfileEntity)

    @Query("SELECT * FROM exercise_profiles ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    suspend fun recentProfiles(limit: Int): List<ExerciseProfileEntity>

    @Query("SELECT * FROM ability_estimates WHERE scope = :scope AND scopeKey = :scopeKey")
    suspend fun getAbility(scope: String, scopeKey: String): AbilityEstimateEntity?

    @Query("SELECT * FROM ability_estimates ORDER BY scope, scopeKey")
    suspend fun getAbilities(): List<AbilityEstimateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAbility(entity: AbilityEstimateEntity)

    @Insert
    suspend fun insertSavedProof(entity: SavedProofEntity): Long

    @Query("SELECT * FROM exercise_saved_proofs WHERE problemType = :problemType AND problemId = :problemId ORDER BY createdAtEpochMillis DESC")
    suspend fun savedProofs(problemType: String, problemId: Long): List<SavedProofEntity>

    @Query("DELETE FROM exercise_saved_proofs WHERE id = :id")
    suspend fun deleteSavedProof(id: Long)

    @Insert
    suspend fun insertErrorReport(entity: ExerciseErrorReportEntity): Long

    @Query("SELECT * FROM exercise_error_reports ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun recentErrorReports(limit: Int): List<ExerciseErrorReportEntity>
}

@Dao
interface V2AiDao {
    @Insert
    suspend fun insertAnalysis(entity: AiAnalysisRecordEntity): Long

    @Query("SELECT * FROM ai_analysis_records WHERE taskType = :taskType ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun analysesByType(taskType: String, limit: Int): List<AiAnalysisRecordEntity>

    @Query("SELECT * FROM ai_analysis_records WHERE taskType = :taskType AND objectType = :objectType AND objectId = :objectId ORDER BY createdAtEpochMillis DESC LIMIT 1")
    suspend fun latestAnalysis(taskType: String, objectType: String, objectId: Long): AiAnalysisRecordEntity?

    @Query("SELECT * FROM ai_analysis_records WHERE createdAtEpochMillis BETWEEN :startMillis AND :endMillis ORDER BY createdAtEpochMillis")
    suspend fun analysesBetween(startMillis: Long, endMillis: Long): List<AiAnalysisRecordEntity>

    @Query("UPDATE ai_analysis_records SET userAccepted = :accepted WHERE id = :id")
    suspend fun setUserAccepted(id: Long, accepted: Boolean)

    @Query("SELECT * FROM weekly_reports WHERE weekStartEpochDay = :weekStartEpochDay LIMIT 1")
    suspend fun getWeeklyReport(weekStartEpochDay: Long): WeeklyReportEntity?

    @Query("SELECT * FROM weekly_reports ORDER BY weekStartEpochDay DESC LIMIT :limit")
    suspend fun recentWeeklyReports(limit: Int): List<WeeklyReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeeklyReport(entity: WeeklyReportEntity)

    @Query("DELETE FROM ai_analysis_records")
    suspend fun deleteAllAnalyses()

    @Query("DELETE FROM weekly_reports")
    suspend fun deleteAllWeeklyReports()
}

@Dao
interface SyncDao {
    @Insert
    suspend fun insertOperation(entity: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations ORDER BY occurredAtEpochMillis DESC, id DESC LIMIT :limit")
    fun observeOperations(limit: Int = 100): Flow<List<SyncOperationEntity>>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE syncedAtEpochMillis IS NULL")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM sync_operations WHERE syncedAtEpochMillis IS NULL ORDER BY occurredAtEpochMillis, id")
    suspend fun pendingOperations(): List<SyncOperationEntity>

    @Query("UPDATE sync_operations SET syncedAtEpochMillis = :syncedAt WHERE syncedAtEpochMillis IS NULL")
    suspend fun markAllSynced(syncedAt: Long)

    /** 只把指定对象类型的待同步操作标为已同步，避免导出某一范围时误清其他范围的队列。 */
    @Query(
        "UPDATE sync_operations SET syncedAtEpochMillis = :syncedAt " +
            "WHERE syncedAtEpochMillis IS NULL AND objectType IN (:objectTypes)",
    )
    suspend fun markSynced(objectTypes: List<String>, syncedAt: Long)

    /** 只清理一个对象的待同步操作，保留其他对象的本地修改。 */
    @Query(
        "UPDATE sync_operations SET syncedAtEpochMillis = :syncedAt " +
            "WHERE syncedAtEpochMillis IS NULL AND objectType = :objectType AND objectId = :objectId",
    )
    suspend fun markObjectSynced(objectType: String, objectId: Long, syncedAt: Long)

    @Query("SELECT * FROM sync_baselines")
    suspend fun getBaselines(): List<SyncBaselineEntity>

    @Query("SELECT * FROM sync_baselines WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun getBaseline(objectType: String, objectId: Long): SyncBaselineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBaseline(entity: SyncBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBaselines(entities: List<SyncBaselineEntity>)

    @Query("DELETE FROM sync_baselines WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun deleteBaseline(objectType: String, objectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(entity: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getTombstones(): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones WHERE deletedAtEpochMillis > :sinceMillis")
    suspend fun tombstonesSince(sinceMillis: Long): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun deleteTombstone(objectType: String, objectId: Long)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: SyncStateEntity)

    @Query("DELETE FROM sync_operations")
    suspend fun deleteAllOperations()

    @Query("DELETE FROM sync_baselines")
    suspend fun deleteAllBaselines()

    @Query("DELETE FROM sync_tombstones")
    suspend fun deleteAllTombstones()
}

@Dao
interface HomeCardDao {
    @Query("SELECT * FROM home_card_preferences ORDER BY sortOrder, cardId")
    fun observeCards(): Flow<List<HomeCardPreferenceEntity>>

    @Query("SELECT * FROM home_card_preferences ORDER BY sortOrder, cardId")
    suspend fun getCards(): List<HomeCardPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(cards: List<HomeCardPreferenceEntity>)
}
