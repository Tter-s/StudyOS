package com.zhixu.studyos.core.data.local

import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import kotlinx.coroutines.flow.Flow

data class ProblemBankEntryWithDetails(
    @Embedded val entry: ProblemBankEntryEntity,
    @Relation(parentColumn = "id", entityColumn = "problemId")
    val formulas: List<ProblemBankFormulaEntity>,
    @Relation(parentColumn = "id", entityColumn = "problemId")
    val knowledgePoints: List<ProblemBankKnowledgePointEntity>,
) {
    fun toDomainModel() = entry.toDomainModel(
        formulas = formulas.sortedBy(ProblemBankFormulaEntity::orderIndex).map { it.toDomainModel() },
        knowledgePoints = knowledgePoints.map { it.toDomainModel() },
    )
}

@Dao
interface ProblemBankDao {
    // ---------- 来源文档 ----------

    @Insert
    suspend fun insertDocument(document: ProblemDocumentEntity): Long

    @Query("SELECT * FROM problem_documents WHERE id = :id")
    suspend fun getDocument(id: Long): ProblemDocumentEntity?

    @Query("SELECT * FROM problem_documents WHERE contentSha256 = :sha256 LIMIT 1")
    suspend fun findDocumentBySha256(sha256: String): ProblemDocumentEntity?

