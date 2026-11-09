package dev.sonicbrief.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jobs",
    indices = [
        Index(value = ["updatedAtMs"]),
        Index(value = ["status"]),
    ],
)
data class JobEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val sourceUrl: String?,
    val sourceFilename: String?,
    val title: String?,
    val creator: String?,
    val durationMs: Long?,
    val status: String,
    val stage: String,
    val progress: Int,
    val stageDetail: String?,
    val transcriptLanguage: String,
    val summaryLanguage: String,
    val customInstructions: String?,
    val model: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long?,
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["jobId", "revision"], unique = true),
    ],
)
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val language: String,
    val revision: Int,
    val fullText: String,
    val sourceKind: String,
    val isUserEdited: Boolean,
    val createdAtMs: Long,
)

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["transcriptId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["transcriptId"])],
)
data class TranscriptSegmentEntity(
    val transcriptId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["jobId"]), Index(value = ["transcriptId"])],
)
data class SummaryEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val transcriptId: String,
    val transcriptRevision: Int,
    val language: String,
    val content: String,
    val model: String?,
    val customInstructions: String?,
    val createdAtMs: Long,
)
