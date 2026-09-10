package bes.max.bmaps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

abstract class ShellNavigationChecks {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun useOfflineFixture() {
        compose.activity.intent.putExtra("fixture-map", true)
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun navigationAndDialogSurviveRecreationWithoutReplayingSave() {
        compose.onNodeWithText("Build", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose a place. Take it offline.").assertIsDisplayed()
        compose.onNodeWithText("Viewer", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Map viewer").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Map viewer").assertIsDisplayed()

        compose.onNodeWithText("Preferences").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dark").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Dark").assertIsSelected()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Map viewer").assertIsDisplayed()

        compose.onNodeWithText("Preferences").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Preferences").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Use device setting").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your maps").assertIsDisplayed()
    }
}
