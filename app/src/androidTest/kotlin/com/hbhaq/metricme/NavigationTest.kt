package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun profileDraftSurvivesRotationAndCancelDoesNotSave() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("You").performClick()
        val edit = hasText("Create profile") or hasText("Edit profile")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(edit)
        compose.onNode(edit).performClick()
        compose.onNodeWithText("What should we call you?").performTextInput("Unsaved draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Unsaved draft").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Unsaved draft").assertDoesNotExist()
    }

    @Test fun sampleModeIsClearlyLabeledAndDoesNotPopulatePersonalHistory() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Explore with sample data"))
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithText("SAMPLE DATA").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "screenshots/today-sample.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Exit demo").performClick()
        compose.onNodeWithText("You").performClick()
        compose.onNodeWithText("Alex").assertDoesNotExist()
    }
}
