package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewHeaderInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickingNonSelectedHeaderInvokesPageSelection() {
        var selectedPage: Int? = null

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface {
                    DataViewHeaderStrip(
                        titles = listOf("Steps", "Weight", "Create View"),
                        currentPage = 0,
                        currentPageOffsetFraction = 0f,
                        onPageClick = { selectedPage = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    )
                }
            }
        }

        composeRule.onNodeWithTag("header_title_1", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectedPage)
        }

        composeRule.onNodeWithTag("header_title_2", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals(2, selectedPage)
        }
    }
}
