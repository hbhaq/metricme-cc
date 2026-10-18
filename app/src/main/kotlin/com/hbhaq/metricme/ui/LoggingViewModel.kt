package com.hbhaq.metricme.ui

import androidx.lifecycle.*
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import com.hbhaq.metricme.reminders.ReminderCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.*
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LoggingState(val loading: Boolean = true, val logs: LogBook = LogBook(), val routines: RoutineBook = RoutineBook(), val date: LocalDate = LocalDate.now(), val busy: Boolean = false, val error: String? = null)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LoggingViewModel @Inject constructor(private val repository: LoggingRepository, private val routines: RoutineRepository, private val reminders: ReminderCoordinator, private val clock: Clock, private val saved: SavedStateHandle) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val demo = MutableStateFlow(false)
    private val retry = MutableStateFlow(0)
    val date = saved.getStateFlow("journalDate", LocalDate.now(clock).toString())
    val editor = saved.getStateFlow("editor", "")
    val fields = saved.getStateFlow("logFields", hashMapOf<String, String>())
    private val data = retry.flatMapLatest {
        combine(repository.book, routines.book, date) { logs, routines, day -> LoggingState(loading = false, logs = logs, routines = routines, date = LocalDate.parse(day)) }
            .catch { emit(LoggingState(loading = false, error = "Local records could not be opened. Retry to reconnect; nothing was deleted.")) }
    }
    val state = combine(data, busy, error) { value, saving, message -> value.copy(busy = saving, error = message ?: value.error) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LoggingState())
    init {
        viewModelScope.launch {
            var lastDate = LocalDate.now(clock)
            while (isActive) {
                delay(60000)
                val today = LocalDate.now(clock)
                if (today != lastDate) { if (date.value == lastDate.toString()) saved["journalDate"] = today.toString(); lastDate = today; reminders.requestReconcile() }
            }
        }
    }
    fun setDemo(value: Boolean) { demo.value = value; if (value) close() }
    fun change(key: String, value: String) {
        val limit = if (key == "exercises") 10000 else if (key in setOf("notes", "instructions")) 2000 else 200
        if (value.length > limit) { error.value = "This field is limited to $limit characters."; return }
        saved["logFields"] = HashMap(fields.value).apply { put(key, value) }; error.value = null
    }
    fun clearError() { error.value = null }
    fun retry() { error.value = null; retry.value++; refresh() }
    fun refresh() { reminders.requestReconcile() }
    fun chooseDate(value: LocalDate) { saved["journalDate"] = value.toString() }
    fun today() = LocalDate.now(clock)
    fun previousDay() = chooseDate(LocalDate.parse(date.value).minusDays(1))
    fun nextDay() { val next = LocalDate.parse(date.value).plusDays(1); if (next <= today()) chooseDate(next) }
    private fun baseFields(): HashMap<String, String> {
        val day = LocalDate.parse(date.value)
        val now = LocalDateTime.now(clock).withSecond(0).withNano(0)
        val time = if (day == today()) now else day.atTime(12, 0)
        return hashMapOf("time" to time.toString(), "zone" to clock.zone.id, "quantity" to "1", "unit" to "serving", "portion" to "100", "meal" to "Other", "bed" to day.minusDays(1).atTime(23, 0).toString(), "wake" to day.atTime(7, 0).toString(), "bedZone" to clock.zone.id, "wakeZone" to clock.zone.id, "weekdays" to "1,2,3,4,5,6,7", "times" to "09:00", "start" to today().toString())
    }
    fun openLog(kind: LogKind, item: JournalItem? = null) {
        if (demo.value || busy.value) return
        val f = baseFields()
        fun event(t: EventTime) { f["time"] = t.instant.atZone(ZoneId.of(t.zoneId)).toLocalDateTime().withNano(0).toString(); f["zone"] = t.zoneId; f["offset"] = ZoneOffset.ofTotalSeconds(t.offsetSeconds).id }
        when (val input = item?.input) {
            is FoodLog -> { event(input.time); f.putAll(mapOf("name" to input.name, "meal" to input.meal, "quantity" to input.quantity.toString(), "unit" to input.unit, "portion" to (input.portion * 100).toString(), "calories" to input.calories?.toString().orEmpty(), "protein" to input.protein?.toString().orEmpty(), "carbs" to input.carbs?.toString().orEmpty(), "fat" to input.fat?.toString().orEmpty())) }
            is ActivityLog -> { event(input.time); f.putAll(mapOf("name" to input.name, "minutes" to input.minutes?.toString().orEmpty(), "steps" to input.steps?.toString().orEmpty(), "intensity" to input.intensity.orEmpty(), "notes" to input.notes, "exercises" to input.exercises.joinToString("\n") { "${it.name} | ${it.sets ?: ""} | ${it.reps ?: ""} | ${it.loadKg ?: ""}" })) }
            is StepsLog -> { event(input.time); f["steps"] = input.steps.toString() }
            is SleepLog -> { f["bed"] = input.bed.instant.atZone(ZoneId.of(input.bed.zoneId)).toLocalDateTime().toString(); f["wake"] = input.wake.instant.atZone(ZoneId.of(input.wake.zoneId)).toLocalDateTime().toString(); f["bedZone"] = input.bed.zoneId; f["wakeZone"] = input.wake.zoneId; f["bedOffset"] = ZoneOffset.ofTotalSeconds(input.bed.offsetSeconds).id; f["wakeOffset"] = ZoneOffset.ofTotalSeconds(input.wake.offsetSeconds).id; f["quality"] = input.quality.orEmpty() }
            null -> Unit
        }
        start("log:${kind.name}", item?.id, f)
    }
    fun openRoutine(kind: RoutineKind, card: RoutineCard? = null) {
        if (demo.value || busy.value) return
        val f = baseFields()
        if (card != null) f.putAll(mapOf("name" to card.name, "instructions" to card.instructions, "dose" to card.dose, "notes" to card.notes, "weekdays" to RoutineRepository.encodeDays(card.weekdays), "times" to card.times.joinToString(", "), "asNeeded" to card.asNeeded.toString(), "lastDay" to card.lastDay?.toString().orEmpty()))
        start("routine:${kind.name}", card?.id, f)
    }
    fun openPrn(card: RoutineCard) { if (!demo.value && !busy.value) start("prn", card.id, baseFields().apply { put("name", card.name); put("dose", card.dose); put("instructions", card.instructions) }) }
    private fun start(mode: String, id: String?, f: HashMap<String, String>) { saved["editor"] = mode; saved["editId"] = id; saved["saveKey"] = UUID.randomUUID().toString(); saved["logFields"] = f; error.value = null }
    fun close() { if (!busy.value) { saved["editor"] = ""; error.value = null } }
    private fun value(key: String) = fields.value[key].orEmpty().trim()
    private fun number(key: String, label: String, required: Boolean = false): Double? {
        val raw = value(key); if (raw.isEmpty() && !required) return null
        return raw.toDoubleOrNull() ?: throw IllegalArgumentException("$label needs a number with a decimal point, or leave it blank if unknown.")
    }
    private fun integer(key: String, label: String, required: Boolean = false): Int? {
        val raw = value(key); if (raw.isEmpty() && !required) return null
        return raw.toIntOrNull() ?: throw IllegalArgumentException("$label needs a whole number.")
    }
    private fun day(key: String): LocalDate = try { LocalDate.parse(value(key)) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter $key as YYYY-MM-DD.") }
    fun save() = perform {
        val id: String? = saved["editId"]
        val key: String = saved["saveKey"] ?: throw IllegalArgumentException("Reopen the form before saving.")
        fun event() = EventTimes.resolve(value("time"), value("zone"), value("offset"))
        if (editor.value.startsWith("log:")) {
            val input: ManualLog = when (LogKind.valueOf(editor.value.substringAfter(':'))) {
                LogKind.FOOD -> FoodLog(id, event(), value("name"), value("meal"), number("quantity", "Quantity", true)!!, value("unit"), number("portion", "Portion", true)!! / 100, number("calories", "Calories"), number("protein", "Protein"), number("carbs", "Carbs"), number("fat", "Fat"))
                LogKind.ACTIVITY -> ActivityLog(id, event(), value("name"), number("minutes", "Duration"), integer("steps", "Steps"), value("intensity").ifEmpty { null }, value("notes"), parseExercises(value("exercises")))
                LogKind.STEPS -> StepsLog(id, event(), integer("steps", "Steps", true)!!)
                LogKind.SLEEP -> SleepLog(id, EventTimes.resolve(value("bed"), value("bedZone"), value("bedOffset")), EventTimes.resolve(value("wake"), value("wakeZone"), value("wakeOffset")), value("quality").ifEmpty { null }, value("overlap") == "true")
            }
            repository.save(input, key)
        } else if (editor.value == "prn") {
            routines.logPrn(id ?: throw IllegalArgumentException("Choose a medication first."), event(), value("notes"), key)
        } else {
            val times = if (value("asNeeded") == "true") emptyList() else value("times").split(',').map { try { LocalTime.parse(it.trim()) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter reminder times as HH:mm, separated by commas.") } }
            routines.save(RoutineInput(id, RoutineKind.valueOf(editor.value.substringAfter(':')), value("name"), value("instructions"), value("dose"), value("notes"), RoutineRepository.days(value("weekdays")), times, day("start"), value("lastDay").takeIf { it.isNotEmpty() }?.let { day("lastDay") }, value("asNeeded") == "true"), key)
            reminders.requestReconcile()
        }
    }
    private fun parseExercises(text: String): List<Exercise> = text.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.split('|').map { it.trim() }
        require(parts.size <= 4) { "Each exercise needs: name | sets | reps | load in kg." }
        fun int(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Exercise sets and reps need whole numbers.") }
        val load = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("Exercise load needs a number in kg.") }
        Exercise(parts.first(), int(1), int(2), load)
    }
    fun delete(item: JournalItem) = perform { repository.delete(item.id, item.kind, UUID.randomUUID().toString()) }
    fun archive(card: RoutineCard) = perform { routines.archive(card.id, card.kind, UUID.randomUUID().toString()); reminders.requestReconcile() }
    fun action(card: OccurrenceCard, action: OccurrenceAction) = perform { reminders.action(card.id, action, UUID.randomUUID().toString()) }
    fun deletePrn(id: String) = perform { routines.deletePrn(id, UUID.randomUUID().toString()) }
    fun enableReminders(enabled: Boolean) = perform { reminders.setEnabled(enabled) }
    private fun perform(block: suspend () -> Unit) {
        if (busy.value || demo.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { block(); saved["editor"] = "" }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error.value = e.message ?: "Check your entries and retry." }
            catch (_: Exception) { error.value = "Could not update local records. Your form is preserved; try again." }
            finally { busy.value = false }
        }
    }
}
