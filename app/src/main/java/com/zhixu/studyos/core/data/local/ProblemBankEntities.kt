package com.zhixu.studyos.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhixu.studyos.core.model.ProblemAnalysisSource
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentPage
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemFormula
import com.zhixu.studyos.core.model.ProblemImportBatch
import com.zhixu.studyos.core.model.ProblemImportBatchStatus
import com.zhixu.studyos.core.model.ProblemImportItem
import com.zhixu.studyos.core.model.ProblemImportItemStatus
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import com.zhixu.studyos.core.model.ProblemPageTextSource
import com.zhixu.studyos.core.model.ProblemReviewStatus
import java.time.Instant

@Entity(tableName = "problem_documents")
data class ProblemDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val type: String,
    val relativePath: String,
    val originalName: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val pageCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_document_pages",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "pageIndex"], unique = true)],
)
data class ProblemDocumentPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val text: String,
    val textSource: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_import_batches",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId"])],
)
data class ProblemImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val status: String,
    val totalPages: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_import_items",
    foreignKeys = [
        ForeignKey(
            entity = ProblemImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["batchId", "pageIndex"], unique = true)],
)
data class ProblemImportItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val pageIndex: Int,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val draftJson: String?,
)

@Entity(
    tableName = "problem_bank_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProblemDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["documentId", "chapter", "problemNumber"], unique = true),
        Index(value = ["completionStatus"]),
        Index(value = ["reviewStatus"]),
        Index(value = ["courseId"]),
    ],
)
data class ProblemBankEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val courseId: Long?,
    val courseName: String,
    val textbook: String,
    val chapter: String,
    val problemNumber: String,
    val statement: String,
    val estimatedDifficulty: Int?,
    val difficultySource: String,
    val estimatedMinutes: Int?,
    val actualMinutes: Int?,
    val completionStatus: String,
    val reviewStatus: String,
    val learningSuggestion: String?,
    val suggestionSource: String?,
    val dependsOn: String,
    val importBatchId: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "problem_bank_formulas",
    foreignKeys = [
        ForeignKey(
            entity = ProblemBankEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["problemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["problemId", "orderIndex"], unique = true)],
)
data class ProblemBankFormulaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemId: Long,
    val orderIndex: Int,
    val latex: String,
    val confidence: Double,
    val needsReview: Boolean,
    val originalSnippet: String,
)

@Entity(
    tableName = "problem_bank_knowledge_points",
    foreignKeys = [
        ForeignKey(
            entity = ProblemBankEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["problemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["problemId", "name"], unique = true)],
)
data class ProblemBankKnowledgePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemId: Long,
    val name: String,
    val source: String,
)

fun ProblemDocumentEntity.toDomainModel() = ProblemDocument(
    id = id,
    displayName = displayName,
    type = ProblemDocumentType.valueOf(type),
    relativePath = relativePath,
    originalName = originalName,
    sizeBytes = sizeBytes,
    contentSha256 = contentSha256,
    pageCount = pageCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun ProblemDocumentPageEntity.toDomainModel() = ProblemDocumentPage(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    text = text,
    textSource = ProblemPageTextSource.valueOf(textSource),
    createdAtEpochMillis = createdAtEpochMillis,
)

fun ProblemImportBatchEntity.toDomainModel() = ProblemImportBatch(
    id = id,
    documentId = documentId,
    status = ProblemImportBatchStatus.valueOf(status),
    totalPages = totalPages,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun ProblemImportItemEntity.toDomainModel() = ProblemImportItem(
    id = id,
    batchId = batchId,
    pageIndex = pageIndex,
    status = ProblemImportItemStatus.valueOf(status),
    attempts = attempts,
    lastError = lastError,
    draftJson = draftJson,
)

fun ProblemBankEntryEntity.toDomainModel(
    formulas: List<ProblemFormula>,
    knowledgePoints: List<ProblemKnowledgePoint>,
) = ProblemBankEntry(
    id = id,
    documentId = documentId,
    pageIndex = pageIndex,
    courseId = courseId,
    courseName = courseName,
    textbook = textbook,
    chapter = chapter,
    problemNumber = problemNumber,
    statement = statement,
    formulas = formulas,
    knowledgePoints = knowledgePoints,
    estimatedDifficulty = estimatedDifficulty,
    difficultySource = ProblemAnalysisSource.valueOf(difficultySource),
    estimatedMinutes = estimatedMinutes,
    actualMinutes = actualMinutes,
    completionStatus = ProblemCompletionStatus.valueOf(completionStatus),
    reviewStatus = ProblemReviewStatus.valueOf(reviewStatus),
    learningSuggestion = learningSuggestion,
    suggestionSource = suggestionSource?.let(ProblemAnalysisSource::valueOf),
    dependsOn = dependsOn.split("\n").filter(String::isNotEmpty),
    importBatchId = importBatchId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun ProblemBankFormulaEntity.toDomainModel() = ProblemFormula(
    id = id,
    orderIndex = orderIndex,
    latex = latex,
    confidence = confidence,
    needsReview = needsReview,
    originalSnippet = originalSnippet,
)

fun ProblemBankKnowledgePointEntity.toDomainModel() = ProblemKnowledgePoint(
    id = id,
    name = name,
    source = ProblemAnalysisSource.valueOf(source),
)
