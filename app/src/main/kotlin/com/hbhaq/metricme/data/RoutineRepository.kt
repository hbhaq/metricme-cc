package com.hbhaq.metricme.data

import androidx.room.withTransaction
import com.hbhaq.metricme.domain.*
import java.time.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine

data class RoutineCard(val id: String, val kind: RoutineKind, val name: String, val instructions: String, val dose: String, val notes: String, val weekdays: Set<DayOfWeek>, val times: List<LocalTime>, val asNeeded: Boolean, val archived: Boolean, val lastDay: LocalDate? = null)
data class OccurrenceCard(val id: String, val ownerId: String, val kind: RoutineKind, val label: String, val scheduled: Instant, val zone: String, val status: OccurrenceStatus, val snoozedUntil: Instant?)
data class RoutineBook(val definitions: List<RoutineCard> = emptyList(), val occurrences: List<OccurrenceCard> = emptyList(), val prn: List<PrnEntity> = emptyList(), val remindersEnabled: Boolean = false) {
    fun on(date: LocalDate) = occurrences.filter { it.scheduled.atZone(ZoneId.of(it.zone)).toLocalDate() == date && it.status != OccurrenceStatus.CANCELLED }.sortedBy { it.scheduled }
}
@Singleton
class RoutineRepository @Inject constructor(private val db: MetricMeDatabase, private val mutations: LocalMutations, private val clock: Clock) {
    private val dao get() = db.routineDao()
    private data class Definitions(val habits: List<HabitEntity>, val meds: List<MedicationEntity>, val schedules: List<ScheduleEntity>, val versions: List<ScheduleVersionEntity>)
    private val definitions = combine(dao.observeHabits(), dao.observeMedications(), dao.observeSchedules(), dao.observeVersions()) { h, m, s, v -> Definitions(h, m, s, v) }
    private data class Occurrences(val habits: List<HabitOccurrenceEntity>, val meds: List<MedicationOccurrenceEntity>, val prn: List<PrnEntity>)
    private val occurrences = combine(dao.observeHabitOccurrences(), dao.observeMedicationOccurrences(), dao.observePrn()) { h, m, p -> Occurrences(h, m, p) }
    val book = combine(definitions, occurrences, dao.observeSettings()) { defs, occs, settings ->
        fun schedules(id: String) = defs.schedules.filter { (it.habitId == id || it.medicationId == id) && it.active }
        fun lastDay(id: String): LocalDate? = defs.versions.filter { v -> v.scheduleId in schedules(id).map { it.id } }.maxByOrNull { it.effectiveFrom }?.let { v -> v.effectiveUntil?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(v.zoneId)).toLocalDate().minusDays(1) } }
        val cards = defs.habits.map { h -> RoutineCard(h.id, RoutineKind.HABIT, h.name, "", "", h.notes, days(h.selectedWeekdays), schedules(h.id).map { LocalTime.parse(it.wallTime) }, false, h.archived, lastDay(h.id)) } +
            defs.meds.map { m -> RoutineCard(m.id, RoutineKind.MEDICATION, m.name, m.instructions, m.doseText, m.notes, schedules(m.id).firstOrNull()?.let { days(it.weekdays) }.orEmpty(), schedules(m.id).map { LocalTime.parse(it.wallTime) }, m.asNeeded, m.endedAt?.let { it <= clock.millis() } == true, lastDay(m.id) ?: m.endedAt?.let { Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDate().minusDays(1) }) }
        val stableHabits = occs.habits.map { h -> OccurrenceCard(h.id, h.habitId, RoutineKind.HABIT, h.labelSnapshot.ifEmpty { defs.habits.firstOrNull { it.id == h.habitId }?.name ?: "Habit" }, Instant.ofEpochMilli(h.scheduledAt), ZoneOffset.ofTotalSeconds(h.offsetSeconds).id, OccurrenceStatus.valueOf(h.status), null) }
        val medCards = occs.meds.map { m -> OccurrenceCard(m.id, m.medicationId, RoutineKind.MEDICATION, m.labelSnapshot.ifEmpty { defs.meds.firstOrNull { it.id == m.medicationId }?.let { "${it.name} · ${it.doseText}" } ?: "Medication" }, Instant.ofEpochMilli(m.scheduledAt), ZoneOffset.ofTotalSeconds(m.offsetSeconds).id, OccurrenceStatus.valueOf(m.status), m.snoozedUntil?.let(Instant::ofEpochMilli)) }
        RoutineBook(cards, stableHabits + medCards, occs.prn, settings?.enabled == true)
    }
    suspend fun save(input: RoutineInput, key: String): String = mutations.execute(key, "routine:$input") {
        RoutineRules.validate(input, LocalDate.now(clock))
        val id = input.id ?: UUID.randomUUID().toString(); val now = clock.millis(); val zone = clock.zone
        val oldH = dao.habits().firstOrNull { it.id == id }; val oldM = dao.medications().firstOrNull { it.id == id }
        require(input.id == null || oldH != null || oldM != null) { "This routine no longer exists." }
        require(input.id == null || (input.kind == RoutineKind.HABIT) == (oldH != null)) { "A routine's type cannot be changed." }
        closeSchedules(id, now)
        val stamp = Stamp(createdAt = oldH?.stamp?.createdAt ?: oldM?.stamp?.createdAt ?: now, updatedAt = now)
        val lastInstant = input.lastDay?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        if (input.kind == RoutineKind.HABIT) dao.habit(HabitEntity(id, "local", input.name.trim(), encodeDays(input.weekdays), false, stamp, input.notes.trim()))
        else dao.medication(MedicationEntity(id, "local", input.name.trim(), input.instructions.trim(), input.dose.trim(), input.notes.trim(), input.asNeeded, lastInstant, stamp))
        if (!input.asNeeded) input.times.forEach { time ->
            val scheduleId = UUID.randomUUID().toString()
            dao.schedule(ScheduleEntity(scheduleId, "local", if (input.kind == RoutineKind.HABIT) id else null, if (input.kind == RoutineKind.MEDICATION) id else null, time.toString(), encodeDays(input.weekdays), zone.id, true))
            val start = maxOf(if (input.id == null) input.start.atStartOfDay(zone).toInstant() else clock.instant(), input.start.atStartOfDay(zone).toInstant())
            val label = if (input.kind == RoutineKind.HABIT) input.name.trim() else listOf(input.name.trim(), input.dose.trim(), input.instructions.trim()).filter { it.isNotBlank() }.joinToString(" · ")
            dao.version(ScheduleVersionEntity(UUID.randomUUID().toString(), scheduleId, start.toEpochMilli(), lastInstant, time.toString(), encodeDays(input.weekdays), zone.id, label))
        }
        generate(); id
    }
    suspend fun archive(id: String, kind: RoutineKind, key: String): String = mutations.execute(key, "archive:$kind:$id") {
        val now = clock.millis(); closeSchedules(id, now)
        if (kind == RoutineKind.HABIT) dao.habits().firstOrNull { it.id == id }?.let { dao.habit(it.copy(archived = true, stamp = it.stamp.copy(updatedAt = now))) }
        else dao.medications().firstOrNull { it.id == id }?.let { dao.medication(it.copy(endedAt = now, stamp = it.stamp.copy(updatedAt = now))) }
        id
    }
    private suspend fun closeSchedules(id: String, at: Long) {
        dao.schedules().filter { it.active && (it.habitId == id || it.medicationId == id) }.forEach { schedule ->
            dao.schedule(schedule.copy(active = false))
            dao.versions(schedule.id).filter { it.effectiveUntil == null || it.effectiveUntil > at }.forEach { dao.version(it.copy(effectiveUntil = maxOf(it.effectiveFrom, at))) }
        }
        dao.habitOccurrences().filter { it.habitId == id && it.scheduledAt >= at && it.status == "NOT_RECORDED" }.forEach { dao.updateHabitOccurrence(it.copy(status = OccurrenceStatus.CANCELLED.name, stamp = it.stamp.copy(updatedAt = at))) }
        dao.medicationOccurrences().filter { it.medicationId == id && it.scheduledAt >= at && it.status in listOf("NOT_RECORDED", "SNOOZED") }.forEach { dao.updateMedicationOccurrence(it.copy(status = OccurrenceStatus.CANCELLED.name, snoozedUntil = null, stamp = it.stamp.copy(updatedAt = at))) }
    }
    suspend fun reconcile() = db.withTransaction {
        val zone = clock.zone.id; val now = clock.millis()
        dao.schedules().filter { it.active && it.zoneId != zone }.forEach { s ->
            val versions = dao.versions(s.id)
            val current = versions.maxByOrNull { it.effectiveFrom }
            versions.filter { it.effectiveUntil == null || it.effectiveUntil > now }.forEach { dao.version(it.copy(effectiveUntil = maxOf(it.effectiveFrom, now))) }
            dao.habitOccurrences().filter { it.versionId in versions.map { v -> v.id } && it.scheduledAt >= now && it.status == "NOT_RECORDED" }.forEach { dao.updateHabitOccurrence(it.copy(status = "CANCELLED")) }
            dao.medicationOccurrences().filter { it.versionId in versions.map { v -> v.id } && it.scheduledAt >= now && it.status in listOf("NOT_RECORDED", "SNOOZED") }.forEach { dao.updateMedicationOccurrence(it.copy(status = "CANCELLED", snoozedUntil = null)) }
            dao.schedule(s.copy(zoneId = zone))
            if (current != null && (current.effectiveUntil == null || current.effectiveUntil > now)) {
                val oldZone = ZoneId.of(current.zoneId); val newZone = ZoneId.of(zone)
                val start = if (current.effectiveFrom > now) Instant.ofEpochMilli(current.effectiveFrom).atZone(oldZone).toLocalDate().atStartOfDay(newZone).toInstant().toEpochMilli() else now
                val end = current.effectiveUntil?.let { Instant.ofEpochMilli(it).atZone(oldZone).toLocalDate().atStartOfDay(newZone).toInstant().toEpochMilli() }
                dao.version(current.copy(id = UUID.randomUUID().toString(), effectiveFrom = maxOf(start, now), effectiveUntil = end, zoneId = zone))
                s.medicationId?.let { id -> dao.medications().firstOrNull { it.id == id }?.let { dao.medication(it.copy(endedAt = end)) } }
            }
        }
        generate()
    }
    private suspend fun generate() {
        val now = clock.millis(); val habits = dao.habits().associateBy { it.id }; val meds = dao.medications().associateBy { it.id }
        dao.schedules().forEach { schedule ->
            dao.versions(schedule.id).forEach { v ->
                val first = Instant.ofEpochMilli(v.effectiveFrom).atZone(ZoneId.of(v.zoneId)).toLocalDate().coerceAtLeast(LocalDate.now(clock).minusDays(365))
                val last = LocalDate.now(clock).plusDays(14)
                if (first > last) return@forEach
                val version = RecurrenceVersion(v.id, Instant.ofEpochMilli(v.effectiveFrom), v.effectiveUntil?.let(Instant::ofEpochMilli), v.zoneId, LocalTime.parse(v.wallTime), days(v.weekdays))
                RoutineRules.plan(version, first, last).forEach { occurrence ->
                    val stamp = Stamp(createdAt = now)
                    val h = schedule.habitId?.let { habits[it] }; val m = schedule.medicationId?.let { meds[it] }
                    if (h != null) dao.insertHabitOccurrence(HabitOccurrenceEntity(occurrence.id, h.id, v.id, occurrence.instant.toEpochMilli(), occurrence.offsetSeconds, "NOT_RECORDED", stamp, v.labelSnapshot.ifEmpty { h.name }))
                    if (m != null) dao.insertMedicationOccurrence(MedicationOccurrenceEntity(occurrence.id, m.id, v.id, occurrence.instant.toEpochMilli(), occurrence.offsetSeconds, "NOT_RECORDED", null, stamp, v.labelSnapshot.ifEmpty { listOf(m.name, m.doseText, m.instructions).filter { it.isNotBlank() }.joinToString(" · ") }))
                    dao.insertDelivery(DeliveryEntity(occurrence.id, if (h != null) occurrence.id else null, if (m != null) occurrence.id else null, occurrence.instant.toEpochMilli()))
                }
            }
        }
    }
    suspend fun action(id: String, action: OccurrenceAction, key: String, expectedDue: Long? = null): String = mutations.execute(key, "action:$id:$action:$expectedDue") {
        val h = dao.habitOccurrence(id); val m = dao.medicationOccurrence(id); val delivery = dao.delivery(id)
        if (expectedDue != null && (delivery?.dueAt != expectedDue || dao.settings()?.enabled != true || !canRemind(id))) return@execute id
        val kind = if (h != null) RoutineKind.HABIT else RoutineKind.MEDICATION
        val status = (h?.status ?: m?.status)?.let(OccurrenceStatus::valueOf) ?: return@execute id
        if (action == OccurrenceAction.SNOOZE) require(canRemind(id)) { "This schedule has ended or changed. It cannot create a new reminder; recorded history is still available." }
        val next = RoutineRules.transition(kind, status, action)
        if (next == status && action != OccurrenceAction.SNOOZE) return@execute id
        val now = clock.millis(); val snooze = if (next == OccurrenceStatus.SNOOZED) maxOf(now, m?.scheduledAt ?: now) + Duration.ofMinutes(10).toMillis() else null
        if (h != null) dao.updateHabitOccurrence(h.copy(status = next.name, stamp = h.stamp.copy(updatedAt = now)))
        if (m != null) dao.updateMedicationOccurrence(m.copy(status = next.name, snoozedUntil = snooze, stamp = m.stamp.copy(updatedAt = now)))
        if (delivery != null && snooze != null) dao.updateDelivery(delivery.copy(dueAt = snooze, deliveredFor = null))
        if (delivery != null && action == OccurrenceAction.UNDO) dao.updateDelivery(delivery.copy(dueAt = h?.scheduledAt ?: m!!.scheduledAt, deliveredFor = h?.scheduledAt ?: m!!.scheduledAt))
        id
    }
    suspend fun logPrn(id: String, time: EventTime, notes: String, key: String): String = mutations.execute(key, "prn:$id:$time:$notes") {
        val med = dao.medications().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Medication not found.")
        require(med.asNeeded && (med.endedAt == null || time.instant.toEpochMilli() < med.endedAt)) { "This is not an active as-needed medication." }
        require(time.instant <= clock.instant() && notes.length <= 2000) { "Check the event time and notes." }
        val result = UUID.randomUUID().toString()
        dao.prn(PrnEntity(result, id, time.instant.toEpochMilli(), time.zoneId, time.offsetSeconds, listOf(med.name, med.doseText).filter { it.isNotBlank() }.joinToString(" · "), notes, key, Stamp(createdAt = clock.millis()))); result
    }
    suspend fun deletePrn(id: String, key: String) = mutations.execute(key, "delete-prn:$id") { dao.deletePrn(id); id }
    suspend fun setReminders(enabled: Boolean) { dao.settings(ReminderSettingsEntity(enabled = enabled)) }
    suspend fun enabled() = dao.settings()?.enabled == true
    suspend fun deliveries() = dao.deliveries()
    suspend fun occurrence(id: String): Pair<RoutineKind, OccurrenceStatus>? {
        dao.habitOccurrence(id)?.let { return RoutineKind.HABIT to OccurrenceStatus.valueOf(it.status) }
        return dao.medicationOccurrence(id)?.let { RoutineKind.MEDICATION to OccurrenceStatus.valueOf(it.status) }
    }
    suspend fun canRemind(id: String): Boolean {
        val h = dao.habitOccurrence(id); val m = dao.medicationOccurrence(id)
        if (h != null && dao.habits().firstOrNull { it.id == h.habitId }?.archived != false) return false
        if (m != null) {
            val med = dao.medications().firstOrNull { it.id == m.medicationId } ?: return false
            if (med.endedAt != null && med.endedAt <= clock.millis()) return false
        }
        val versionId = h?.versionId ?: m?.versionId ?: return false
        return dao.schedules().filter { it.active }.any { s -> dao.versions(s.id).any { v -> v.id == versionId && (v.effectiveUntil == null || v.effectiveUntil > clock.millis()) } }
    }
    suspend fun claim(id: String, due: Long): Boolean = db.withTransaction {
        val state = occurrence(id) ?: return@withTransaction false
        val delivery = dao.delivery(id) ?: return@withTransaction false
        if (!enabled() || !canRemind(id) || delivery.dueAt != due || !RoutineRules.deliverable(state.second, Instant.ofEpochMilli(due), clock.instant(), delivery.deliveredFor == due)) return@withTransaction false
        dao.claim(id, due) == 1
    }
    companion object {
        fun days(value: String): Set<DayOfWeek> = value.split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet()
        fun encodeDays(value: Set<DayOfWeek>): String = value.sortedBy { it.value }.joinToString(",") { it.value.toString() }
    }
}
