package com.hbhaq.metricme.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM UserProfile WHERE id = 'local'") fun observeProfile(): Flow<ProfileEntity?>
    @Query("SELECT * FROM UserProfile WHERE id = 'local'") suspend fun profile(): ProfileEntity?
    @Upsert suspend fun saveProfile(profile: ProfileEntity)
    @Query("SELECT * FROM BodyMeasurement WHERE profileId = 'local' ORDER BY instant DESC, sequence DESC") fun observeMeasurements(): Flow<List<MeasurementEntity>>
    @Query("SELECT * FROM BodyMeasurement WHERE profileId = 'local' ORDER BY instant DESC, sequence DESC") suspend fun measurements(): List<MeasurementEntity>
    @Insert suspend fun insertMeasurement(measurement: MeasurementEntity): Long
    @Update suspend fun updateMeasurement(measurement: MeasurementEntity)
    @Query("DELETE FROM BodyMeasurement WHERE id = :id AND profileId = 'local'") suspend fun deleteMeasurement(id: String)
    @Query("SELECT * FROM TargetHistory WHERE profileId = 'local' ORDER BY effectiveDate, createdAt, sequence") fun observeTargets(): Flow<List<TargetEntity>>
    @Query("SELECT * FROM TargetHistory WHERE profileId = 'local' ORDER BY effectiveDate, createdAt, sequence") suspend fun targets(): List<TargetEntity>
    @Insert suspend fun insertTarget(target: TargetEntity)
    @Query("SELECT * FROM OperationReceipt WHERE operationKey = :key") suspend fun receipt(key: String): ReceiptEntity?
    @Insert suspend fun insertReceipt(receipt: ReceiptEntity)
}

@Database(entities = [ProfileEntity::class, MeasurementEntity::class, TargetEntity::class,
    FoodEntity::class, FoodComponentEntity::class, ActivityEntity::class, StepEntity::class, SleepEntity::class,
    HabitEntity::class, MedicationEntity::class, ScheduleEntity::class, ScheduleVersionEntity::class,
    HabitOccurrenceEntity::class, MedicationOccurrenceEntity::class, DraftEntity::class,
    ProcessingJobEntity::class, ReportEntity::class, ReceiptEntity::class, TombstoneEntity::class,
    ExerciseEntity::class, PrnEntity::class, DeliveryEntity::class, ReminderSettingsEntity::class], version = 2, exportSchema = true)
abstract class MetricMeDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun loggingDao(): LoggingDao
    abstract fun routineDao(): RoutineDao
}
