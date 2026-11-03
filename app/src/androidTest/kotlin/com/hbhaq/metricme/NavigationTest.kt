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
        compose.onNode(hasText("Setup") and hasClickAction()).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Profile & measurements"))
        compose.onNodeWithText("Profile & measurements").performClick()
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
        compose.onNode(hasText("Setup") and hasClickAction()).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Explore demo mode"))
        compose.onNodeWithContentDescription("Explore demo mode").performClick()
        compose.onNodeWithText("SAMPLE DATA").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "screenshots/today-sample.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Exit demo").performClick()
        compose.onNode(hasText("Setup") and hasClickAction()).performClick()
        compose.onNodeWithText("Alex").assertDoesNotExist()
    }

    @Test fun setupContainsAccountAndKeepsTechnicalFieldsCollapsed() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Setup") and hasClickAction()).performClick()
        compose.onNodeWithText("Journal & AI account").assertExists()
        compose.onNodeWithText("Sign in with a code").assertExists()
        compose.onNodeWithText("Backend HTTPS URL").assertDoesNotExist()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val values=android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"setup.png"); put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png"); put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/metricme-ci") }
            val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            context.contentResolver.openOutputStream(uri)!!.use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNode(hasText("Journal") and hasClickAction()).performClick()
        compose.onNodeWithText("Journal & AI account").assertDoesNotExist()
        compose.onNodeWithText("Connect your journal").performClick()
        compose.onNodeWithText("Journal & AI account").assertExists()
    }

    @Test fun journalMergesCaptureWithHistoryAndKeepsTypedDraftThroughRotation() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Tell MetricMe…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Record a voice note").assertExists()
        compose.onNodeWithContentDescription("Attach a photo").assertExists()
        compose.onNode(hasText("AI") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Tell MetricMe…").performTextInput("I slept seven hours and had eggs")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("I slept seven hours and had eggs").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val values=android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"journal.png"); put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png"); put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/metricme-ci") }
            val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            context.contentResolver.openOutputStream(uri)!!.use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
