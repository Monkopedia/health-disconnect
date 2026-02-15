package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Rect
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeRowLabelValueAndIconShareVerticalAlignment() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_UNAVAILABLE
        every { permissionsViewModel.grantedPermissions } returns flowOf(emptySet())
        every {
            permissionsViewModel.requestPermissionActivityContract
        } returns PermissionController.createRequestPermissionResultContract()

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                SettingsScreen(
                    onBack = {},
                    permissionsViewModel = permissionsViewModel,
                    appThemeViewModel = AppThemeViewModel(app),
                    initialThemeDropdownExpanded = false,
                    previewDebugRows = emptyList()
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_theme_label", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_theme_value", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_theme_icon", useUnmergedTree = true)
            .assertIsDisplayed()

        val labelBounds =
            composeRule.onNodeWithTag("settings_theme_label", useUnmergedTree = true).fetchSemanticsNode()
                .boundsInRoot
        val valueBounds =
            composeRule.onNodeWithTag("settings_theme_value", useUnmergedTree = true).fetchSemanticsNode()
                .boundsInRoot
        val iconBounds =
            composeRule.onNodeWithTag("settings_theme_icon", useUnmergedTree = true).fetchSemanticsNode()
                .boundsInRoot

        assertBoundsAreNearlyAligned(labelBounds, valueBounds)
        assertBoundsAreNearlyAligned(valueBounds, iconBounds)
    }

    private fun assertBoundsAreNearlyAligned(left: Rect, right: Rect) {
        val delta = (left.center.y - right.center.y).absoluteValue
        assertTrue("Bounds are not vertically aligned: delta=$delta", delta < 4f)
    }
}
