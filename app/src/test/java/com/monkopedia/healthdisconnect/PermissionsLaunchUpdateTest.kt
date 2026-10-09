package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Update button on the update-required screen (issue #116). That screen is what Android 9-13
 * users without a current Health Connect see first, so the button must never crash: it is started
 * from the Application context, and the Play Store may not exist (F-Droid / de-Googled devices).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionsLaunchUpdateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var viewModel: PermissionsViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = PermissionsViewModel(context = app, healthConnectClient = null)
    }

    @Test
    fun `launchUpdate starts the Play Store as a new task from the application context`() {
        viewModel.launchUpdate()

        val started = checkNotNull(shadowOf(app).nextStartedActivity) { "no activity was started" }
        assertEquals("com.android.vending", started.`package`)
        assertEquals("market", started.data?.scheme)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `launchUpdate falls back to the web listing when there is no Play Store`() {
        shadowOf(app).checkActivities(true)
        val webListing = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
        )
        shadowOf(app.packageManager).addResolveInfoForIntent(webListing, browserResolveInfo())

        viewModel.launchUpdate()

        val started = checkNotNull(shadowOf(app).nextStartedActivity) { "no activity was started" }
        assertEquals(webListing.data, started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(viewModel.updateUnavailable.value)
    }

    @Test
    fun `launchUpdate reports unavailable instead of crashing when nothing can open a listing`() {
        shadowOf(app).checkActivities(true)

        viewModel.launchUpdate()

        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(viewModel.updateUnavailable.value)
    }

    @Test
    fun `update screen tells the user to install Health Connect when unavailable`() {
        composeRule.setContent { UpdateRequired(updateUnavailable = true) }

        composeRule.onNodeWithText(app.getString(R.string.permissions_update_unavailable))
            .assertIsDisplayed()
    }

    private fun browserResolveInfo() = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = "org.example.browser"
            name = "org.example.browser.BrowserActivity"
        }
    }
}
