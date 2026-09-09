package bes.max.bmaps

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

abstract class RasterMapChecks {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fixturePixelsRenderAfterNavigationAndRecreation() {
        compose.onNodeWithText("Build", useUnmergedTree = true).performClick()
        assertFixtureRendered()
        compose.activityRule.scenario.recreate()
        assertFixtureRendered()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your maps").assertIsDisplayed()
        compose.onNodeWithText("Build", useUnmergedTree = true).performClick()
        assertFixtureRendered()
    }

    private fun assertFixtureRendered() {
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("sample-map").captureToImage().toPixelMap()
            var waterPixels = 0
            for (y in 0 until pixels.height step 12) for (x in 0 until pixels.width step 12) {
                val color = pixels[x, y]
                if (color.red in 0.45f..0.57f && color.green in 0.70f..0.82f && color.blue in 0.79f..0.91f) waterPixels++
            }
            waterPixels > 20
        }
    }
}
