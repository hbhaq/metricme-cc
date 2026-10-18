package com.hbhaq.metricme.data

import androidx.room.*

data class Stamp(
    val source: String = "MANUAL",
    val confirmation: String = "CONFIRMED",
    val processing: String = "COMPLETE",
    val confidence: Double? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

@Entity(tableName = "UserProfile")
data class ProfileEntity(
    @PrimaryKey val id: String = "local",
    val name: String = "",
    val birthDate: String? = null,
    val genderIdentity: String = "",
    val equationInput: String? = null,
    val activity: String = "SEDENTARY",
    val disableAutomaticTargets: Boolean = false,
    val goals: String = "",
    val restrictions: String = "",
    val imperialDisplay: Boolean = false,
    @Embedded val stamp: Stamp
)

@Entity(tableName = "BodyMeasurement", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("id", unique = true), Index("operationKey", unique = true), Index(value = ["profileId", "kind", "instant"])])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val profileId: String = "local",
    val kind: String,
    val value: Double,
    val instant: Long,
    val zoneId: String,
    val acquisition: String,
    val operationKey: String,
    @Embedded val stamp: Stamp
)

@Entity(tableName = "TargetHistory", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("id", unique = true), Index(value = ["profileId", "effectiveDate"])])
data class TargetEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val profileId: String = "local",
    val effectiveDate: String,
    val calories: Double?,
    val bmr: Double?,
    val manual: Boolean,
    val explanation: String,
    val createdAt: Long
)

@Entity(tableName = "FoodEntry", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "localDate"]), Index("operationKey", unique = true)])
data class FoodEntity(@PrimaryKey val id: String, val profileId: String, val instant: Long, val zoneId: String, val offsetSeconds: Int, val localDate: String, val mealType: String, val portionConsumed: Double, val operationKey: String, @Embedded val stamp: Stamp)

@Entity(tableName = "FoodComponent", foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.CASCADE)], indices = [Index("foodId")])
data class FoodComponentEntity(@PrimaryKey val id: String, val foodId: String, val name: String, val quantity: Double, val unit: String, val calories: Double, val proteinGrams: Double, val carbsGrams: Double, val fatGrams: Double)

@Entity(tableName = "ActivityEntry", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "localDate"]), Index("operationKey", unique = true)])
data class ActivityEntity(@PrimaryKey val id: String, val profileId: String, val instant: Long, val zoneId: String, val offsetSeconds: Int, val localDate: String, val type: String, val durationSeconds: Long?, val reportedSteps: Int?, val intensity: String?, val notes: String, val operationKey: String, @Embedded val stamp: Stamp)

@Entity(tableName = "StepEntry", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "localDate"]), Index("operationKey", unique = true)])
data class StepEntity(@PrimaryKey val id: String, val profileId: String, val instant: Long, val zoneId: String, val offsetSeconds: Int, val localDate: String, val steps: Int, val isDailyTotal: Boolean, val operationKey: String, @Embedded val stamp: Stamp)

@Entity(tableName = "SleepEntry", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "wakeDate"]), Index("operationKey", unique = true)])
data class SleepEntity(@PrimaryKey val id: String, val profileId: String, val bedtime: Long, val wakeTime: Long, val bedtimeZone: String, val wakeZone: String, val wakeDate: String, val quality: String?, val operationKey: String, @Embedded val stamp: Stamp)

@Entity(tableName = "HabitDefinition", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class HabitEntity(@PrimaryKey val id: String, val profileId: String, val name: String, val selectedWeekdays: String, val archived: Boolean, @Embedded val stamp: Stamp)

@Entity(tableName = "Medication", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class MedicationEntity(@PrimaryKey val id: String, val profileId: String, val name: String, val instructions: String, val doseText: String, val notes: String, val asNeeded: Boolean, val endedAt: Long?, @Embedded val stamp: Stamp)

@Entity(tableName = "ReminderSchedule", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId"), Index("habitId"), Index("medicationId")])
data class ScheduleEntity(@PrimaryKey val id: String, val profileId: String, val habitId: String?, val medicationId: String?, val wallTime: String, val weekdays: String, val zoneId: String, val active: Boolean)

@Entity(tableName = "ScheduleVersion", foreignKeys = [ForeignKey(entity = ScheduleEntity::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("scheduleId")])
data class ScheduleVersionEntity(@PrimaryKey val id: String, val scheduleId: String, val effectiveFrom: Long, val effectiveUntil: Long?, val wallTime: String, val weekdays: String, val zoneId: String)

@Entity(tableName = "HabitOccurrence", foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ScheduleVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("habitId"), Index(value = ["versionId", "scheduledAt"], unique = true)])
data class HabitOccurrenceEntity(@PrimaryKey val id: String, val habitId: String, val versionId: String, val scheduledAt: Long, val offsetSeconds: Int, val status: String, @Embedded val stamp: Stamp)

@Entity(tableName = "MedicationOccurrence", foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ScheduleVersionEntity::class, parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("medicationId"), Index(value = ["versionId", "scheduledAt"], unique = true)])
data class MedicationOccurrenceEntity(@PrimaryKey val id: String, val medicationId: String, val versionId: String, val scheduledAt: Long, val offsetSeconds: Int, val status: String, val snoozedUntil: Long?, @Embedded val stamp: Stamp)

@Entity(tableName = "Draft", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId"), Index("operationKey", unique = true)])
data class DraftEntity(@PrimaryKey val id: String, val profileId: String, val kind: String, val revision: Int, val localMediaPath: String?, val content: String, val operationKey: String, @Embedded val stamp: Stamp)

@Entity(tableName = "ProcessingJob", foreignKeys = [ForeignKey(entity = DraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE)], indices = [Index("draftId"), Index("operationKey", unique = true), Index(value = ["status", "nextAttemptAt"])])
data class ProcessingJobEntity(@PrimaryKey val id: String, val draftId: String, val operationKey: String, val status: String, val attempt: Int, val nextAttemptAt: Long?, val consentVersion: String, val revision: Int, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "CoachingReport", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "periodType", "periodStart", "periodEnd"], unique = true)])
data class ReportEntity(@PrimaryKey val id: String, val profileId: String, val periodType: String, val periodStart: String, val periodEnd: String, val inputVersion: String, val generatedAt: Long, val modelConfig: String, val content: String, val sample: Boolean)

@Entity(tableName = "OperationReceipt", foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class ReceiptEntity(@PrimaryKey val operationKey: String, val profileId: String, val payloadHash: String, val resultId: String, val createdAt: Long)

@Entity(tableName = "DeletionTombstone")
data class TombstoneEntity(@PrimaryKey val id: String, val deletedAt: Long, val backendPending: Boolean)
