package dev.sonicbrief.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sonicbrief.app.data.local.entity.JobEntity
import dev.sonicbrief.app.data.local.entity.SummaryEntity
import dev.sonicbrief.app.data.local.entity.TranscriptEntity
import dev.sonicbrief.app.data.local.entity.TranscriptSegmentEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE jobs ADD COLUMN provider TEXT NOT NULL DEFAULT 'gemini'")
    }
}

@Database(
    entities = [
        JobEntity::class,
        TranscriptEntity::class,
        TranscriptSegmentEntity::class,
        SummaryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SonicBriefDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun transcriptDao(): TranscriptDao
}
