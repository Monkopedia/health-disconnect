package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
        val clicked = mutableListOf<Int>()

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface {
                    DataViewHeaderStrip(
                        titles = listOf("Steps", "Weight", "Create View"),
                        currentPage = 0,
                        currentPageOffsetFraction = 0f,
                        onPageClick = { clicked.add(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    )
                }
            }
        }

        for (index in 0..2) {
            val count = composeRule.onAllNodesWithTag("header_title_$index", useUnmergedTree = true).fetchSemanticsNodes().size
            assertEquals(1, count)
            val semantics = composeRule.onNodeWithTag("header_title_$index", useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals(true, semantics.contains(SemanticsProperties.TestTag))
            assertEquals(true, semantics.contains(SemanticsActions.OnClick))
        }

        for (index in 0..2) {
            composeRule.onNodeWithTag("header_title_$index", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
            composeRule.runOnIdle { assertEquals(index, clicked[index]) }
        }
    }
}
