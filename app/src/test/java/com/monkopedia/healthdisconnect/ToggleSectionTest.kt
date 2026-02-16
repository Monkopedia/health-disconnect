package com.monkopedia.healthdisconnect

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.ui.ToggleSection
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.Text

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ToggleSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rapidHeaderClicksApplyQueuedParityWithoutDesync() {
        composeRule.mainClock.autoAdvance = false
        val expanded = mutableStateOf(false)

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                ToggleSection(
                    labelText = "Section",
                    visibleState = expanded,
                    headerTestTag = "toggle_header"
                ) {
                    Text("Body", modifier = Modifier.testTag("toggle_body"))
                }
            }
        }

        composeRule.onNodeWithTag("toggle_header").performClick()
        composeRule.onNodeWithTag("toggle_header").performClick()
        composeRule.onNodeWithTag("toggle_header").performClick()

        composeRule.mainClock.advanceTimeBy(1200)
        composeRule.waitForIdle()

        assertTrue(expanded.value)
        composeRule.onNodeWithTag("toggle_body").assertIsDisplayed()
    }

    @Test
    fun rapidEvenClicksEndCollapsed() {
        composeRule.mainClock.autoAdvance = false
        val expanded = mutableStateOf(false)

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                ToggleSection(
                    labelText = "Section",
                    visibleState = expanded,
                    headerTestTag = "toggle_header"
                ) {
                    Text("Body", modifier = Modifier.testTag("toggle_body"))
                }
            }
        }

        composeRule.onNodeWithTag("toggle_header").performClick()
        composeRule.onNodeWithTag("toggle_header").performClick()

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()

        assertFalse(expanded.value)
        val bodies = composeRule
            .onAllNodesWithTag("toggle_body", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(bodies.isEmpty())
    }
}
