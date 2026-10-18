package com.hbhaq.metricme.di

import android.content.Context
import androidx.room.Room
import com.hbhaq.metricme.data.MetricMeDatabase
import com.hbhaq.metricme.data.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): MetricMeDatabase =
        Room.databaseBuilder(context, MetricMeDatabase::class.java, "metricme.db").addMigrations(MIGRATION_1_2).build()
    @Provides fun clock(): Clock = object : Clock() {
        override fun getZone() = java.time.ZoneId.systemDefault()
        override fun instant() = java.time.Instant.now()
        override fun withZone(zone: java.time.ZoneId): Clock = Clock.system(zone)
    }
}
