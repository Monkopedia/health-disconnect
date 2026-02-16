package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewEntriesUnitsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun entriesUseMetricUnitPreferenceForValuePreview() {
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = WeightRecord::class.qualifiedName
                        ?: error("WeightRecord missing qualified name"),
                    metricSettings = MetricChartSettings(unitPreference = UnitPreference.IMPERIAL)
                )
            )
        )
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        every { viewModel.dataViews } returns MutableStateFlow(
            DataViewInfoList(
                dataViews = mapOf(1 to DataViewInfo(id = 1, name = "Weight")),
                ordering = listOf(1)
            )
        )
        every { viewModel.dataView(1) } returns flowOf(view)

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        every { healthDataModel.collectData(any(), any()) } returns flowOf(
            listOf(
                fakeWeightRecord(72.0)
            )
        )

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EntriesRouteScreen(
                        viewId = 1,
                        onBack = {},
                        viewModel = viewModel,
                        healthDataModel = healthDataModel
                    )
                }
            }
        }

        composeRule.onNodeWithTag("entries_item_row_0").assertIsDisplayed()
        composeRule.onNodeWithText("pounds", substring = true).assertIsDisplayed()
    }

    private fun fakeWeightRecord(kilograms: Double): WeightRecord {
        val record = mockk<WeightRecord>(relaxed = true)
        every { record.weight } returns Mass.kilograms(kilograms)
        every { record.time } returns Instant.parse("2026-02-10T12:00:00Z")
        return record
    }
}
