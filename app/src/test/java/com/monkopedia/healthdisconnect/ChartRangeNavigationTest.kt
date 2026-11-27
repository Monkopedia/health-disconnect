package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.ChartRangeRoute
import com.monkopedia.healthdisconnect.ui.ChartRangeScreen
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.LocalClock
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #137: "Show Thu hour by hour" opens the same view zoomed to that day and back returns to
 * it, without touching the saved view's settings.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChartRangeNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)
    private val days = (0..7).map { today.minusDays(7L - it) }

    private val savedView = DataView(
        id = 7,
        type = ViewType.CHART,
        records = listOf(RecordSelection(fqn = PermissionsViewModel.CLASSES.first().qualifiedName!!)),
        chartSettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7)
    )
    private val storedView = MutableStateFlow(savedView)

    private val daily = HealthDataModel.MetricSeries(
        label = "Heart rate",
        unit = "bpm",
        points = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 70.0 + i) },
        bandMin = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 50.0 + i) },
        bandMax = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 130.0 + i) },
        aggregation = AggregationMode.MIN_MAX_AVG
    )
    private val zoomedDay = ChartRange(
        days[3].atStartOfDay(zone).toInstant(),
        days[4].atStartOfDay(zone).toInstant(),
        BucketSize.HOUR
    )
    private val hourly = HealthDataModel.MetricSeries(
        label = "Heart rate",
        unit = "bpm",
        points = (0 until 24).map { HealthDataModel.MetricPoint(zoomedDay.start.plusSeconds(it * 3_600L), 60.0 + it) },
        bucketSize = BucketSize.HOUR
    )

    private val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true).also {
        every { it.dataViews } returns MutableStateFlow(
            DataViewInfoList(dataViews = mapOf(7 to DataViewInfo(id = 7, name = "Heart rate")), ordering = listOf(7))
        )
        every { it.dataView(7) } returns storedView
        // Saving writes through, as the real store does, so a save would show in storedView.
        coEvery { it.updateView(any()) } answers { storedView.value = firstArg() }
    }
    private val healthDataModel = mockk<HealthDataModel>(relaxed = true).also {
        every { it.collectRecordCount(any()) } returns flowOf(HealthDataModel.RecordCount(8))
        every { it.collectAggregatedSeries(any()) } returns flowOf(listOf(daily))
        every { it.collectChartLoadIssues(any()) } returns flowOf(HealthDataModel.ChartLoadIssues())
        every { it.collectSeriesInRange(any(), any()) } returns
            flowOf(HealthDataModel.LoadedSeries(listOf(hourly), HealthDataModel.ChartLoadIssues()))
    }
    private val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true).also {
        every { it.grantedPermissions } returns MutableStateFlow(setOf(PermissionsViewModel.HISTORY_PERMISSION))
    }

    @Test
    fun theHourByHourViewOpensAndBackReturnsWithTheSavedViewUnchanged() {
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        NavHost(navController, startDestination = "views") {
                            composable("views") {
                                DataViewView(
                                    viewModel = viewModel,
                                    page = 0,
                                    healthDataModel = healthDataModel,
                                    permissionsViewModel = permissionsViewModel,
                                    onOpenRangeRequested = { viewId, range ->
                                        navController.navigate(ChartRangeRoute.of(viewId, range))
                                    }
                                )
                            }
                            composable(ChartRangeRoute.PATTERN, ChartRangeRoute.arguments) { entry ->
                                val arguments = entry.arguments!!
                                ChartRangeScreen(
                                    viewId = ChartRangeRoute.viewId(arguments),
                                    range = ChartRangeRoute.range(arguments)!!,
                                    onBack = { navController.popBackStack() },
                                    onOpenRange = {},
                                    viewModel = viewModel,
                                    healthDataModel = healthDataModel
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("data_view_chart_plot").performTouchInput {
            val pad = 8.dp.toPx()
            click(Offset(pad + (width - 2 * pad) * 3 / 7f, centerY))
        }
        composeRule.onNodeWithTag("chart_readout_zoom").performClick()

        composeRule.onNodeWithTag("chart_range_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Wed, Feb 11 · Hour by hour").assertIsDisplayed()
        // The zoom is asked of the saved view as it is, for that day alone.
        verify { healthDataModel.collectSeriesInRange(savedView, zoomedDay) }

        Espresso.pressBack()

        composeRule.onNodeWithTag("chart_range_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("data_view_chart_plot").assertIsDisplayed()
        // Nothing was saved, nothing is waiting to be saved, and the chart is the saved view's own.
        composeRule.onNodeWithTag("data_view_save_button").assertDoesNotExist()
        coVerify(exactly = 0) { viewModel.updateView(any()) }
        assertEquals(savedView, storedView.value)
        verify(exactly = 0) { healthDataModel.collectAggregatedSeries(match { it != savedView }) }
    }
}
