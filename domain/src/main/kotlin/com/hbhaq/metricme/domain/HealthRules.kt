package com.hbhaq.metricme.domain

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class EnergyEstimate(val bmr: Double?, val tdee: Double?, val reason: String)

object HealthRules {
    fun validateProfile(profile: Profile, today: LocalDate): String? = when {
        profile.name.length > 80 -> "Name must be 80 characters or fewer."
        profile.genderIdentity.length > 80 -> "Gender identity must be 80 characters or fewer."
        profile.goals.length > 1000 || profile.restrictions.length > 1000 -> "Notes must be 1,000 characters or fewer."
        profile.birthDate != null && (profile.birthDate.isAfter(today) || Period.between(profile.birthDate, today).years !in 18..120) -> "MetricMe is for adults aged 18–120. Leave birthdate blank to log without estimates."
        else -> null
    }

    fun validateMeasurement(kind: MeasurementKind, value: Double): String? {
        if (!value.isFinite()) return "Enter a finite number."
        val range = if (kind == MeasurementKind.WEIGHT) 20.0..500.0 else 80.0..250.0
        return if (value !in range) "Enter ${kind.name.lowercase()} between ${range.start.toInt()} and ${range.endInclusive.toInt()} ${kind.unit}." else null
    }

    fun requiresConfirmation(kind: MeasurementKind, value: Double, previous: Double?): Boolean {
        val unusual = if (kind == MeasurementKind.WEIGHT) value !in 35.0..250.0 else value !in 120.0..220.0
        val change = previous != null && (if (kind == MeasurementKind.WEIGHT) abs(value - previous) / previous > 0.10 else abs(value - previous) > 3.0)
        return unusual || change
    }

    fun effectiveMeasurement(records: List<Measurement>, kind: MeasurementKind, at: Instant): Measurement? = records
        .filter { it.kind == kind && it.instant <= at && it.provenance.confirmation == Confirmation.CONFIRMED }
        .maxWithOrNull(compareBy<Measurement> { it.instant }.thenBy { it.sequence }.thenBy { it.id })

    fun stale(measurement: Measurement, today: LocalDate): Boolean = ChronoUnit.DAYS.between(measurement.date, today) > 30

    fun estimate(profile: Profile, weightKg: Double?, heightCm: Double?, date: LocalDate): EnergyEstimate {
        if (profile.disableAutomaticTargets) return EnergyEstimate(null, null, "Automatic calorie estimates are off. Logging and manual targets remain available.")
        val birth = profile.birthDate ?: return EnergyEstimate(null, null, "Add a birthdate to calculate an estimate, or set a manual target.")
        val age = Period.between(birth, date).years
        if (age !in 18..120 || birth.isAfter(date)) return EnergyEstimate(null, null, "Estimates are available for adults aged 18–120.")
        val input = profile.equationInput ?: return EnergyEstimate(null, null, "Choose an equation input or use a manual target.")
        if (weightKg == null || heightCm == null) return EnergyEstimate(null, null, "Add height and weight, or use a manual target.")
        if (validateMeasurement(MeasurementKind.WEIGHT, weightKg) != null || validateMeasurement(MeasurementKind.HEIGHT, heightCm) != null)
            return EnergyEstimate(null, null, "Valid height and weight are needed for this estimate.")
        val bmr = 10 * weightKg + 6.25 * heightCm - 5 * age + input.coefficient
        if (bmr <= 0) return EnergyEstimate(null, null, "These inputs do not support a usable estimate. Use a manual target.")
        return EnergyEstimate(bmr, bmr * profile.activity.multiplier, "Mifflin–St Jeor maintenance estimate; activity already included.")
    }

    fun historicalTarget(records: List<TargetSnapshot>, date: LocalDate): TargetSnapshot? = records
        .filter { it.effectiveDate <= date }
        .maxWithOrNull(compareBy<TargetSnapshot> { it.effectiveDate }.thenBy { it.createdAt }.thenBy { it.sequence })

    fun validateManualTarget(value: Double?): String? = if (value != null && (!value.isFinite() || value !in 500.0..10000.0))
        "Enter a manual target between 500 and 10,000 kcal. This range is input validation, not a recommendation." else null
}
