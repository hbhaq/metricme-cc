package com.hbhaq.metricme.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hbhaq.metricme.data.ProfileRepository
import com.hbhaq.metricme.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.*
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class AppState(
    val loading: Boolean = true,
    val demo: Boolean = false,
    val profile: Profile? = null,
    val measurements: List<Measurement> = emptyList(),
    val targets: List<TargetSnapshot> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MetricMeViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock
) : ViewModel() {
    init {
        viewModelScope.launch {
            try { repository.ensureCurrentTarget() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The Room flow exposes a readable storage error if opening failed. */ }
        }
    }
    private val demo = saved.getStateFlow("demo", false)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val live = combine(repository.profile, repository.measurements, repository.targets) { p, m, t ->
        AppState(loading = false, profile = p, measurements = m, targets = t)
    }.catch { emit(AppState(loading = false, error = "Local data could not be opened. Restart the app to retry; no data was deleted.")) }
    val state = combine(live, demo, busy, error) { data, sample, saving, message ->
        (if (sample) demoState() else data).copy(saving = saving, error = message ?: data.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())

    val formKind = saved.getStateFlow("formKind", "")
    val name = saved.getStateFlow("name", "")
    val birthDate = saved.getStateFlow("birthDate", "")
    val gender = saved.getStateFlow("gender", "")
    val equation = saved.getStateFlow("equation", "")
    val activity = saved.getStateFlow("activity", ActivityLevel.SEDENTARY.name)
    val disableAuto = saved.getStateFlow("disableAuto", false)
    val goals = saved.getStateFlow("goals", "")
    val restrictions = saved.getStateFlow("restrictions", "")
    val imperial = saved.getStateFlow("imperial", false)
    val measurementKind = saved.getStateFlow("measurementKind", MeasurementKind.WEIGHT.name)
    val measurementValue = saved.getStateFlow("measurementValue", "")
    val measurementDate = saved.getStateFlow("measurementDate", LocalDate.now(clock).toString())
    val acquisition = saved.getStateFlow("acquisition", Acquisition.MANUALLY_ENTERED.name)
    val unusual = saved.getStateFlow("unusual", false)
    val manualTarget = saved.getStateFlow("manualTarget", "")

    fun text(key: String, value: String) { saved[key] = value; error.value = null }
    fun flag(key: String, value: Boolean) { saved[key] = value; error.value = null }
    fun setDemo(enabled: Boolean) { closeForm(); saved["demo"] = enabled; error.value = null }
    fun clearError() { error.value = null }
    fun today(): LocalDate = LocalDate.now(clock)

    fun openProfile() {
        if (state.value.demo) return
        val p = state.value.profile
        saved["name"] = p?.name.orEmpty(); saved["birthDate"] = p?.birthDate?.toString().orEmpty()
        saved["gender"] = p?.genderIdentity.orEmpty(); saved["equation"] = p?.equationInput?.name.orEmpty()
        saved["activity"] = (p?.activity ?: ActivityLevel.SEDENTARY).name
        saved["disableAuto"] = p?.disableAutomaticTargets ?: false
        saved["goals"] = p?.goals.orEmpty(); saved["restrictions"] = p?.restrictions.orEmpty()
        saved["imperial"] = p?.imperialDisplay ?: false
        startForm("profile")
    }
    fun openMeasurement(kind: MeasurementKind, record: Measurement? = null) {
        if (state.value.demo) return
        saved["measurementKind"] = kind.name; saved["measurementValue"] = record?.value?.toString().orEmpty()
        saved["measurementDate"] = record?.date?.toString() ?: today().toString()
        saved["acquisition"] = (record?.acquisition ?: Acquisition.MANUALLY_ENTERED).name
        saved["measurementId"] = record?.id; saved["unusual"] = false
        startForm("measurement")
    }
    fun openTarget() {
        if (state.value.demo) return
        val target = HealthRules.historicalTarget(state.value.targets, today())
        saved["manualTarget"] = if (target?.manual == true) target.calories?.toInt()?.toString().orEmpty() else ""
        startForm("target")
    }
    private fun startForm(kind: String) {
        saved["formKind"] = kind; saved["operationKey"] = UUID.randomUUID().toString(); error.value = null
    }
    fun closeForm() { if (!busy.value) { saved["formKind"] = ""; error.value = null } }
    fun saveProfile() = perform {
        val birth = birthDate.value.trim().takeIf { it.isNotEmpty() }?.let { parseDate(it) }
        val p = Profile(name = name.value, birthDate = birth, genderIdentity = gender.value,
            equationInput = equation.value.takeIf { it.isNotEmpty() }?.let(EquationInput::valueOf),
            activity = ActivityLevel.valueOf(activity.value), disableAutomaticTargets = disableAuto.value,
            goals = goals.value, restrictions = restrictions.value, imperialDisplay = imperial.value,
            provenance = Provenance(createdAt = clock.instant()))
        HealthRules.validateProfile(p, today())?.let { throw IllegalArgumentException(it) }
        repository.saveProfile(p, operationKey())
    }
    fun saveMeasurement() = perform {
        val value = measurementValue.value.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Enter a number using a decimal point, for example 72.5.")
        val kind = MeasurementKind.valueOf(measurementKind.value)
        HealthRules.validateMeasurement(kind, value)?.let { throw IllegalArgumentException(it) }
        repository.saveMeasurement(saved["measurementId"], kind, value, parseDate(measurementDate.value.trim()), Acquisition.valueOf(acquisition.value), unusual.value, operationKey())
    }
    fun saveTarget(automatic: Boolean = false) = perform {
        val value = if (automatic) null else manualTarget.value.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Enter a calorie target, or choose Use automatic estimate.")
        HealthRules.validateManualTarget(value)?.let { throw IllegalArgumentException(it) }
        repository.setManualTarget(value, operationKey())
    }
    fun deleteMeasurement(id: String) = perform { repository.deleteMeasurement(id, UUID.randomUUID().toString()) }
    private fun operationKey(): String = saved["operationKey"] ?: error("Reopen the form before saving.")
    private fun parseDate(value: String): LocalDate = try { LocalDate.parse(value) } catch (_: DateTimeException) { throw IllegalArgumentException("Enter a valid date as YYYY-MM-DD.") }
    private fun perform(block: suspend () -> Unit) {
        if (busy.value || state.value.demo) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { block(); saved["formKind"] = "" }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error.value = e.message ?: "Check your values and try again." }
            catch (_: Exception) { error.value = "Could not save locally. Your edits are still here; try again." }
            finally { busy.value = false }
        }
    }
    private fun demoState(): AppState {
        val today = today()
        val now = clock.instant()
        val p = Profile(name = "Alex", birthDate = today.minusYears(30), equationInput = EquationInput.PLUS_5, activity = ActivityLevel.LIGHT, goals = "Build a steadier routine", provenance = Provenance(createdAt = now))
        val measurements = listOf(
            Measurement(kind = MeasurementKind.WEIGHT, value = 72.4, instant = now.minusSeconds(86400), zoneId = clock.zone.id, sequence = 2, acquisition = Acquisition.MEASURED, provenance = Provenance(createdAt = now)),
            Measurement(kind = MeasurementKind.HEIGHT, value = 175.0, instant = now.minusSeconds(86400), zoneId = clock.zone.id, sequence = 1, acquisition = Acquisition.MEASURED, provenance = Provenance(createdAt = now))
        )
        val estimate = HealthRules.estimate(p, 72.4, 175.0, today)
        return AppState(loading = false, demo = true, profile = p, measurements = measurements, targets = listOf(TargetSnapshot(effectiveDate = today, calories = estimate.tdee, bmr = estimate.bmr, manual = false, explanation = "Sample maintenance estimate", createdAt = now, sequence = 1)))
    }
}
