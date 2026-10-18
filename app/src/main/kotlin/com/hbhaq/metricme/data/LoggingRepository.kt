package com.hbhaq.metricme.data

import com.hbhaq.metricme.domain.*
import java.time.*
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

data class JournalItem(val id: String, val kind: LogKind, val title: String, val instant: Instant, val zone: String, val date: LocalDate, val detail: String, val input: ManualLog, val order: Long = 0, val source: String = "MANUAL")
data class LogBook(val items: List<JournalItem> = emptyList()) {
    fun on(date: LocalDate) = items.filter { it.date == date }.sortedByDescending { it.instant }
    fun calories(date: LocalDate): Double? = LogRules.loggedCalories(on(date).mapNotNull { it.input as? FoodLog })
    fun steps(date: LocalDate): StepTotal {
        val rows = on(date)
        val daily = rows.filter { it.input is StepsLog }.maxWithOrNull(compareBy<JournalItem> { it.instant }.thenBy { it.order }.thenBy { it.id })?.input as? StepsLog
        val walks = rows.mapNotNull { row -> (row.input as? ActivityLog)?.let { a -> a.steps?.let { WalkSteps(row.id, a.time.instant, a.time.instant.plusSeconds(((a.minutes ?: 0.0) * 60).toLong()), it) } } }
        return LogRules.steps(daily?.steps, walks)
    }
    fun sleepMinutes(date: LocalDate): Long? = LogRules.sleepMinutes(on(date).mapNotNull { (it.input as? SleepLog)?.let { s -> s.bed.instant to s.wake.instant } })
}
class LoggingRepository @Inject constructor(private val db: MetricMeDatabase, private val mutations: LocalMutations, private val clock: Clock) {
    private val dao get() = db.loggingDao()
    val book = combine(dao.foods(), dao.activities(), dao.steps(), dao.sleeps()) { foods, activities, steps, sleeps ->
        val items = mutableListOf<JournalItem>()
        foods.forEach { (e, parts) ->
            val p = parts.singleOrNull() ?: return@forEach
            val t = EventTime(Instant.ofEpochMilli(e.instant), e.zoneId, e.offsetSeconds)
            val input = FoodLog(e.id, t, p.name, e.mealType, p.quantity, p.unit, e.portionConsumed, p.calories, p.proteinGrams, p.carbsGrams, p.fatGrams)
            items += JournalItem(e.id, LogKind.FOOD, p.name, t.instant, t.zoneId, LocalDate.parse(e.localDate), "${e.mealType} · ${p.quantity} ${p.unit} · ${(e.portionConsumed * 100).toInt()}% consumed", input, source = e.stamp.source)
        }
        activities.forEach { (e, exercises) ->
            val t = EventTime(Instant.ofEpochMilli(e.instant), e.zoneId, e.offsetSeconds)
            val input = ActivityLog(e.id, t, e.type, e.durationSeconds?.div(60.0), e.reportedSteps, e.intensity, e.notes, exercises.map { Exercise(it.name, it.sets, it.reps, it.loadKg) })
            items += JournalItem(e.id, LogKind.ACTIVITY, e.type, t.instant, t.zoneId, LocalDate.parse(e.localDate), listOfNotNull(e.durationSeconds?.let { "${it / 60} min" }, e.reportedSteps?.let { "$it reported steps" }, e.intensity, exercises.takeIf { it.isNotEmpty() }?.let { "${it.size} exercises" }).joinToString(" · ").ifEmpty { "Duration and steps not recorded" }, input, source = e.stamp.source)
        }
        steps.forEach { e ->
            val t = EventTime(Instant.ofEpochMilli(e.instant), e.zoneId, e.offsetSeconds)
            items += JournalItem(e.id, LogKind.STEPS, "${e.steps} daily steps", t.instant, t.zoneId, LocalDate.parse(e.localDate), "Daily total replaces activity step subtotals", StepsLog(e.id, t, e.steps), e.stamp.createdAt)
        }
        sleeps.forEach { e ->
            val bed = Instant.ofEpochMilli(e.bedtime); val wake = Instant.ofEpochMilli(e.wakeTime)
            val input = SleepLog(e.id, EventTime(bed, e.bedtimeZone, ZoneId.of(e.bedtimeZone).rules.getOffset(bed).totalSeconds), EventTime(wake, e.wakeZone, ZoneId.of(e.wakeZone).rules.getOffset(wake).totalSeconds), e.quality, false)
            val mins = Duration.between(bed, wake).toMinutes()
            items += JournalItem(e.id, LogKind.SLEEP, "${mins / 60}h ${mins % 60}m sleep", wake, e.wakeZone, LocalDate.parse(e.wakeDate), e.quality ?: "Quality not recorded · assigned to wake-up day", input)
        }
        LogBook(items.sortedByDescending { it.instant })
    }
    suspend fun save(input: ManualLog, key: String): String = mutations.execute(key, "log:$input") {
        LogRules.validate(input, clock.instant())
        val id = input.id ?: UUID.randomUUID().toString()
        val now = clock.millis()
        when (input) {
            is FoodLog -> {
                val old = dao.food(id); require(input.id == null || old != null) { "This entry was deleted. Close and reopen the form." }
                val t = input.time
                dao.food(FoodEntity(id, "local", t.instant.toEpochMilli(), t.zoneId, t.offsetSeconds, t.date.toString(), input.meal, input.portion, old?.operationKey ?: key, Stamp(createdAt = old?.stamp?.createdAt ?: now, updatedAt = now)))
                dao.deleteComponents(id)
                dao.components(listOf(FoodComponentEntity(UUID.randomUUID().toString(), id, input.name.trim(), input.quantity, input.unit.trim(), input.calories, input.protein, input.carbs, input.fat)))
            }
            is ActivityLog -> {
                val old = dao.activity(id); require(input.id == null || old != null) { "This entry was deleted. Close and reopen the form." }
                val t = input.time
                dao.activity(ActivityEntity(id, "local", t.instant.toEpochMilli(), t.zoneId, t.offsetSeconds, t.date.toString(), input.name.trim(), input.minutes?.let { (it * 60).toLong() }, input.steps, input.intensity, input.notes.trim(), old?.operationKey ?: key, Stamp(createdAt = old?.stamp?.createdAt ?: now, updatedAt = now)))
                dao.deleteExercises(id); dao.exercises(input.exercises.map { ExerciseEntity(UUID.randomUUID().toString(), id, it.name.trim(), it.sets, it.reps, it.loadKg) })
            }
            is StepsLog -> {
                val old = dao.step(id); require(input.id == null || old != null) { "This entry was deleted. Close and reopen the form." }
                val t = input.time
                dao.step(StepEntity(id, "local", t.instant.toEpochMilli(), t.zoneId, t.offsetSeconds, t.date.toString(), input.steps, true, old?.operationKey ?: key, Stamp(createdAt = old?.stamp?.createdAt ?: now, updatedAt = now)))
            }
            is SleepLog -> {
                val old = dao.sleep(id); require(input.id == null || old != null) { "This entry was deleted. Close and reopen the form." }
                require(input.overlapConfirmed || dao.overlaps(id, input.bed.instant.toEpochMilli(), input.wake.instant.toEpochMilli()).isEmpty()) { "This overlaps another sleep session. Check the overlap confirmation before saving; daily duration counts overlapping minutes once." }
                dao.sleep(SleepEntity(id, "local", input.bed.instant.toEpochMilli(), input.wake.instant.toEpochMilli(), input.bed.zoneId, input.wake.zoneId, input.wake.date.toString(), input.quality, old?.operationKey ?: key, Stamp(createdAt = old?.stamp?.createdAt ?: now, updatedAt = now)))
            }
        }
        id
    }
    suspend fun delete(id: String, kind: LogKind, key: String) = mutations.execute(key, "delete:$kind:$id") {
        when (kind) { LogKind.FOOD -> dao.deleteFood(id); LogKind.ACTIVITY -> dao.deleteActivity(id); LogKind.STEPS -> dao.deleteStep(id); LogKind.SLEEP -> dao.deleteSleep(id) }; id
    }
}
