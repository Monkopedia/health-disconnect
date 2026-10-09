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
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    }

    @After
    fun tearDown() {
        appDb.close()
    }

    @Test
    fun deletingTheOnScreenViewRemovesItWithoutCrashing() {
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
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        val remaining = runBlocking { appDb.dataViewInfoDao().allOrderedSnapshot() }
        assertEquals(emptyList<Any>(), remaining)
        assertEquals(null, runBlocking { appDb.dataViewDao().getById(1) })
    }
}
