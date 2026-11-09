package dev.sonicbrief.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sonicbrief.app.data.job.RoomResultRepository
import dev.sonicbrief.app.data.local.JobDao
import dev.sonicbrief.app.data.local.SonicBriefDatabase
import dev.sonicbrief.app.data.local.TranscriptDao
import dev.sonicbrief.app.domain.repository.ResultRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonicBriefDatabase =
        Room.databaseBuilder(context, SonicBriefDatabase::class.java, "sonicbrief-mobile.db").build()

    @Provides
    fun provideJobDao(database: SonicBriefDatabase): JobDao = database.jobDao()

    @Provides
    fun provideTranscriptDao(database: SonicBriefDatabase): TranscriptDao = database.transcriptDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("sonicbrief_settings.preferences_pb")
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    abstract fun bindResultRepository(repository: RoomResultRepository): ResultRepository
}
