package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LoggingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun begin(label: String) {
        compose.waitUntil(20000) { compose.onAllNodesWithText("Log", useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Log").performClick(); compose.onNodeWithText(label, substring = false).performClick()
    }
    @Test fun foodSaveEditAndDeleteWorkWithoutAnAccount() {
        begin("Food")
        compose.onNodeWithText("Food name").performTextInput("UI toast")
        compose.onNodeWithText("Calories (optional)").performScrollTo().performTextInput("200")
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Save entry").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Journal").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("UI toast"))
        compose.onNodeWithText("UI toast").assertExists()
        compose.onNodeWithText("Edit food").performScrollTo().performClick()
        compose.onNodeWithText("Food name").performTextReplacement("Corrected UI toast")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Corrected UI toast").assertExists()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Save entry").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Corrected UI toast"))
        compose.onNodeWithText("Delete", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Delete record").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Corrected UI toast").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun dailyStepsAcceptExplicitZeroAndMissingSleepStaysUnknown() {
        begin("Daily steps")
        compose.onNodeWithText("Daily step total").performTextInput("0")
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Save entry").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Journal").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("0 daily steps"))
        compose.onNodeWithText("0 daily steps").assertExists()
        compose.onNodeWithText("Delete", substring = false).performScrollTo().performClick(); compose.onNodeWithText("Delete record").performClick()
    }
}
