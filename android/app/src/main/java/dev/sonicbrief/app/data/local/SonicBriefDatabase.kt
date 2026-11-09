package dev.sonicbrief.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.sonicbrief.app.data.local.entity.JobEntity
import dev.sonicbrief.app.data.local.entity.SummaryEntity
import dev.sonicbrief.app.data.local.entity.TranscriptEntity
import dev.sonicbrief.app.data.local.entity.TranscriptSegmentEntity

@Database(
    entities = [
        JobEntity::class,
        TranscriptEntity::class,
        TranscriptSegmentEntity::class,
        SummaryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SonicBriefDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun transcriptDao(): TranscriptDao
}
