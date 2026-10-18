package com.hbhaq.metricme.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class EquationInput(val coefficient: Int, val label: String) {
    PLUS_5(5, "+5 coefficient (male study group)"),
    MINUS_161(-161, "−161 coefficient (female study group)")
}
enum class ActivityLevel(val multiplier: Double, val label: String) {
    SEDENTARY(1.2, "Mostly seated"), LIGHT(1.375, "Lightly active"),
    MODERATE(1.55, "Moderately active"), HIGH(1.725, "Very active"),
    VERY_HIGH(1.9, "Highly physical days")
}
enum class Source { MANUAL, AI_ESTIMATED, IMPORTED }
enum class Confirmation { DRAFT, CONFIRMED }
enum class Processing { COMPLETE, PENDING, FAILED }
data class Provenance(
    val source: Source = Source.MANUAL,
    val confirmation: Confirmation = Confirmation.CONFIRMED,
    val processing: Processing = Processing.COMPLETE,
    val confidence: Double? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt
) {
    init { require(confidence == null || (confidence.isFinite() && confidence in 0.0..1.0)) }
}
data class Profile(
    val id: String = "local",
    val name: String = "",
    val birthDate: LocalDate? = null,
    val genderIdentity: String = "",
    val equationInput: EquationInput? = null,
    val activity: ActivityLevel = ActivityLevel.SEDENTARY,
    val disableAutomaticTargets: Boolean = false,
    val goals: String = "",
    val restrictions: String = "",
    val imperialDisplay: Boolean = false,
    val provenance: Provenance
)
enum class MeasurementKind(val unit: String) { WEIGHT("kg"), HEIGHT("cm") }
enum class Acquisition { MEASURED, MANUALLY_ENTERED }
data class Measurement(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String = "local",
    val kind: MeasurementKind,
    val value: Double,
    val instant: Instant,
    val zoneId: String,
    val sequence: Long,
    val acquisition: Acquisition = Acquisition.MANUALLY_ENTERED,
    val operationKey: String = UUID.randomUUID().toString(),
    val provenance: Provenance
) {
    val date: LocalDate get() = instant.atZone(ZoneId.of(zoneId)).toLocalDate()
}
data class TargetSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String = "local",
    val effectiveDate: LocalDate,
    val calories: Double?,
    val bmr: Double?,
    val manual: Boolean,
    val explanation: String,
    val createdAt: Instant,
    val sequence: Long
)
