package com.hbhaq.metricme.domain

import java.time.*
import java.util.UUID

enum class LogKind(val label: String) { FOOD("Food"), ACTIVITY("Activity"), STEPS("Daily steps"), SLEEP("Sleep") }
data class EventTime(val instant: Instant, val zoneId: String, val offsetSeconds: Int) {
    val date: LocalDate get() = instant.atZone(ZoneId.of(zoneId)).toLocalDate()
}
object EventTimes {
    fun resolve(local: String, zone: String, offset: String = ""): EventTime {
        val datetime = try { LocalDateTime.parse(local.trim()) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter date and time as YYYY-MM-DDTHH:mm.") }
        val id = try { ZoneId.of(zone.trim()) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter an IANA timezone, such as Asia/Karachi.") }
        val offsets = id.rules.getValidOffsets(datetime)
        require(offsets.isNotEmpty()) { "This clock time did not occur because clocks moved forward. Choose a valid time." }
        val chosen = if (offset.isBlank()) {
            require(offsets.size == 1) { "This time occurred twice. Enter an explicit offset, such as +01:00 or +00:00." }
            offsets.single()
        } else {
            val candidate = try { ZoneOffset.of(offset.trim()) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter an offset such as +05:00.") }
            require(candidate in offsets) { "That offset does not apply to this local time and timezone." }; candidate
        }
        return EventTime(datetime.toInstant(chosen), id.id, chosen.totalSeconds)
    }
}
data class Exercise(val name: String, val sets: Int?, val reps: Int?, val loadKg: Double?)
sealed interface ManualLog { val id: String? }
data class FoodLog(override val id: String?, val time: EventTime, val name: String, val meal: String, val quantity: Double, val unit: String, val portion: Double, val calories: Double?, val protein: Double?, val carbs: Double?, val fat: Double?) : ManualLog {
    private fun consumed(value: Double?): Double? = if (portion == 0.0) 0.0 else value?.times(portion)
    val consumedCalories get() = consumed(calories)
    val consumedProtein get() = consumed(protein)
    val consumedCarbs get() = consumed(carbs)
    val consumedFat get() = consumed(fat)
}
data class ActivityLog(override val id: String?, val time: EventTime, val name: String, val minutes: Double?, val steps: Int?, val intensity: String?, val notes: String, val exercises: List<Exercise>) : ManualLog
data class StepsLog(override val id: String?, val time: EventTime, val steps: Int) : ManualLog
data class SleepLog(override val id: String?, val bed: EventTime, val wake: EventTime, val quality: String?, val overlapConfirmed: Boolean) : ManualLog
data class WalkSteps(val id: String, val start: Instant, val end: Instant, val steps: Int)
data class StepTotal(val count: Int?, val explanation: String)
object LogRules {
    fun loggedCalories(foods: List<FoodLog>): Double? = foods.mapNotNull { it.consumedCalories }.takeIf { it.isNotEmpty() }?.sum()
    fun validate(log: ManualLog, now: Instant) {
        fun time(t: EventTime) { require(t.instant <= now) { "Future entries cannot be logged." }; require(t.instant >= now.minus(Duration.ofDays(36525))) { "Choose an event within the past 100 years." } }
        fun optionalNumber(value: Double?, max: Double, label: String) { require(value == null || (value.isFinite() && value in 0.0..max)) { "$label must be a finite number from 0 to $max, or blank if unknown." } }
        when (log) {
            is FoodLog -> {
                time(log.time); require(log.name.isNotBlank() && log.name.length <= 120) { "Enter a food name of 1–120 characters." }
                require(log.meal in listOf("Breakfast", "Lunch", "Dinner", "Snack", "Other")) { "Choose a meal type." }
                require(log.quantity.isFinite() && log.quantity > 0 && log.quantity <= 100000) { "Enter a positive quantity up to 100,000." }
                require(log.unit.isNotBlank() && log.unit.length <= 30) { "Enter a quantity unit." }
                require(log.portion.isFinite() && log.portion in 0.0..1.0) { "Portion consumed must be 0–100%." }
                optionalNumber(log.calories, 20000.0, "Calories"); listOf(log.protein, log.carbs, log.fat).forEach { optionalNumber(it, 5000.0, "Macro grams") }
            }
            is ActivityLog -> {
                time(log.time); require(log.name.isNotBlank() && log.name.length <= 120) { "Enter an activity name of 1–120 characters." }
                require(log.minutes == null || (log.minutes.isFinite() && log.minutes > 0 && log.minutes <= 1440)) { "Duration must be more than 0 and at most 1,440 minutes, or blank." }
                require(log.steps == null || log.steps in 0..200000) { "Steps must be 0–200,000, or blank if unknown." }
                require(log.notes.length <= 2000 && log.exercises.size <= 50) { "Keep notes within 2,000 characters and workouts within 50 exercises." }
                log.exercises.forEach { require(it.name.isNotBlank() && it.name.length <= 120 && (it.sets == null || it.sets in 1..100) && (it.reps == null || it.reps in 1..10000) && (it.loadKg == null || (it.loadKg.isFinite() && it.loadKg in 0.0..1000.0))) { "Check exercise names, sets (1–100), reps (1–10,000) and load (0–1,000 kg)." } }
            }
            is StepsLog -> { time(log.time); require(log.steps in 0..200000) { "Daily steps must be 0–200,000." } }
            is SleepLog -> { time(log.bed); time(log.wake); require(log.wake.instant > log.bed.instant && Duration.between(log.bed.instant, log.wake.instant) <= Duration.ofHours(24)) { "Wake time must follow bedtime, within 24 hours. Split longer sessions." } }
        }
    }
    fun overlaps(start: Instant, end: Instant, otherStart: Instant, otherEnd: Instant): Boolean = start < otherEnd && otherStart < end
    fun sleepMinutes(sessions: List<Pair<Instant, Instant>>): Long? {
        if (sessions.isEmpty()) return null
        var end = Instant.MIN; var total = 0L
        sessions.sortedBy { it.first }.forEach { (start, finish) ->
            val effective = maxOf(start, end)
            if (finish > effective) total += Duration.between(effective, finish).seconds
            end = maxOf(end, finish)
        }
        return total / 60
    }
    fun steps(dailyTotal: Int?, walks: List<WalkSteps>): StepTotal {
        if (dailyTotal != null) return StepTotal(dailyTotal, "Daily total used; individual activity steps are not added again.")
        if (walks.isEmpty()) return StepTotal(null, "Steps not logged.")
        val accepted = mutableListOf<WalkSteps>(); var excluded = 0
        walks.sortedWith(compareBy<WalkSteps> { it.start }.thenBy { it.id }).forEach { walk ->
            if (accepted.any { it.start == walk.start || overlaps(walk.start, maxOf(walk.end, walk.start.plusNanos(1)), it.start, maxOf(it.end, it.start.plusNanos(1))) }) excluded++ else accepted += walk
        }
        return StepTotal(accepted.sumOf { it.steps }, if (excluded > 0) "$excluded overlapping activity step logs excluded; edit duplicates or enter a daily total." else "Reported activity steps; no daily total logged.")
    }
    fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
