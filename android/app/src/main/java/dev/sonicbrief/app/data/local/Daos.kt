package dev.sonicbrief.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.sonicbrief.app.data.local.entity.JobEntity
import dev.sonicbrief.app.data.local.entity.SummaryEntity
import dev.sonicbrief.app.data.local.entity.TranscriptEntity
import dev.sonicbrief.app.data.local.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    fun observeById(jobId: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    suspend fun getById(jobId: String): JobEntity?

    @Upsert
    suspend fun upsert(job: JobEntity)

    @Query("UPDATE jobs SET title = :title, updatedAtMs = :now WHERE id = :jobId")
    suspend fun rename(jobId: String, title: String, now: Long)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun delete(jobId: String)

    /** Jobs that were mid-flight when the process died; marked failed on next launch. */
    @Query("SELECT * FROM jobs WHERE status = 'active'")
    suspend fun getActive(): List<JobEntity>
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE jobId = :jobId ORDER BY revision DESC")
    suspend fun getTranscripts(jobId: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE id = :transcriptId LIMIT 1")
    suspend fun getTranscript(transcriptId: String): TranscriptEntity?

    @Query("SELECT * FROM transcript_segments WHERE transcriptId = :transcriptId ORDER BY sequence ASC")
    suspend fun getSegments(transcriptId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM summaries WHERE jobId = :jobId ORDER BY createdAtMs DESC")
    suspend fun getSummaries(jobId: String): List<SummaryEntity>

    @Query("SELECT COALESCE(MAX(revision), 0) FROM transcripts WHERE jobId = :jobId")
    suspend fun maxRevision(jobId: String): Int

    @Upsert
    suspend fun upsertTranscript(transcript: TranscriptEntity)

    @Upsert
    suspend fun upsertSegments(segments: List<TranscriptSegmentEntity>)

    @Upsert
    suspend fun upsertSummary(summary: SummaryEntity)

    @Transaction
    suspend fun insertTranscriptWithSegments(
        transcript: TranscriptEntity,
        segments: List<TranscriptSegmentEntity>,
    ) {
        upsertTranscript(transcript)
        upsertSegments(segments)
    }
}
