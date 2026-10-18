package com.hbhaq.metricme

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoggingDatabaseTest {
    private lateinit var db: MetricMeDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Asia/Karachi"))
    private lateinit var logs: LoggingRepository
    private lateinit var routines: RoutineRepository
    private fun key() = UUID.randomUUID().toString()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, MetricMeDatabase::class.java).build()
        val mutations = LocalMutations(db, clock); logs = LoggingRepository(db, mutations, clock); routines = RoutineRepository(db, mutations, clock)
    }
    @After fun close() = db.close()
    @Test fun allManualLogsPersistEditDeleteAndKeepUnknownValues() = runBlocking {
        val t = EventTime(clock.instant(), clock.zone.id, 18000)
        val food = FoodLog(null, t, "Toast", "Breakfast", 2.0, "slices", 0.5, 200.0, null, 30.0, null)
        val id = logs.save(food, key())
        val activity = ActivityLog(null, t, "Walk", 20.0, 2000, "easy", "", listOf(Exercise("Carry", 2, 10, null)))
        logs.save(activity, key()); logs.save(StepsLog(null, t, 5000), key())
        val bed = t.copy(instant = clock.instant().minusSeconds(8 * 3600)); logs.save(SleepLog(null, bed, t, null, false), key())
        var book = logs.book.first()
        assertEquals(4, book.items.size); assertEquals(100.0, book.calories(t.date)!!, 0.0); assertEquals(5000, book.steps(t.date).count)
        assertEquals(480L, book.sleepMinutes(t.date)); assertNull((book.items.first { it.kind == LogKind.FOOD }.input as FoodLog).protein)
        logs.save(food.copy(id = id, name = "Edited toast"), key()); assertEquals(1, logs.book.first().items.count { it.kind == LogKind.FOOD })
        logs.delete(id, LogKind.FOOD, key()); book = logs.book.first(); assertEquals(3, book.items.size); assertNull(book.calories(t.date))
    }
    @Test fun concurrentRetriesCreateOnlyOneConfirmedFood() = runBlocking {
        val food = FoodLog(null, EventTime(clock.instant(), clock.zone.id, 18000), "Tea", "Snack", 1.0, "cup", 1.0, 0.0, null, null, null)
        val key = key()
        coroutineScope { List(4) { async(Dispatchers.IO) { logs.save(food, key) } }.awaitAll() }
        assertEquals(1, logs.book.first().items.size)
    }
    @Test fun overlappingSleepRequiresConfirmationThenCountsUnion() = runBlocking {
        val wake = EventTime(clock.instant(), clock.zone.id, 18000)
        logs.save(SleepLog(null, wake.copy(instant = wake.instant.minusSeconds(7200)), wake, null, false), key())
        try { logs.save(SleepLog(null, wake.copy(instant = wake.instant.minusSeconds(3600)), wake, null, false), key()); fail("Overlap must be reviewed") } catch (_: IllegalArgumentException) { }
        logs.save(SleepLog(null, wake.copy(instant = wake.instant.minusSeconds(3600)), wake, null, true), key())
        assertEquals(120L, logs.book.first().sleepMinutes(wake.date))
    }
    private fun routine(kind: RoutineKind = RoutineKind.MEDICATION, prn: Boolean = false) = RoutineInput(null, kind, "Test routine", "Existing instructions", "as directed", "", DayOfWeek.entries.toSet(), if (prn) emptyList() else listOf(LocalTime.parse("09:00"), LocalTime.parse("21:00")), LocalDate.now(clock), null, prn)
    @Test fun recurrenceReconciliationAndActionsAreIdempotent() = runBlocking {
        routines.save(routine(), key()); repeat(2) { routines.reconcile() }
        val dao = db.routineDao(); assertEquals(30, dao.medicationOccurrences().size)
        val due = dao.medicationOccurrences().minBy { it.scheduledAt }
        val op = key(); repeat(2) { routines.action(due.id, OccurrenceAction.DONE, op) }
        routines.action(due.id, OccurrenceAction.SKIP, key())
        assertEquals("TAKEN", dao.medicationOccurrence(due.id)!!.status)
        routines.action(due.id, OccurrenceAction.UNDO, key()); assertEquals("NOT_RECORDED", dao.medicationOccurrence(due.id)!!.status)
    }
    @Test fun scheduleEditPreservesPastAndCancelsOnlyFuturePending() = runBlocking {
        val id = routines.save(routine(), key()); val dao = db.routineDao()
        val past = dao.medicationOccurrences().first { it.scheduledAt < clock.millis() }
        routines.action(past.id, OccurrenceAction.DONE, key())
        routines.save(routine().copy(id = id, times = listOf(LocalTime.parse("22:00")), dose = "updated text"), key())
        assertEquals("TAKEN", dao.medicationOccurrence(past.id)!!.status)
        assertTrue(dao.medicationOccurrence(past.id)!!.labelSnapshot.contains("as directed"))
        assertTrue(dao.medicationOccurrences().any { it.scheduledAt > clock.millis() && it.status == "CANCELLED" })
        routines.archive(id, RoutineKind.MEDICATION, key()); assertFalse(dao.medicationOccurrences().any { it.scheduledAt > clock.millis() && it.status == "NOT_RECORDED" })
    }
    @Test fun snoozeKeepsOccurrenceIdentityAndPrnStaysOutsideSchedule() = runBlocking {
        routines.save(routine(), key()); val dao = db.routineDao(); val first = dao.medicationOccurrences().minBy { it.scheduledAt }
        routines.action(first.id, OccurrenceAction.SNOOZE, key())
        val snoozed = dao.medicationOccurrence(first.id)!!
        assertEquals("SNOOZED", snoozed.status); assertEquals(clock.millis() + 600000, snoozed.snoozedUntil)
        val count = dao.medicationOccurrences().size
        val prn = routines.save(routine(prn = true).copy(name = "As needed"), key())
        routines.logPrn(prn, EventTime(clock.instant(), clock.zone.id, 18000), "", key())
        assertEquals(count, dao.medicationOccurrences().size); assertEquals(1, routines.book.first().prn.size)
    }
    @Test fun deliveryClaimsAndStaleActionsAreSafe() = runBlocking {
        routines.save(routine(), key()); routines.setReminders(true)
        val first = db.routineDao().medicationOccurrences().minBy { it.scheduledAt }
        // 09:00 local is eight hours old: do not deliver an outdated alert or change dose state.
        assertFalse(routines.claim(first.id, first.scheduledAt)); assertEquals("NOT_RECORDED", db.routineDao().medicationOccurrence(first.id)!!.status)
        routines.action(first.id, OccurrenceAction.SNOOZE, key())
        routines.action(first.id, OccurrenceAction.DONE, key(), first.scheduledAt)
        assertEquals("SNOOZED", db.routineDao().medicationOccurrence(first.id)!!.status)
    }
    @Test fun duplicateDeliveryClaimsNeverRecordADoseAndArchiveBlocksLateAlerts() = runBlocking {
        val id = routines.save(routine().copy(times = listOf(LocalTime.parse("16:30"))), key()); routines.setReminders(true)
        val due = db.routineDao().medicationOccurrences().minBy { it.scheduledAt }
        val claims = coroutineScope { List(4) { async(Dispatchers.IO) { routines.claim(due.id, due.scheduledAt) } }.awaitAll() }
        assertEquals(1, claims.count { it }); assertEquals("NOT_RECORDED", db.routineDao().medicationOccurrence(due.id)!!.status)
        routines.archive(id, RoutineKind.MEDICATION, key()); assertFalse(routines.canRemind(due.id))
    }
    @Test fun timezoneChangePreservesHistoryAndRegeneratesFutureOnce() = runBlocking {
        var zone = ZoneId.of("Asia/Karachi")
        val dynamic = object : Clock() {
            override fun instant() = clock.instant()
            override fun getZone() = zone
            override fun withZone(value: ZoneId): Clock = Clock.fixed(instant(), value)
        }
        val repo = RoutineRepository(db, LocalMutations(db, dynamic), dynamic)
        val id = repo.save(routine().copy(times = listOf(LocalTime.parse("16:30"))), key())
        val dao = db.routineDao(); val past = dao.medicationOccurrences().first { it.medicationId == id && it.scheduledAt < clock.millis() }
        repo.action(past.id, OccurrenceAction.DONE, key())
        zone = ZoneId.of("Europe/London"); repeat(2) { repo.reconcile() }
        assertEquals("TAKEN", dao.medicationOccurrence(past.id)!!.status); assertEquals(18000, dao.medicationOccurrence(past.id)!!.offsetSeconds)
        val pending = dao.medicationOccurrences().filter { it.medicationId == id && it.status == "NOT_RECORDED" && it.scheduledAt > clock.millis() }
        assertTrue(pending.isNotEmpty()); assertTrue(pending.all { it.offsetSeconds in setOf(0, 3600) })
        val schedule = dao.schedules().single { it.medicationId == id && it.active }
        assertEquals(2, dao.versions(schedule.id).size)
    }
    @Test fun habitAndVitaminOccurrencesStaySeparateFromMedicationDoses() = runBlocking {
        val id = routines.save(routine(RoutineKind.HABIT).copy(name = "My vitamin check-in"), key())
        val dao = db.routineDao(); val due = dao.habitOccurrences().first { it.habitId == id && it.scheduledAt < clock.millis() }
        routines.action(due.id, OccurrenceAction.DONE, key())
        assertEquals("COMPLETED", dao.habitOccurrence(due.id)!!.status); assertTrue(dao.medicationOccurrences().isEmpty())
        routines.archive(id, RoutineKind.HABIT, key())
        assertEquals("COMPLETED", dao.habitOccurrence(due.id)!!.status)
        assertFalse(dao.habitOccurrences().any { it.habitId == id && it.scheduledAt > clock.millis() && it.status == "NOT_RECORDED" })
    }
}
