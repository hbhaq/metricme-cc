package com.hbhaq.metricme.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HealthRulesTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val date = LocalDate.parse("2026-10-18")
    private val profile = Profile(birthDate = LocalDate.parse("1996-10-18"), equationInput = EquationInput.PLUS_5, provenance = Provenance(createdAt = now))

    @Test fun knownMifflinExampleAndActivityIncludedOnce() {
        val result = HealthRules.estimate(profile, 70.0, 175.0, date)
        assertEquals(1648.75, result.bmr!!, 0.0001)
        assertEquals(1978.5, result.tdee!!, 0.0001)
    }
    @Test fun secondCoefficientAndBirthdayBoundary() {
        val p = profile.copy(equationInput = EquationInput.MINUS_161)
        assertEquals(1482.75, HealthRules.estimate(p, 70.0, 175.0, date).bmr!!, 0.0001)
        assertEquals(1487.75, HealthRules.estimate(p, 70.0, 175.0, date.minusDays(1)).bmr!!, 0.0001)
    }
    @Test fun missingInputIsNotZero() {
        assertNull(HealthRules.estimate(profile, null, 175.0, date).tdee)
        assertNull(HealthRules.estimate(profile.copy(equationInput = null), 70.0, 175.0, date).bmr)
        assertNull(HealthRules.estimate(profile.copy(birthDate = null), 70.0, 175.0, date).bmr)
    }
    @Test fun pregnancyDisablesAutomaticButDoesNotModifyManualHistory() {
        assertNull(HealthRules.estimate(profile.copy(disableAutomaticTargets = true), 70.0, 175.0, date).tdee)
        val target = target(2100.0, date, 1)
        assertEquals(2100.0, HealthRules.historicalTarget(listOf(target), date)!!.calories!!, 0.0)
    }
    @Test fun rejectsNonFiniteAndInvalidInputs() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 501.0).forEach {
            assertNotNull(HealthRules.validateMeasurement(MeasurementKind.WEIGHT, it))
        }
        assertNull(HealthRules.estimate(profile, Double.NaN, 175.0, date).tdee)
        assertNotNull(HealthRules.validateManualTarget(Double.NaN))
    }
    @Test fun unusualChangesNeedConfirmation() {
        assertTrue(HealthRules.requiresConfirmation(MeasurementKind.WEIGHT, 85.0, 70.0))
        assertTrue(HealthRules.requiresConfirmation(MeasurementKind.HEIGHT, 180.0, 175.0))
        assertFalse(HealthRules.requiresConfirmation(MeasurementKind.WEIGHT, 71.0, 70.0))
    }
    @Test fun sameInstantUsesSequenceAndFutureAndDraftRecordsExcluded() {
        val first = measurement(70.0, now, 1)
        val later = measurement(71.0, now, 2)
        val future = measurement(72.0, now.plusSeconds(1), 3)
        val draft = measurement(90.0, now, 4).copy(provenance = Provenance(confirmation = Confirmation.DRAFT, createdAt = now))
        assertEquals(later, HealthRules.effectiveMeasurement(listOf(first, later, future, draft), MeasurementKind.WEIGHT, now))
    }
    @Test fun historicalTargetsNeverUseFutureSnapshots() {
        val past = target(2000.0, date.minusDays(10), 1)
        val current = target(2200.0, date, 2)
        assertEquals(past, HealthRules.historicalTarget(listOf(past, current), date.minusDays(1)))
        assertNull(HealthRules.historicalTarget(listOf(past), date.minusDays(11)))
    }
    @Test fun invalidationSnapshotPreventsOldEstimateReappearing() {
        val old = target(2000.0, date.minusDays(1), 1)
        val unavailable = target(0.0, date, 2).copy(calories = null)
        assertNull(HealthRules.historicalTarget(listOf(old, unavailable), date)!!.calories)
    }
    @Test fun staleThresholdAndLocalDateAreExplicit() {
        val m = measurement(70.0, Instant.parse("2026-09-17T23:30:00Z"), 1).copy(zoneId = "Asia/Karachi")
        assertEquals(LocalDate.parse("2026-09-18"), m.date)
        assertFalse(HealthRules.stale(m, date))
        assertTrue(HealthRules.stale(m, date.plusDays(1)))
    }
    @Test fun adultsOnlyAndGenderDoesNotSelectCoefficient() {
        assertNotNull(HealthRules.validateProfile(profile.copy(birthDate = date.minusYears(17)), date))
        val changed = profile.copy(genderIdentity = "Nonbinary")
        assertEquals(HealthRules.estimate(profile, 70.0, 175.0, date), HealthRules.estimate(changed, 70.0, 175.0, date))
    }
    private fun measurement(value: Double, instant: Instant, sequence: Long) = Measurement(kind = MeasurementKind.WEIGHT, value = value, instant = instant, zoneId = "UTC", sequence = sequence, provenance = Provenance(createdAt = now))
    private fun target(value: Double, date: LocalDate, sequence: Long) = TargetSnapshot(effectiveDate = date, calories = value, bmr = null, manual = true, explanation = "Manual", createdAt = now, sequence = sequence)
}
