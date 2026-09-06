package com.voxit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DemoModeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectingBottomScenarioKeepsSimulationBannerVisible() {
        composeRule.waitUntilAtLeastOneExists(hasText("Continue"), timeoutMillis = 5_000)
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Demo Mode").performScrollTo().performClick()
        composeRule.onNodeWithText("High combined risk").performScrollTo().performClick()

        composeRule.waitUntilAtLeastOneExists(
            hasContentDescription("Simulated demo, not a real detection"),
            timeoutMillis = 2_000,
        )
        composeRule.onNode(hasContentDescription("Simulated demo, not a real detection"))
            .assertIsDisplayed()
    }
}
