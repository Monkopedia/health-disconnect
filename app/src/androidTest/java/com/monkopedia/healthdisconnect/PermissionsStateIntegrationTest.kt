package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify

@RunWith(AndroidJUnit4::class)
class PermissionsStateIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sdkUnavailable_showsNoSdkScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_UNAVAILABLE
        every { permissionsViewModel.needsPermissions } returns flowOf(false)

        renderPermissionsRoot(permissionsViewModel)

        composeRule.onNodeWithText(app.getString(R.string.permissions_no_sdk_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.permissions_no_sdk_body)).assertIsDisplayed()
    }

    @Test
    fun providerUpdateRequired_showsUpdatePromptAndLaunchesUpdate() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        every { permissionsViewModel.needsPermissions } returns flowOf(false)
        every { permissionsViewModel.launchUpdate() } just Runs

        renderPermissionsRoot(permissionsViewModel)

        composeRule.onNodeWithText(app.getString(R.string.permissions_update_required_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.permissions_launch_update)).performClick()
        verify(exactly = 1) {
            permissionsViewModel.launchUpdate()
        }
    }

    @Test
    fun permissionDenied_showsRequestScreenAndIgnoreMovesForwardToContent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val needsPermissions = MutableStateFlow(true)
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_AVAILABLE
        every { permissionsViewModel.needsPermissions } returns needsPermissions
        every { permissionsViewModel.ignorePermissions() } answers {
            needsPermissions.value = false
        }

        val permittedText = "Permitted content"
        renderPermissionsRoot(permissionsViewModel, permittedText)

        composeRule.onNodeWithText(app.getString(R.string.permissions_grant)).assertIsDisplayed()
        assertTextDoesNotExist(permittedText)
        composeRule.onNodeWithText(app.getString(R.string.permissions_ignore)).performClick()
        composeRule.onNodeWithText(permittedText).assertIsDisplayed()
        verify(exactly = 1) {
            permissionsViewModel.ignorePermissions()
        }
    }

    @Test
    fun permissionGranted_showsPermittedContent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_AVAILABLE
        every { permissionsViewModel.needsPermissions } returns MutableStateFlow(false)

        val permittedText = "Permitted content"
        renderPermissionsRoot(permissionsViewModel, permittedText)

        composeRule.onNodeWithText(permittedText).assertIsDisplayed()
        assertTextDoesNotExist(app.getString(R.string.permissions_grant))
    }

    private fun assertTextDoesNotExist(text: String) {
        assertTrue(
            "Expected text '$text' to be absent from semantics tree",
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        )
    }

    private fun renderPermissionsRoot(
        permissionsViewModel: PermissionsViewModel,
        permittedContentText: String = "Permitted content"
    ) {
        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                PermissionsRoot(
                    permissionsViewModel = permissionsViewModel,
                    permittedContent = { PermittedContent(permittedContentText) }
                )
            }
        }
    }

    @Composable
    private fun PermittedContent(text: String) {
        Text(text = text)
    }
}
