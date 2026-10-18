package com.hbhaq.metricme.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface RoutineDao {
    @Query("SELECT * FROM HabitDefinition WHERE profileId='local'") fun observeHabits(): Flow<List<HabitEntity>>
    @Query("SELECT * FROM Medication WHERE profileId='local'") fun observeMedications(): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM HabitOccurrence") fun observeHabitOccurrences(): Flow<List<HabitOccurrenceEntity>>
    @Query("SELECT * FROM MedicationOccurrence") fun observeMedicationOccurrences(): Flow<List<MedicationOccurrenceEntity>>
    @Query("SELECT * FROM ReminderSchedule WHERE profileId='local'") fun observeSchedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM ScheduleVersion") fun observeVersions(): Flow<List<ScheduleVersionEntity>>
    @Query("SELECT * FROM MedicationPrn ORDER BY instant DESC") fun observePrn(): Flow<List<PrnEntity>>
    @Query("SELECT * FROM ReminderSettings WHERE id='local'") fun observeSettings(): Flow<ReminderSettingsEntity?>
    @Query("SELECT * FROM HabitDefinition WHERE profileId='local'") suspend fun habits(): List<HabitEntity>
    @Query("SELECT * FROM Medication WHERE profileId='local'") suspend fun medications(): List<MedicationEntity>
    @Query("SELECT * FROM ReminderSchedule WHERE profileId='local'") suspend fun schedules(): List<ScheduleEntity>
    @Query("SELECT * FROM ScheduleVersion WHERE scheduleId=:id") suspend fun versions(id: String): List<ScheduleVersionEntity>
    @Query("SELECT * FROM HabitOccurrence WHERE id=:id") suspend fun habitOccurrence(id: String): HabitOccurrenceEntity?
    @Query("SELECT * FROM MedicationOccurrence WHERE id=:id") suspend fun medicationOccurrence(id: String): MedicationOccurrenceEntity?
    @Query("SELECT * FROM HabitOccurrence") suspend fun habitOccurrences(): List<HabitOccurrenceEntity>
    @Query("SELECT * FROM MedicationOccurrence") suspend fun medicationOccurrences(): List<MedicationOccurrenceEntity>
    @Upsert suspend fun habit(value: HabitEntity)
    @Upsert suspend fun medication(value: MedicationEntity)
    @Upsert suspend fun schedule(value: ScheduleEntity)
    @Upsert suspend fun version(value: ScheduleVersionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertHabitOccurrence(value: HabitOccurrenceEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMedicationOccurrence(value: MedicationOccurrenceEntity): Long
    @Update suspend fun updateHabitOccurrence(value: HabitOccurrenceEntity)
    @Update suspend fun updateMedicationOccurrence(value: MedicationOccurrenceEntity)
    @Insert suspend fun prn(value: PrnEntity)
    @Query("DELETE FROM MedicationPrn WHERE id=:id") suspend fun deletePrn(id: String)
    @Query("SELECT * FROM ReminderDelivery") suspend fun deliveries(): List<DeliveryEntity>
    @Query("SELECT * FROM ReminderDelivery WHERE occurrenceId=:id") suspend fun delivery(id: String): DeliveryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDelivery(value: DeliveryEntity)
    @Update suspend fun updateDelivery(value: DeliveryEntity)
    @Query("UPDATE ReminderDelivery SET deliveredFor=:due WHERE occurrenceId=:id AND dueAt=:due AND (deliveredFor IS NULL OR deliveredFor != :due)") suspend fun claim(id: String, due: Long): Int
    @Query("SELECT * FROM ReminderSettings WHERE id='local'") suspend fun settings(): ReminderSettingsEntity?
    @Upsert suspend fun settings(value: ReminderSettingsEntity)
}
