package com.hbhaq.metricme.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RoutineRulesTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private fun version(time: String, zone: String = "Europe/London") = RecurrenceVersion("version", start, null, zone, LocalTime.parse(time), DayOfWeek.entries.toSet())
    @Test fun planningIsStableAndDuplicateFree() {
        val date = LocalDate.parse("2026-10-18")
        val first = RoutineRules.plan(version("09:00"), date, date.plusDays(6))
        assertEquals(7, first.size); assertEquals(first, RoutineRules.plan(version("09:00"), date, date.plusDays(6)))
        assertEquals(7, first.map { it.id }.distinct().size)
    }
    @Test fun selectedWeekdaysAndEndExclusiveAreRespected() {
        val monday = LocalDate.parse("2026-10-19")
        val v = version("23:50", "Asia/Karachi").copy(weekdays = setOf(DayOfWeek.MONDAY), end = monday.plusDays(7).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant())
        assertEquals(1, RoutineRules.plan(v, monday, monday.plusDays(8)).size)
    }
    @Test fun remindersShiftDstGapAndRepeatOnlyOnceInOverlap() {
        val gap = LocalDate.parse("2026-03-29")
        val result = RoutineRules.plan(version("01:30"), gap, gap).single()
        assertTrue(result.shifted); assertEquals(LocalTime.parse("02:30"), result.instant.atZone(ZoneId.of("Europe/London")).toLocalTime())
        val overlap = LocalDate.parse("2026-10-25")
        val repeated = RoutineRules.plan(version("01:30"), overlap, overlap).single()
        assertEquals(3600, repeated.offsetSeconds)
    }
    @Test fun changedZoneProducesOneNewVersionAndStableOldHistory() {
        val day = LocalDate.parse("2026-10-18")
        val old = RoutineRules.plan(version("09:00", "Asia/Karachi"), day, day).single()
        val changed = RoutineRules.plan(version("09:00", "Europe/London").copy(id = "new-version"), day, day).single()
        assertNotEquals(old.id, changed.id); assertNotEquals(old.instant, changed.instant)
    }
    @Test fun missingNotificationDoesNotChangeDoseState() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        assertTrue(RoutineRules.deliverable(OccurrenceStatus.NOT_RECORDED, now, now, false))
        assertFalse(RoutineRules.deliverable(OccurrenceStatus.NOT_RECORDED, now, now.plusSeconds(7201), false))
        assertFalse(RoutineRules.deliverable(OccurrenceStatus.NOT_RECORDED, now, now, true))
        assertFalse(RoutineRules.deliverable(OccurrenceStatus.TAKEN, now, now, false))
    }
    @Test fun conflictingNotificationActionsCannotOverwriteTakenDose() {
        val taken = RoutineRules.transition(RoutineKind.MEDICATION, OccurrenceStatus.NOT_RECORDED, OccurrenceAction.DONE)
        assertEquals(OccurrenceStatus.TAKEN, taken)
        assertEquals(taken, RoutineRules.transition(RoutineKind.MEDICATION, taken, OccurrenceAction.SKIP))
        assertEquals(OccurrenceStatus.NOT_RECORDED, RoutineRules.transition(RoutineKind.MEDICATION, taken, OccurrenceAction.UNDO))
    }
    @Test fun snoozeAndHabitCompletionHaveDistinctStates() {
        assertEquals(OccurrenceStatus.SNOOZED, RoutineRules.transition(RoutineKind.MEDICATION, OccurrenceStatus.NOT_RECORDED, OccurrenceAction.SNOOZE))
        assertEquals(OccurrenceStatus.COMPLETED, RoutineRules.transition(RoutineKind.HABIT, OccurrenceStatus.NOT_RECORDED, OccurrenceAction.DONE))
        assertEquals(OccurrenceStatus.CANCELLED, RoutineRules.transition(RoutineKind.MEDICATION, OccurrenceStatus.CANCELLED, OccurrenceAction.DONE))
    }
    @Test fun prnNeedsNoScheduledOccurrences() {
        val today = LocalDate.parse("2026-10-18")
        RoutineRules.validate(RoutineInput(null, RoutineKind.MEDICATION, "My medication", "Follow supplied instructions", "as directed", "", emptySet(), emptyList(), today, null, true), today)
        assertThrows(IllegalArgumentException::class.java) { RoutineRules.validate(RoutineInput(null, RoutineKind.HABIT, "Habit", "", "", "", emptySet(), emptyList(), today, null, false), today) }
    }
}
