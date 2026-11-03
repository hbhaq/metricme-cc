package com.hbhaq.metricme.contracts

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class GuidanceRulesTest {
    private fun fact(category:String)=JournalFact(category,"Reported "+category,"",Instant.now().minusSeconds(10).toString())
    private fun extraction(f:JournalFact)=JournalExtraction("LOG","A report",listOf(f),null,null,null)
    @Test fun foodNeedsCaloriesAndConsumedAmountBeforeSaving() {
        val food=fact("FOOD")
        assertNotNull(JournalQuality.question(extraction(food)))
        assertNotNull(JournalQuality.question(extraction(food.copy(calories=400.0))))
        assertNull(JournalQuality.question(extraction(food.copy(quantity=1.0,unit="burger",calories=400.0,estimated=true))))
    }
    @Test fun relevantDetailsAreRequiredWithoutInventingUnnecessaryNumbers() {
        assertNotNull(JournalQuality.question(extraction(fact("ACTIVITY"))))
        assertNull(JournalQuality.question(extraction(fact("ACTIVITY").copy(durationMinutes=20.0))))
        assertNull(JournalQuality.question(extraction(fact("ACTIVITY").copy(steps=0))))
        assertNotNull(JournalQuality.question(extraction(fact("SLEEP"))))
        assertNotNull(JournalQuality.question(extraction(fact("MEDICATION"))))
        assertNotNull(JournalQuality.question(extraction(fact("WATER"))))
        assertNull(JournalQuality.question(extraction(fact("MOOD"))))
        assertNull(JournalQuality.question(extraction(fact("NOTE"))))
    }
    @Test fun insightClarificationMayPrecedeDateResolution() {
        JournalRules.extraction(JournalExtraction("QUESTION","How am I doing?",emptyList(),"Which goal and period?",null,null,continuation="Intent unclear"))
        assertThrows(IllegalArgumentException::class.java) { JournalRules.extraction(JournalExtraction("QUESTION","How am I doing?",emptyList(),null,null,null)) }
    }
    @Test fun compactContinuationSurvivesManyRoundsWithoutTruncatingLatestAnswer() {
        var summary="Burger from lunch"
        repeat(50) { round ->
            val updated="Burger from lunch; confirmed round "+round+"; portion 2 slices; sauce clarified"
            val e=JournalExtraction("LOG","Answer "+round,emptyList(),"Next useful question?",null,null,continuation=updated)
            summary=JournalQuality.continuation(summary,e,"Next useful question?")
            assertEquals(updated,summary)
        }
        assertTrue(summary.contains("49"))
    }
    @Test fun profileRejectsInvalidInputsAndUndatedTargets() {
        WellnessRules.profile(WellnessProfile(30,"Woman",170.0,70.0,2000,"2026-01-01"))
        assertThrows(IllegalArgumentException::class.java) { WellnessRules.profile(WellnessProfile(age=17)) }
        assertThrows(IllegalArgumentException::class.java) { WellnessRules.profile(WellnessProfile(weightKg=Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { WellnessRules.profile(WellnessProfile(dailyCalorieTarget=2000)) }
    }
}
