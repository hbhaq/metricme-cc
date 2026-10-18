package com.hbhaq.metricme.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE FoodComponent_new (id TEXT NOT NULL PRIMARY KEY, foodId TEXT NOT NULL, name TEXT NOT NULL, quantity REAL NOT NULL, unit TEXT NOT NULL, calories REAL, proteinGrams REAL, carbsGrams REAL, fatGrams REAL, FOREIGN KEY(foodId) REFERENCES FoodEntry(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO FoodComponent_new SELECT * FROM FoodComponent")
        db.execSQL("DROP TABLE FoodComponent")
        db.execSQL("ALTER TABLE FoodComponent_new RENAME TO FoodComponent")
        db.execSQL("CREATE INDEX index_FoodComponent_foodId ON FoodComponent(foodId)")
        db.execSQL("ALTER TABLE HabitDefinition ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE HabitOccurrence ADD COLUMN labelSnapshot TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MedicationOccurrence ADD COLUMN labelSnapshot TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ScheduleVersion ADD COLUMN labelSnapshot TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE ExerciseEntry (id TEXT NOT NULL PRIMARY KEY, activityId TEXT NOT NULL, name TEXT NOT NULL, sets INTEGER, reps INTEGER, loadKg REAL, FOREIGN KEY(activityId) REFERENCES ActivityEntry(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_ExerciseEntry_activityId ON ExerciseEntry(activityId)")
        db.execSQL("CREATE TABLE MedicationPrn (id TEXT NOT NULL PRIMARY KEY, medicationId TEXT NOT NULL, instant INTEGER NOT NULL, zoneId TEXT NOT NULL, offsetSeconds INTEGER NOT NULL, labelSnapshot TEXT NOT NULL, notes TEXT NOT NULL, operationKey TEXT NOT NULL, source TEXT NOT NULL, confirmation TEXT NOT NULL, processing TEXT NOT NULL, confidence REAL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(medicationId) REFERENCES Medication(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_MedicationPrn_medicationId ON MedicationPrn(medicationId)")
        db.execSQL("CREATE UNIQUE INDEX index_MedicationPrn_operationKey ON MedicationPrn(operationKey)")
        db.execSQL("CREATE TABLE ReminderDelivery (occurrenceId TEXT NOT NULL PRIMARY KEY, habitOccurrenceId TEXT, medicationOccurrenceId TEXT, dueAt INTEGER NOT NULL, deliveredFor INTEGER, FOREIGN KEY(habitOccurrenceId) REFERENCES HabitOccurrence(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(medicationOccurrenceId) REFERENCES MedicationOccurrence(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_ReminderDelivery_habitOccurrenceId ON ReminderDelivery(habitOccurrenceId)")
        db.execSQL("CREATE UNIQUE INDEX index_ReminderDelivery_medicationOccurrenceId ON ReminderDelivery(medicationOccurrenceId)")
        db.execSQL("CREATE INDEX index_ReminderDelivery_dueAt ON ReminderDelivery(dueAt)")
        db.execSQL("CREATE TABLE ReminderSettings (id TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL)")
    }
}
