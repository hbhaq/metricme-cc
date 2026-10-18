package com.hbhaq.metricme.domain

import java.time.*

enum class RoutineKind { HABIT, MEDICATION }
enum class OccurrenceStatus { NOT_RECORDED, COMPLETED, TAKEN, SKIPPED, SNOOZED, CANCELLED }
enum class OccurrenceAction { DONE, SKIP, SNOOZE, UNDO }
data class RoutineInput(val id: String?, val kind: RoutineKind, val name: String, val instructions: String, val dose: String, val notes: String, val weekdays: Set<DayOfWeek>, val times: List<LocalTime>, val start: LocalDate, val lastDay: LocalDate?, val asNeeded: Boolean)
data class RecurrenceVersion(val id: String, val start: Instant, val end: Instant?, val zone: String, val time: LocalTime, val weekdays: Set<DayOfWeek>)
data class PlannedOccurrence(val id: String, val instant: Instant, val offsetSeconds: Int, val shifted: Boolean)
object RoutineRules {
    fun validate(input: RoutineInput, today: LocalDate) {
        require(input.name.isNotBlank() && input.name.length <= 120) { "Enter a name of 1–120 characters." }
        require(input.instructions.length <= 1000 && input.dose.length <= 120 && input.notes.length <= 2000) { "Shorten the instructions, dose text or notes." }
        require(input.start >= today.minusYears(1) && input.start <= today.plusYears(1)) { "Choose a start within one year of today." }
        require(input.lastDay == null || (input.lastDay >= input.start && input.lastDay <= input.start.plusYears(5))) { "Last day must be on or after the start, within five years." }
        require(!input.asNeeded || input.kind == RoutineKind.MEDICATION) { "As-needed applies only to medications." }
        if (!input.asNeeded) require(input.weekdays.isNotEmpty() && input.times.isNotEmpty() && input.times.size <= 8 && input.times.distinct().size == input.times.size) { "Choose weekdays and 1–8 distinct reminder times." }
    }
    fun plan(version: RecurrenceVersion, first: LocalDate, last: LocalDate): List<PlannedOccurrence> {
        require(!last.isBefore(first) && Duration.between(first.atStartOfDay(ZoneOffset.UTC), last.plusDays(1).atStartOfDay(ZoneOffset.UTC)).toDays() <= 400) { "Planning range must be 1–400 days." }
        val zone = ZoneId.of(version.zone)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.filter { it.dayOfWeek in version.weekdays }.mapNotNull { date ->
            val local = date.atTime(version.time)
            // For reminders only: gap shifts forward; overlap uses the earlier offset, once.
            val zoned = local.atZone(zone).withEarlierOffsetAtOverlap()
            val instant = zoned.toInstant()
            if (instant < version.start || (version.end != null && instant >= version.end)) null
            else PlannedOccurrence(LogRules.stableId("${version.id}|$instant|${zoned.offset.totalSeconds}"), instant, zoned.offset.totalSeconds, zoned.toLocalDateTime() != local)
        }.distinctBy { it.id }.toList()
    }
    fun transition(kind: RoutineKind, status: OccurrenceStatus, action: OccurrenceAction): OccurrenceStatus {
        if (status == OccurrenceStatus.CANCELLED) return status
        if (action == OccurrenceAction.UNDO) return OccurrenceStatus.NOT_RECORDED
        if (status in setOf(OccurrenceStatus.TAKEN, OccurrenceStatus.COMPLETED, OccurrenceStatus.SKIPPED)) return status
        return when (action) {
            OccurrenceAction.DONE -> if (kind == RoutineKind.MEDICATION) OccurrenceStatus.TAKEN else OccurrenceStatus.COMPLETED
            OccurrenceAction.SKIP -> OccurrenceStatus.SKIPPED
            OccurrenceAction.SNOOZE -> { require(kind == RoutineKind.MEDICATION) { "Only medication occurrences can be snoozed." }; OccurrenceStatus.SNOOZED }
            OccurrenceAction.UNDO -> OccurrenceStatus.NOT_RECORDED
        }
    }
    fun deliverable(status: OccurrenceStatus, due: Instant, now: Instant, alreadyDelivered: Boolean): Boolean =
        !alreadyDelivered && status in setOf(OccurrenceStatus.NOT_RECORDED, OccurrenceStatus.SNOOZED) && now >= due && now <= due.plus(Duration.ofHours(2))
}
