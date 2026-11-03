package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hbhaq.metricme.journal.InsightPeriods
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class InsightPeriodsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun calendarPeriodsAndCurrentPartialPeriodAreExplicit() {
        val today=LocalDate.of(2026,11,3)
        assertEquals(today to today,InsightPeriods.range("day",today,today))
        assertEquals(LocalDate.of(2026,11,2) to today,InsightPeriods.range("week",today,today))
        assertEquals(LocalDate.of(2026,11,1) to today,InsightPeriods.range("month",today,today))
        assertEquals(LocalDate.of(2026,10,1) to LocalDate.of(2026,10,31),InsightPeriods.range("month",LocalDate.of(2026,10,15),today))
    }
    @Test fun insightsHasPeriodControlsAndItsOwnQuestionComposer() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-insights").performClick()
        compose.onNodeWithText("Daily").assertExists(); compose.onNodeWithText("Weekly").assertExists(); compose.onNodeWithText("Monthly").assertExists()
        compose.onNodeWithText("Ask about your history").performTextInput("When did I last have a burger?")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("When did I last have a burger?").assertExists()
    }
}
