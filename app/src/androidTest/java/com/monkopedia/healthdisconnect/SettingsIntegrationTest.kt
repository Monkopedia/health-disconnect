package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun themeDropdownCanBeExpandedAndSelectionPersists() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockPermissionsViewModel()
        val appThemeViewModel = mockk<AppThemeViewModel>(relaxed = true)
        val currentTheme = MutableStateFlow(AppThemeMode.DARK)
        every { appThemeViewModel.themeMode } returns currentTheme
        every { appThemeViewModel.setThemeMode(any()) } answers {
            currentTheme.value = firstArg<AppThemeMode>()
        }

        composeRule.setContent {
            HealthDisconnectTheme {
                SettingsScreen(
                    permissionsViewModel = permissionsViewModel,
                    appThemeViewModel = appThemeViewModel
                )
            }
        }

        composeRule.onNodeWithTag("settings_theme_row").performClick()
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_system)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_dark)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_light)).performClick()
        verify {
            appThemeViewModel.setThemeMode(AppThemeMode.LIGHT)
        }
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_light)).assertIsDisplayed()
        assertEquals(AppThemeMode.LIGHT, currentTheme.value)
    }

    @Test
    fun themeLabelCanBeChangedToSystem() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockPermissionsViewModel()
        val appThemeViewModel = mockk<AppThemeViewModel>(relaxed = true)
        val currentTheme = MutableStateFlow(AppThemeMode.LIGHT)
        every { appThemeViewModel.themeMode } returns currentTheme
        every { appThemeViewModel.setThemeMode(any()) } answers {
            currentTheme.value = firstArg<AppThemeMode>()
        }

        composeRule.setContent {
            HealthDisconnectTheme {
                SettingsScreen(
                    permissionsViewModel = permissionsViewModel,
                    appThemeViewModel = appThemeViewModel
                )
            }
        }

        composeRule.onNodeWithTag("settings_theme_row").performClick()
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_system)).performClick()
        verify {
            appThemeViewModel.setThemeMode(AppThemeMode.SYSTEM)
        }
        composeRule.onNodeWithText(app.getString(R.string.settings_theme_system)).assertIsDisplayed()
        assertEquals(AppThemeMode.SYSTEM, currentTheme.value)
    }

    private fun mockPermissionsViewModel(): PermissionsViewModel {
        val flow = MutableStateFlow(emptySet<String>())
        return mockk<PermissionsViewModel>(relaxed = true).apply {
            every { availabilityStatus } returns HealthConnectClient.SDK_AVAILABLE
            every { grantedPermissions } returns flow as Flow<Set<String>>
            every { requestPermissionActivityContract } returns PermissionController.createRequestPermissionResultContract()
            every { onResult(any()) } just Runs
        }
    }
}
