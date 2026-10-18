package com.hbhaq.metricme.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class LoggingRulesTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val time = EventTime(now, "UTC", 0)
    @Test fun unknownNutritionAndExplicitZeroRemainDifferent() {
        val food = FoodLog(null, time, "Water", "Other", 1.0, "glass", 1.0, null, null, null, null)
        LogRules.validate(food, now); LogRules.validate(food.copy(calories = 0.0), now)
        assertNull(food.calories); assertEquals(0.0, food.copy(calories = 0.0).calories!!, 0.0)
    }
    @Test fun portionScalingPreservesUnknownExceptExplicitNoneConsumed() {
        val food = FoodLog(null, time, "Toast", "Breakfast", 2.0, "slice", 0.5, 200.0, null, 40.0, 10.0)
        assertEquals(100.0, food.consumedCalories!!, 0.0); assertNull(food.consumedProtein)
        assertEquals(20.0, food.consumedCarbs!!, 0.0)
        assertEquals(0.0, food.copy(portion = 0.0, calories = null).consumedCalories!!, 0.0)
        assertNull(LogRules.loggedCalories(emptyList()))
    }
    @Test fun negativeNonfiniteAndFutureFoodRejected() {
        val food = FoodLog(null, time, "Toast", "Breakfast", 1.0, "slice", 1.0, 100.0, null, null, null)
        listOf(food.copy(calories = Double.NaN), food.copy(portion = 1.1), food.copy(quantity = -1.0), food.copy(time = time.copy(instant = now.plusSeconds(1)))).forEach { assertThrows(IllegalArgumentException::class.java) { LogRules.validate(it, now) } }
    }
    @Test fun overnightSleepUsesInstantsAndWakeDate() {
        val bed = EventTimes.resolve("2026-10-17T23:00", "Asia/Karachi")
        val wake = EventTimes.resolve("2026-10-18T07:30", "Asia/Karachi")
        LogRules.validate(SleepLog(null, bed, wake, null, false), now)
        assertEquals(LocalDate.parse("2026-10-18"), wake.date)
        assertEquals(510L, LogRules.sleepMinutes(listOf(bed.instant to wake.instant)))
    }
    @Test fun springSleepIsShorterThanWallClockDifference() {
        val bed = EventTimes.resolve("2026-03-29T00:00", "Europe/London")
        val wake = EventTimes.resolve("2026-03-29T08:00", "Europe/London")
        assertEquals(420L, LogRules.sleepMinutes(listOf(bed.instant to wake.instant)))
    }
    @Test fun gapRejectedAndRepeatedTimeRequiresOffset() {
        assertThrows(IllegalArgumentException::class.java) { EventTimes.resolve("2026-03-29T01:30", "Europe/London") }
        assertThrows(IllegalArgumentException::class.java) { EventTimes.resolve("2026-10-25T01:30", "Europe/London") }
        val earlier = EventTimes.resolve("2026-10-25T01:30", "Europe/London", "+01:00")
        val later = EventTimes.resolve("2026-10-25T01:30", "Europe/London", "+00:00")
        assertEquals(3600L, Duration.between(earlier.instant, later.instant).seconds)
    }
    @Test fun multipleSleepSessionsAndNapsDoNotDoubleCountOverlaps() {
        assertNull(LogRules.sleepMinutes(emptyList()))
        assertEquals(180L, LogRules.sleepMinutes(listOf(now.minusSeconds(7200) to now, now.minusSeconds(3600) to now.plusSeconds(3600))))
        assertFalse(LogRules.overlaps(now.minusSeconds(60), now, now, now.plusSeconds(60)))
    }
    @Test fun dailyStepTotalSupersedesWalksIncludingExplicitZero() {
        val walks = listOf(WalkSteps("a", now.minusSeconds(3600), now, 3000))
        assertEquals(6000, LogRules.steps(6000, walks).count)
        assertEquals(0, LogRules.steps(0, walks).count)
    }
    @Test fun missingStepsAreUnknownAndOverlappingWalksAreExcluded() {
        assertNull(LogRules.steps(null, emptyList()).count)
        val result = LogRules.steps(null, listOf(WalkSteps("a", now.minusSeconds(3600), now, 3000), WalkSteps("b", now.minusSeconds(1800), now.plusSeconds(600), 2500)))
        assertEquals(3000, result.count); assertTrue(result.explanation.contains("excluded"))
    }
    @Test fun unknownActivityStepsNotInventedAndExerciseValuesValidated() {
        val activity = ActivityLog(null, time, "Workout", 30.0, null, null, "", listOf(Exercise("Squat", 3, 8, 40.0)))
        LogRules.validate(activity, now); assertNull(activity.steps)
        assertThrows(IllegalArgumentException::class.java) { LogRules.validate(activity.copy(exercises = listOf(Exercise("Squat", -1, 8, 40.0))), now) }
    }
}
