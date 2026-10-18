package com.hbhaq.metricme.data

import androidx.room.withTransaction
import com.hbhaq.metricme.domain.*
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

fun Stamp.domain() = Provenance(Source.valueOf(source), Confirmation.valueOf(confirmation), Processing.valueOf(processing), confidence, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt))
fun ProfileEntity.domain() = Profile(id, name, birthDate?.let(LocalDate::parse), genderIdentity, equationInput?.let(EquationInput::valueOf), ActivityLevel.valueOf(activity), disableAutomaticTargets, goals, restrictions, imperialDisplay, stamp.domain())
fun MeasurementEntity.domain() = Measurement(id, profileId, MeasurementKind.valueOf(kind), value, Instant.ofEpochMilli(instant), zoneId, sequence, Acquisition.valueOf(acquisition), operationKey, stamp.domain())
fun TargetEntity.domain() = TargetSnapshot(id, profileId, LocalDate.parse(effectiveDate), calories, bmr, manual, explanation, Instant.ofEpochMilli(createdAt), sequence)

@Singleton
class ProfileRepository @Inject constructor(private val db: MetricMeDatabase, private val clock: Clock) {
    private val dao get() = db.profileDao()
    val profile = dao.observeProfile().map { it?.domain() }
    val measurements = dao.observeMeasurements().map { list -> list.map { it.domain() } }
    val targets = dao.observeTargets().map { list -> list.map { it.domain() } }

    suspend fun saveProfile(value: Profile, key: String) = db.withTransaction {
        val now = clock.instant()
        require(HealthRules.validateProfile(value, LocalDate.now(clock)) == null) { "Check the profile values." }
        val hash = hash(value.copy(provenance = Provenance(createdAt = Instant.EPOCH)).toString())
        if (replayed(key, hash)) return@withTransaction
        val old = dao.profile()
        dao.saveProfile(ProfileEntity(name = value.name.trim(), birthDate = value.birthDate?.toString(), genderIdentity = value.genderIdentity.trim(), equationInput = value.equationInput?.name, activity = value.activity.name, disableAutomaticTargets = value.disableAutomaticTargets, goals = value.goals.trim(), restrictions = value.restrictions.trim(), imperialDisplay = value.imperialDisplay, stamp = Stamp(createdAt = old?.stamp?.createdAt ?: now.toEpochMilli(), updatedAt = now.toEpochMilli())))
        refreshTarget()
        dao.insertReceipt(ReceiptEntity(key, "local", hash, "local", now.toEpochMilli()))
    }

    suspend fun saveMeasurement(id: String?, kind: MeasurementKind, value: Double, date: LocalDate, acquisition: Acquisition, confirmedUnusual: Boolean, key: String) = db.withTransaction {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        require(!date.isAfter(today) && date >= today.minusYears(120)) { "Choose a past or current date within 120 years." }
        require(HealthRules.validateMeasurement(kind, value) == null) { "Check the measurement value." }
        val hash = hash("$id|$kind|$value|$date|$acquisition|$confirmedUnusual")
        if (replayed(key, hash)) return@withTransaction
        ensureProfile()
        val records = dao.measurements()
        val existing = id?.let { wanted -> records.firstOrNull { it.id == wanted } ?: throw IllegalArgumentException("This measurement was deleted. Close and reopen the form.") }
        val previous = HealthRules.effectiveMeasurement(records.map { it.domain() }, kind, now)
        require(confirmedUnusual || !HealthRules.requiresConfirmation(kind, value, previous?.value)) { "This value is unusual or changes substantially. Confirm it before saving." }
        val zone = clock.zone
        // Today uses the actual save instant. Backdated values use local noon, never a future instant.
        val instant = if (date == today) now else date.atTime(12, 0).atZone(zone).toInstant()
        val entity = MeasurementEntity(sequence = existing?.sequence ?: 0, id = existing?.id ?: UUID.randomUUID().toString(), kind = kind.name, value = value, instant = if (existing != null && existing.domain().date == date) existing.instant else instant.toEpochMilli(), zoneId = if (existing != null && existing.domain().date == date) existing.zoneId else zone.id, acquisition = acquisition.name, operationKey = existing?.operationKey ?: key, stamp = Stamp(createdAt = existing?.stamp?.createdAt ?: now.toEpochMilli(), updatedAt = now.toEpochMilli()))
        if (existing == null) dao.insertMeasurement(entity) else dao.updateMeasurement(entity)
        refreshTarget()
        dao.insertReceipt(ReceiptEntity(key, "local", hash, entity.id, now.toEpochMilli()))
    }

    suspend fun deleteMeasurement(id: String, key: String) = db.withTransaction {
        val hash = hash("delete:$id")
        if (replayed(key, hash)) return@withTransaction
        ensureProfile()
        dao.deleteMeasurement(id)
        refreshTarget()
        dao.insertReceipt(ReceiptEntity(key, "local", hash, id, clock.millis()))
    }

    suspend fun setManualTarget(value: Double?, key: String) = db.withTransaction {
        require(HealthRules.validateManualTarget(value) == null) { "Check the manual target value." }
        val hash = hash("target:$value")
        if (replayed(key, hash)) return@withTransaction
        ensureProfile()
        if (value != null) dao.insertTarget(TargetEntity(id = UUID.randomUUID().toString(), effectiveDate = LocalDate.now(clock).toString(), calories = value, bmr = null, manual = true, explanation = "Manually entered target", createdAt = clock.millis()))
        else refreshTarget(forceAutomatic = true)
        dao.insertReceipt(ReceiptEntity(key, "local", hash, "target", clock.millis()))
    }

    suspend fun ensureCurrentTarget() = db.withTransaction {
        val today = LocalDate.now(clock)
        val current = HealthRules.historicalTarget(dao.targets().map { it.domain() }, today)
        if (dao.profile() != null && current?.effectiveDate != today && current?.manual != true) refreshTarget()
    }

    private suspend fun refreshTarget(forceAutomatic: Boolean = false) {
        val today = LocalDate.now(clock)
        val current = HealthRules.historicalTarget(dao.targets().map { it.domain() }, today)
        if (current?.manual == true && !forceAutomatic) return
        val p = dao.profile()?.domain() ?: return
        val records = dao.measurements().map { it.domain() }
        val estimate = HealthRules.estimate(p, HealthRules.effectiveMeasurement(records, MeasurementKind.WEIGHT, clock.instant())?.value, HealthRules.effectiveMeasurement(records, MeasurementKind.HEIGHT, clock.instant())?.value, today)
        dao.insertTarget(TargetEntity(id = UUID.randomUUID().toString(), effectiveDate = today.toString(), calories = estimate.tdee, bmr = estimate.bmr, manual = false, explanation = estimate.reason, createdAt = clock.millis()))
    }
    private suspend fun ensureProfile() {
        if (dao.profile() == null) dao.saveProfile(ProfileEntity(stamp = Stamp(createdAt = clock.millis())))
    }
    private suspend fun replayed(key: String, hash: String): Boolean {
        UUID.fromString(key)
        val prior = dao.receipt(key) ?: return false
        require(prior.payloadHash == hash) { "This save key was already used for different data. Reopen the form." }
        return true
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