    @Query("SELECT * FROM problem_documents ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observeDocuments(): Flow<List<ProblemDocumentEntity>>

    @Query("SELECT * FROM problem_documents ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun getDocuments(): List<ProblemDocumentEntity>

    @Query(
        "UPDATE problem_documents SET pageCount = :pageCount, updatedAtEpochMillis = :updatedAt " +
            "WHERE id = :documentId",
    )
    suspend fun updateDocumentPageCount(documentId: Long, pageCount: Int, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM problem_bank_entries WHERE documentId = :documentId")
    suspend fun countProblemsForDocument(documentId: Long): Int

    // ---------- 页面文本 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: ProblemDocumentPageEntity): Long

    @Query("SELECT * FROM problem_document_pages WHERE documentId = :documentId AND pageIndex = :pageIndex")
    suspend fun getPage(documentId: Long, pageIndex: Int): ProblemDocumentPageEntity?

    @Query("SELECT * FROM problem_document_pages WHERE documentId = :documentId ORDER BY pageIndex")
    suspend fun getPages(documentId: Long): List<ProblemDocumentPageEntity>

    // ---------- 导入批次 ----------

    @Insert
    suspend fun insertBatch(batch: ProblemImportBatchEntity): Long

    @Query("SELECT * FROM problem_import_batches WHERE id = :id")
    suspend fun getBatch(id: Long): ProblemImportBatchEntity?

    @Query(
        "SELECT * FROM problem_import_batches WHERE documentId = :documentId " +
            "AND status IN ('RUNNING', 'PAUSED') ORDER BY id DESC LIMIT 1",
    )
    suspend fun findResumableBatch(documentId: Long): ProblemImportBatchEntity?

    @Query(
        "SELECT * FROM problem_import_batches WHERE documentId = :documentId ORDER BY id DESC LIMIT 1",
    )
    suspend fun findLatestBatch(documentId: Long): ProblemImportBatchEntity?

    @Query(
        "UPDATE problem_import_batches SET status = :status, updatedAtEpochMillis = :updatedAt " +
            "WHERE id = :batchId",
    )
    suspend fun updateBatchStatus(batchId: Long, status: ProblemImportBatchStatus, updatedAt: Long)

    @Insert
    suspend fun insertItems(items: List<ProblemImportItemEntity>)

    @Query("SELECT * FROM problem_import_items WHERE batchId = :batchId ORDER BY pageIndex")
    suspend fun getItems(batchId: Long): List<ProblemImportItemEntity>

    @Query(
        "UPDATE problem_import_items SET status = :status, attempts = :attempts, " +
            "lastError = :lastError, draftJson = :draftJson WHERE id = :itemId",
    )
    suspend fun updateItem(
        itemId: Long,
        status: ProblemImportItemStatus,
        attempts: Int,
        lastError: String?,
        draftJson: String?,
    )

    // ---------- 题库 ----------

    @Transaction
    @Query("SELECT * FROM problem_bank_entries ORDER BY documentId, pageIndex, id")
    fun observeAllProblems(): Flow<List<ProblemBankEntryWithDetails>>

    @Transaction
    @Query("SELECT * FROM problem_bank_entries WHERE id = :id")
    suspend fun getProblemWithDetails(id: Long): ProblemBankEntryWithDetails?

    @Insert
    suspend fun insertProblem(entry: ProblemBankEntryEntity): Long

    @androidx.room.Update
    suspend fun updateProblemEntity(entry: ProblemBankEntryEntity)

    @Insert
    suspend fun insertFormulas(formulas: List<ProblemBankFormulaEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgePoints(points: List<ProblemBankKnowledgePointEntity>)

    @Query("UPDATE problem_bank_entries SET updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun touchProblem(id: Long, updatedAt: Long)

    @Query("DELETE FROM problem_bank_entries WHERE id = :id")
    suspend fun deleteProblem(id: Long)

    @Query("DELETE FROM problem_bank_formulas WHERE problemId = :problemId")
    suspend fun deleteFormulasForProblem(problemId: Long)

    @Query("DELETE FROM problem_bank_knowledge_points WHERE problemId = :problemId")
    suspend fun deleteKnowledgePointsForProblem(problemId: Long)

    @Query(
        "SELECT chapter, problemNumber AS number FROM problem_bank_entries WHERE documentId = :documentId",
    )
    suspend fun findKeysForDocument(documentId: Long): List<EntryKeyRow>

    @Query(
        "SELECT DISTINCT chapter, problemNumber AS number FROM problem_bank_entries " +
            "WHERE textbook = :textbook AND documentId != :excludeDocumentId",
    )
    suspend fun findSuspectedKeys(textbook: String, excludeDocumentId: Long): List<EntryKeyRow>

    @Query("DELETE FROM problem_documents WHERE id = :documentId")
    suspend fun deleteDocumentById(documentId: Long)

    @Query(
        "SELECT documentId, COUNT(*) AS problemCount FROM problem_bank_entries GROUP BY documentId",
    )
    suspend fun problemCountsByDocument(): List<DocumentProblemCountRow>

    data class EntryKeyRow(val chapter: String, val number: String)

    data class ProblemKnowledgeNameRow(val problemId: Long, val name: String)

    @Query("SELECT problemId, name FROM problem_bank_knowledge_points")
    suspend fun allKnowledgePointLinks(): List<ProblemKnowledgeNameRow>

    data class ProblemSummaryRow(
        val id: Long,
        val courseName: String,
        val chapter: String,
        val problemNumber: String,
        val statement: String,
        val estimatedDifficulty: Int?,
        val estimatedMinutes: Int?,
        val completionStatus: String,
        val reviewStatus: String,
    )

    @Query(
        "SELECT id, courseName, chapter, problemNumber, statement, estimatedDifficulty, " +
            "estimatedMinutes, completionStatus, reviewStatus FROM problem_bank_entries ORDER BY id",
    )
    suspend fun allProblemSummaries(): List<ProblemSummaryRow>

    data class DocumentProblemCountRow(val documentId: Long, val problemCount: Int)

    @Query(
        "SELECT problemNumber FROM problem_bank_entries " +
            "WHERE documentId = :documentId AND chapter = :chapter AND problemNumber IN (:numbers)",
    )
    suspend fun findExistingNumbers(
        documentId: Long,
        chapter: String,
        numbers: List<String>,
    ): List<String>

    // ---------- 分页查询（V1.0 大型题库） ----------

    @Transaction
    @Query(
        """
        SELECT * FROM problem_bank_entries
        WHERE (:documentId IS NULL OR documentId = :documentId)
          AND (:chapter IS NULL OR chapter = :chapter)
          AND (:completionStatus IS NULL OR completionStatus = :completionStatus)
          AND (:onlyUnfinished = 0 OR completionStatus != 'COMPLETED')
          AND (:maxDifficulty IS NULL OR COALESCE(estimatedDifficulty, 0) <= :maxDifficulty)
          AND (:onlyFormulaReview = 0 OR EXISTS (
              SELECT 1 FROM problem_bank_formulas f
              WHERE f.problemId = problem_bank_entries.id AND f.needsReview = 1
          ))
          AND (:searchPattern = '' OR statement LIKE :searchPattern ESCAPE '|'
               OR problemNumber LIKE :searchPattern ESCAPE '|'
               OR chapter LIKE :searchPattern ESCAPE '|'
               OR EXISTS (
                   SELECT 1 FROM problem_bank_knowledge_points k
                   WHERE k.problemId = problem_bank_entries.id AND k.name LIKE :searchPattern ESCAPE '|'
               ))
        ORDER BY documentId, pageIndex, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageProblems(
        documentId: Long?,
        chapter: String?,
        completionStatus: String?,
        onlyUnfinished: Int,
        maxDifficulty: Int?,
        onlyFormulaReview: Int,
        searchPattern: String,
        limit: Int,
        offset: Int,
    ): List<ProblemBankEntryWithDetails>

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN completionStatus = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed
        FROM problem_bank_entries
        WHERE (:documentId IS NULL OR documentId = :documentId)
          AND (:chapter IS NULL OR chapter = :chapter)
          AND (:completionStatus IS NULL OR completionStatus = :completionStatus)
          AND (:onlyUnfinished = 0 OR completionStatus != 'COMPLETED')
          AND (:maxDifficulty IS NULL OR COALESCE(estimatedDifficulty, 0) <= :maxDifficulty)
          AND (:onlyFormulaReview = 0 OR EXISTS (
              SELECT 1 FROM problem_bank_formulas f
              WHERE f.problemId = problem_bank_entries.id AND f.needsReview = 1
          ))
          AND (:searchPattern = '' OR statement LIKE :searchPattern ESCAPE '|'
               OR problemNumber LIKE :searchPattern ESCAPE '|'
               OR chapter LIKE :searchPattern ESCAPE '|'
               OR EXISTS (
                   SELECT 1 FROM problem_bank_knowledge_points k
                   WHERE k.problemId = problem_bank_entries.id AND k.name LIKE :searchPattern ESCAPE '|'
               ))
        """,
    )
    suspend fun countProblems(
        documentId: Long?,
        chapter: String?,
        completionStatus: String?,
        onlyUnfinished: Int,
        maxDifficulty: Int?,
        onlyFormulaReview: Int,
        searchPattern: String,
    ): ProblemCountRow

    @Query("SELECT DISTINCT chapter FROM problem_bank_entries WHERE chapter != '' ORDER BY chapter")
    suspend fun distinctChapters(): List<String>

    data class ProblemCountRow(val total: Int, val completed: Int)

    /**
     * AI 分析用的困难题目候选：只返回可能需要的少量记录，避免把整个题库读入内存。
     * 条件：实际用时超过估计 2 倍，或已完成但未掌握且存在待校对公式。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM problem_bank_entries
        WHERE (actualMinutes IS NOT NULL AND estimatedMinutes IS NOT NULL
               AND actualMinutes > estimatedMinutes * 2)
           OR (completionStatus = 'COMPLETED' AND reviewStatus != 'MASTERED'
               AND EXISTS (
                   SELECT 1 FROM problem_bank_formulas f
                   WHERE f.problemId = problem_bank_entries.id AND f.needsReview = 1
               ))
        ORDER BY updatedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun difficultProblemCandidates(limit: Int): List<ProblemBankEntryWithDetails>
}
