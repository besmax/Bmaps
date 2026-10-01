/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
    fun settingsApplyImmediatelyAndSurviveRecreationAndBack() {
        compose.onNodeWithText("Build a map").performClick()
        compose.onNodeWithText("Choose a place. Take it offline.").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Choose a place. Take it offline.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dark").performClick()
        compose.activityRule.scenario.recreate()
        waitForSelectedTheme("Dark")
        compose.onNodeWithText("Dark").assertIsSelected()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Choose a place. Take it offline.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        waitForSelectedTheme("Dark")
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dark")).fetchSemanticsNodes().isNotEmpty() }
        waitForSelectedTheme("Light")
        compose.onNodeWithText("Light").assertIsSelected()
        compose.onNodeWithText("System").performClick()
        compose.onNodeWithText("Version code:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Your maps").assertIsDisplayed()
    }

    private fun waitForSelectedTheme(label: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(label) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }
}
