package com.hbhaq.metricme

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MetricMeDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Asia/Karachi"))
    private lateinit var db: MetricMeDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, MetricMeDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun exportedV1SchemaMigratesAndPreservesProfileAndFood() = runBlocking {
        val name = "schema-validation-${UUID.randomUUID()}"
        migration.createDatabase(name, 1).apply {
            execSQL("INSERT INTO UserProfile VALUES ('local','Preserved',NULL,'',NULL,'SEDENTARY',0,'','',0,'MANUAL','CONFIRMED','COMPLETE',NULL,1,1)")
            execSQL("INSERT INTO FoodEntry VALUES ('food','local',1,'UTC',0,'2026-10-18','Other',1,'op','MANUAL','CONFIRMED','COMPLETE',NULL,1,1)")
            execSQL("INSERT INTO FoodComponent VALUES ('component','food','Original',1,'serving',100,2,20,1)")
            close()
        }
        migration.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).close()
        val disk = Room.databaseBuilder(context, MetricMeDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try { assertEquals("Preserved", disk.profileDao().profile()!!.name); assertEquals(100.0, disk.loggingDao().foods().first().single().components.single().calories!!, 0.0) } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun retryDoesNotDuplicateMeasurementAndDeleteLeavesTargetHistory() = runBlocking {
        val repo = ProfileRepository(db, clock)
        val key = UUID.randomUUID().toString()
        repeat(2) { repo.saveMeasurement(null, MeasurementKind.WEIGHT, 70.0, LocalDate.now(clock), Acquisition.MEASURED, false, key) }
        val dao = db.profileDao()
        assertEquals(1, dao.measurements().size)
        val snapshots = dao.targets().size
        repo.deleteMeasurement(dao.measurements().single().id, UUID.randomUUID().toString())
        assertTrue(dao.measurements().isEmpty())
        assertEquals(snapshots + 1, dao.targets().size)
    }

    @Test fun conflictingOperationKeyCannotChangeData() = runBlocking {
        val repo = ProfileRepository(db, clock)
        val key = UUID.randomUUID().toString()
        repo.saveMeasurement(null, MeasurementKind.WEIGHT, 70.0, LocalDate.now(clock), Acquisition.MEASURED, false, key)
        try {
            repo.saveMeasurement(null, MeasurementKind.WEIGHT, 71.0, LocalDate.now(clock), Acquisition.MEASURED, false, key)
            fail("Conflicting payload must be rejected")
        } catch (_: IllegalArgumentException) { assertEquals(70.0, db.profileDao().measurements().single().value, 0.0) }
    }

    @Test fun profileEditsKeepHistoryAndManualTargetsSurviveMeasurements() = runBlocking {
        val repo = ProfileRepository(db, clock)
        val p = Profile(name = "One", birthDate = LocalDate.parse("1990-01-01"), equationInput = EquationInput.PLUS_5, provenance = Provenance(createdAt = clock.instant()))
        repo.saveProfile(p, UUID.randomUUID().toString())
        repo.setManualTarget(2100.0, UUID.randomUUID().toString())
        repo.saveMeasurement(null, MeasurementKind.HEIGHT, 175.0, LocalDate.now(clock), Acquisition.MEASURED, false, UUID.randomUUID().toString())
        repo.saveProfile(p.copy(name = "Two"), UUID.randomUUID().toString())
        assertEquals(1, db.profileDao().measurements().size)
        assertEquals(2100.0, db.profileDao().targets().last().calories!!, 0.0)
    }
}
