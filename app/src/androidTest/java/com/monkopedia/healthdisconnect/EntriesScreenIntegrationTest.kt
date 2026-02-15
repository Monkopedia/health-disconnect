package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class EntriesScreenIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun entriesScreenShowsRowsAndBackAction() {
        var backPressed = false
        val (viewModel, healthDataModel) = createEntriesHarness()
        val fakeRecord = fakeWeightRecord(73.0)
        val app = ApplicationProvider.getApplicationContext<Application>()

        every { healthDataModel.collectData(any(), any()) } returns flowOf(listOf(fakeRecord))

        composeRule.setContent {
            HealthDisconnectTheme {
                EntriesRouteScreen(
                    viewId = 1,
                    onBack = { backPressed = true },
                    viewModel = viewModel,
                    healthDataModel = healthDataModel
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.data_view_entries_for, "Steps")).assertIsDisplayed()
        composeRule.onNodeWithTag("entries_item_row_0").assertIsDisplayed()
        composeRule.onNodeWithTag("entries_back_button").performClick()
        assertTrue(backPressed)
    }

    @Test
    fun entryClickShowsDetailsDialogAndCopySnackbar() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val (viewModel, healthDataModel) = createEntriesHarness()
        val fakeRecord = fakeWeightRecord(68.5)
        every { healthDataModel.collectData(any(), any()) } returns flowOf(listOf(fakeRecord))

        composeRule.setContent {
            HealthDisconnectTheme {
                EntriesRouteScreen(
                    viewId = 1,
                    onBack = {},
                    viewModel = viewModel,
                    healthDataModel = healthDataModel
                )
            }
        }

        composeRule.onNodeWithTag("entries_item_row_0").performClick()
        composeRule.onNodeWithText(app.getString(R.string.data_view_entry_details_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.data_view_entry_copied)).assertIsDisplayed()
        composeRule.onNodeWithTag("entries_details_close").performClick()
        assertFalse(
            composeRule.onAllNodesWithText(app.getString(R.string.data_view_entry_details_title))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    private fun fakeWeightRecord(kilograms: Double): WeightRecord {
        val record = mockk<WeightRecord>(relaxed = true)
        every { record.weight } returns Mass.kilograms(kilograms)
        every { record.time } returns Instant.parse("2026-02-10T12:00:00Z")
        return record
    }

    private fun createEntriesHarness(): Pair<DataViewAdapterViewModel, HealthDataModel> {
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        every { viewModel.dataViews } returns MutableStateFlow(
            mapOf(
                1 to DataViewInfo(
                    id = 1,
                    name = "Steps"
                )
            )
        ).map { dataViewInfoList ->
            com.monkopedia.healthdisconnect.model.DataViewInfoList(
                dataViews = dataViewInfoList,
                ordering = listOf(1)
            )
        }
        every { viewModel.dataView(1) } returns flowOf(
            DataView(
                id = 1,
                type = ViewType.CHART,
                records = listOf(RecordSelection(WeightRecord::class)),
                alwaysShowEntries = false
            )
        )

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        return viewModel to healthDataModel
    }
}
