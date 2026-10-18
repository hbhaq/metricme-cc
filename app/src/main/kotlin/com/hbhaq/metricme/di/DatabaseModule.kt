package com.hbhaq.metricme.di

import android.content.Context
import androidx.room.Room
import com.hbhaq.metricme.data.MetricMeDatabase
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
        Room.databaseBuilder(context, MetricMeDatabase::class.java, "metricme.db").build()
    @Provides fun clock(): Clock = Clock.systemDefaultZone()
}
