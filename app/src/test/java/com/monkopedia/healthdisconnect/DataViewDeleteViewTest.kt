package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.viewIdStateDataStore
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deleting a view through the UI while its screen is still collecting it (#115). Uses the real
 * [DataViewAdapterViewModel] over an in-memory Room database, so the Room flow re-queries the
 * deleted row exactly as it does on a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewDeleteViewTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var appDb: AppDatabase

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        appDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            app.viewIdStateDataStore.edit { it.clear() }
            app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
        }
    }

    @After
    fun tearDown() {
        runBlocking { app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray()) }
        appDb.close()
    }

    @Test
    fun deletingTheOnScreenViewRemovesItWithoutCrashing() {
        openDeleteConfirmationForOnlyView()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        val remaining = runBlocking { appDb.dataViewInfoDao().allOrderedSnapshot() }
        assertEquals(emptyList<Any>(), remaining)
        assertEquals(null, runBlocking { appDb.dataViewDao().getById(1) })
    }

    /**
     * #104 review: the screen launches the delete from its own scope, and deleting the last view
     * closes the screen, which cancels that scope. An unbind that runs after the row delete in that
     * scope is cancelled with it, leaving the widget bound to the dead id.
     *
     * Whether the unbind's DataStore edit wins that race depends on thread timing, so the test holds
     * the widget-binding store busy until the screen has closed: any unbind still queued behind it
     * by then is exactly the one the cancellation drops.
     */
    @Test
    fun deletingTheLastViewFromItsScreenStillUnbindsItsWidget() {
        openDeleteConfirmationForOnlyView()
        // Bound once the confirmation is up: a bound view shows a different configuration panel.
        runBlocking { app.bindWidgetToView(appWidgetId = 42, viewId = 1) }

        val storeHeld = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val holder = thread {
            runBlocking {
                app.widgetBindingsDataStore.edit {
                    storeHeld.countDown()
                    releaseStore.await()
                }
            }
        }
        assertTrue(storeHeld.await(5, TimeUnit.SECONDS))
        try {
            composeRule.onNodeWithText("Delete").performClick()
            // With the unbind ordered first, the rows stay until the store is released; without it
            // they go now and the screen closes. Either way, give the screen the chance to close.
            runCatching {
                composeRule.waitUntil(timeoutMillis = 2_000) {
                    composeRule.onAllNodesWithTag("data_view_configuration_header")
                        .fetchSemanticsNodes().isEmpty()
                }
            }
            composeRule.waitForIdle()
        } finally {
            releaseStore.countDown()
            holder.join(5_000)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { appDb.dataViewInfoDao().allOrderedSnapshot() }.isEmpty()
        }
        repeat(10) {
            composeRule.mainClock.advanceTimeBy(100)
            Thread.sleep(25)
            composeRule.waitForIdle()
        }
        assertEquals(emptyMap<Int, Int>(), runBlocking { app.widgetBindingsSnapshot() })
    }

    private fun openDeleteConfirmationForOnlyView() {
        val viewModel = DataViewAdapterViewModel(
            app = app,
            savedStateHandle = SavedStateHandle(),
            appDatabase = appDb,
            dataViewDao = appDb.dataViewDao(),
            dataViewInfoDao = appDb.dataViewInfoDao(),
            runLegacyMigrationOnInit = false
        )
        runBlocking { viewModel.createView(WeightRecord::class) }

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        every { healthDataModel.collectRecordCount(any()) } returns emptyFlow()
        every { healthDataModel.collectAggregatedSeries(any()) } returns flowOf(emptyList())
        every { healthDataModel.aggregateMetricSeriesList(any(), any()) } returns emptyList()
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.grantedPermissions } returns MutableStateFlow(emptySet())

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DataViewAdapter(
                        viewModel = viewModel,
                        healthDataModel = healthDataModel,
                        permissionsViewModel = permissionsViewModel,
                        showSettings = {}
                    )
                }
            }
        }
        // The view row arrives from Room off the main thread, so idling alone does not wait for it.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("data_view_configuration_header")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("data_view_configuration_header").performClick()
        composeRule.onAllNodesWithContentDescription("Remove metric")[0].performClick()
    }
}
