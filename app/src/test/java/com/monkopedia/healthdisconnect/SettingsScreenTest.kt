package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.geometry.Rect
import androidx.health.connect.client.HealthConnectClient
import androidx.compose.ui.test.performClick
import androidx.health.connect.client.PermissionController
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
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
        val permissionsViewModel = mockPermissionsViewModel()

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

    @Test
    fun themeRowIsFullyClickable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockPermissionsViewModel()
        val appThemeViewModel = mockk<AppThemeViewModel>(relaxed = true)
        val themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
        every { appThemeViewModel.themeMode } returns themeMode
        every { appThemeViewModel.setThemeMode(any()) } answers {
            themeMode.value = firstArg()
        }

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                SettingsScreen(
                    onBack = {},
                    permissionsViewModel = permissionsViewModel,
                    appThemeViewModel = appThemeViewModel,
                    initialThemeDropdownExpanded = false,
                    previewDebugRows = emptyList()
                )
            }
        }

        composeRule.onNodeWithTag("settings_theme_row", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Dark", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_theme_value", useUnmergedTree = true)
            .assertTextEquals("Dark")
        verify { appThemeViewModel.setThemeMode(AppThemeMode.DARK) }
    }

    @Test
    fun dropdownAnchorPersistsSelectionAcrossOpenClose() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockPermissionsViewModel()
        val appThemeViewModel = mockk<AppThemeViewModel>(relaxed = true)
        val themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
        every { appThemeViewModel.themeMode } returns themeMode
        every { appThemeViewModel.setThemeMode(any()) } answers {
            themeMode.value = firstArg()
        }

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                SettingsScreen(
                    onBack = {},
                    permissionsViewModel = permissionsViewModel,
                    appThemeViewModel = appThemeViewModel,
                    initialThemeDropdownExpanded = false,
                    previewDebugRows = emptyList()
                )
            }
        }

        composeRule.onNodeWithTag("settings_theme_row", useUnmergedTree = true)
            .performClick()

        composeRule.onNodeWithText("Light", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Light", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_theme_value", useUnmergedTree = true)
            .assertTextEquals("Light")

        composeRule.onNodeWithTag("settings_theme_row", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("System", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dark", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun mockPermissionsViewModel(): PermissionsViewModel {
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_UNAVAILABLE
        every { permissionsViewModel.grantedPermissions } returns flowOf(emptySet())
        every { permissionsViewModel.requestPermissionActivityContract } returns
            PermissionController.createRequestPermissionResultContract()
        return permissionsViewModel
    }

    private fun assertBoundsAreNearlyAligned(left: Rect, right: Rect) {
        val delta = (left.center.y - right.center.y).absoluteValue
        assertTrue("Bounds are not vertically aligned: delta=$delta", delta < 4f)
    }
}
