package com.hbhaq.metricme

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.ai.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class AiFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun openAssist() {
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("You").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Photo, audio & private drafts"))
        compose.onNodeWithText("Photo, audio & private drafts").performClick()
    }
    @Test fun offlineDraftEditsSurviveRotationWithoutUploading()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=EntryPointAccessors.fromApplication(context,AiEntryPoint::class.java)
        val id=graph.drafts().create("FOOD",null,"image/jpeg")
        try {
            openAssist()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open draft")); compose.onNodeWithText("Open draft").performClick()
            compose.onNodeWithText("Optional context").performTextInput("Offline draft, no upload consent")
            compose.activityRule.scenario.recreate(); compose.onNodeWithText("Offline draft, no upload consent").assertExists()
            compose.onNodeWithText("Save review changes").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Save review changes").fetchSemanticsNodes().isEmpty() }
            assertTrue(graph.drafts().dao.draft(id)!!.content.contains("Offline draft, no upload consent"))
            assertNull(graph.drafts().dao.job(id))
            compose.onNodeWithText("Close draft").performClick()
        } finally { graph.queue().cancel(id,true) }
    }
}
