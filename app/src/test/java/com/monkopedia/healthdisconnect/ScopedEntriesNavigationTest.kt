package com.monkopedia.healthdisconnect

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.ChartRangeRoute
import com.monkopedia.healthdisconnect.ui.ChartRangeScreen
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import com.monkopedia.healthdisconnect.ui.LocalClock
import com.monkopedia.healthdisconnect.ui.MetricOverTimeChart
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #139: a selected bucket's "View entries" opens the entries of that bucket alone, back
 * returns to the chart, and the saved view is never touched.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScopedEntriesNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)
    private val mondays = (0..3).map { LocalDate.of(2026, 1, 19).plusWeeks(it.toLong()) }
    private fun midnight(day: LocalDate): Instant = day.atStartOfDay(zone).toInstant()

    private val savedView = DataView(
        id = 7,
        type = ViewType.CHART,
        records = listOf(RecordSelection(fqn = WeightRecord::class.qualifiedName!!)),
        chartSettings = ChartSettings(
            aggregation = AggregationMode.SUM,
            timeWindow = TimeWindow.DAYS_30,
            bucketSize = BucketSize.WEEK,
            chartType = ChartType.BARS
        )
    )
    private val storedView = MutableStateFlow(savedView)

    private val weekly = HealthDataModel.MetricSeries(
        label = "Weight",
        unit = "kg",
        points = mondays.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 70.0 + i) },
        bucketSize = BucketSize.WEEK,
        aggregation = AggregationMode.SUM
    )

    /** The second bar: the week of Mon Jan 26. */
    private val secondWeek = ChartRange(midnight(mondays[1]), midnight(mondays[2]), BucketSize.WEEK)
    private val weekEntries = listOf(
        weight(72.4, mondays[1].plusDays(3).atTime(8, 30).atZone(zone).toInstant()),
        weight(71.8, mondays[1].plusDays(1).atTime(8, 30).atZone(zone).toInstant())
    )

    private fun weight(kilograms: Double, at: Instant) = WeightRecord(
        time = at,
        zoneOffset = ZoneOffset.UTC,
        weight = Mass.kilograms(kilograms),
        metadata = Metadata.manualEntry()
    )

    private fun viewModel(infos: Map<Int, DataViewInfo> = mapOf(7 to DataViewInfo(id = 7, name = "Weight"))) =
        mockk<DataViewAdapterViewModel>(relaxed = true).also {
            every { it.dataViews } returns MutableStateFlow(DataViewInfoList(dataViews = infos, ordering = infos.keys.toList()))
            every { it.dataView(7) } returns storedView
            // Saving writes through, as the real store does, so a save would show in storedView.
            coEvery { it.updateView(any()) } answers { storedView.value = firstArg() }
        }

    private val healthDataModel = mockk<HealthDataModel>(relaxed = true).also {
        every { it.collectRecordCount(any()) } returns flowOf(HealthDataModel.RecordCount(8))
        every { it.collectAggregatedSeries(any()) } returns flowOf(listOf(weekly))
        every { it.collectChartLoadIssues(any()) } returns flowOf(HealthDataModel.ChartLoadIssues())
        every { it.collectData(any(), any()) } returns flowOf(emptyList())
        every { it.collectEntriesInRange(any(), any()) } returns
            flowOf(HealthDataModel.LoadedEntries(weekEntries, HealthDataModel.ChartLoadIssues()))
    }
    private val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true).also {
        every { it.grantedPermissions } returns MutableStateFlow(setOf(PermissionsViewModel.HISTORY_PERMISSION))
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    @Test
    fun aSelectedBarOpensItsWeeksEntriesAndBackReturnsWithTheSavedViewUnchanged() {
        val viewModel = viewModel()
        setContent {
            val navController = rememberNavController()
            NavHost(navController, startDestination = "views") {
                composable("views") {
                    DataViewView(
                        viewModel = viewModel,
                        page = 0,
                        healthDataModel = healthDataModel,
                        permissionsViewModel = permissionsViewModel,
                        onOpenEntriesInRangeRequested = { viewId, range ->
                            navController.navigate(ChartRangeRoute.entriesOf(viewId, range))
                        }
                    )
                }
                composable(ChartRangeRoute.ENTRIES_PATTERN, ChartRangeRoute.arguments) { entry ->
                    val arguments = entry.arguments!!
                    EntriesRouteScreen(
                        viewId = ChartRangeRoute.viewId(arguments),
                        range = ChartRangeRoute.range(arguments),
                        onBack = { navController.popBackStack() },
                        viewModel = viewModel,
                        healthDataModel = healthDataModel
                    )
                }
            }
        }

        // Bars sit at the centres of four equal slots; the second is the week of Jan 26.
        composeRule.onNodeWithTag("data_view_chart_plot").performTouchInput {
            val pad = 8.dp.toPx()
            click(Offset(pad + (width - 2 * pad) * 1.5f / 4f, centerY))
        }
        composeRule.onNodeWithTag("chart_readout_view_entries").performClick()

        composeRule.onNodeWithTag("entries_range_label").assertTextEquals("Jan 26 – Feb 1")
        // The week's entries are read for the week alone, from the saved view as it is.
        verify { healthDataModel.collectEntriesInRange(savedView, secondWeek) }
        composeRule.onNodeWithTag("entries_item_row_1").assertIsDisplayed()
        // The export covers the view's whole window, so a range's list does not offer it.
        composeRule.onNodeWithTag("entries_share_button").assertDoesNotExist()

        // Entry Details and copy still work on the scoped list.
        composeRule.onNodeWithTag("entries_item_row_0").performClick()
        composeRule.onNodeWithText("Entry Details").assertIsDisplayed()
        composeRule.onNodeWithText("Entry details copied").assertIsDisplayed()
        composeRule.onNodeWithTag("entries_details_close").performClick()
        composeRule.onNodeWithText("Entry Details").assertDoesNotExist()

        Espresso.pressBack()

        composeRule.onNodeWithTag("entries_range_label").assertDoesNotExist()
        composeRule.onNodeWithTag("data_view_chart_plot").assertIsDisplayed()
        composeRule.onNodeWithTag("data_view_save_button").assertDoesNotExist()
        coVerify(exactly = 0) { viewModel.updateView(any()) }
        assertEquals(savedView, storedView.value)
    }

    @Test
    fun aRangesTruncatedListSaysWhereItStops() {
        val since = mondays[1].plusDays(2).atTime(9, 15).atZone(zone).toInstant()
        every { healthDataModel.collectEntriesInRange(any(), any()) } returns flowOf(
            HealthDataModel.LoadedEntries(weekEntries, HealthDataModel.ChartLoadIssues(truncatedSince = since))
        )
        setContent {
            EntriesRouteScreen(
                viewId = 7,
                range = secondWeek,
                onBack = {},
                viewModel = viewModel(),
                healthDataModel = healthDataModel
            )
        }

        composeRule.onNodeWithText("back to Jan 28, 2026, 9:15", substring = true).assertIsDisplayed()
    }

    /** The range "View entries" opens for [slot] of a lone [series] drawn with [settings]. */
    private fun openedRange(series: HealthDataModel.MetricSeries, settings: ChartSettings, slot: Int): ChartRange? {
        var opened: ChartRange? = null
        var selection by mutableStateOf<Int?>(slot)
        setContent {
            MetricOverTimeChart(
                seriesList = listOf(series),
                settings = settings,
                selectedSlot = selection,
                onSelectedSlotChange = { selection = it },
                onOpenEntries = { opened = it },
                onOpenRange = {}
            )
        }
        composeRule.onNodeWithTag("chart_readout_view_entries").performClick()
        return opened
    }

    @Test
    fun aSelectedBandBucketOpensItsDaysEntries() {
        val days = (0..7).map { today.minusDays(7L - it) }
        val band = HealthDataModel.MetricSeries(
            label = "Heart rate",
            unit = "bpm",
            points = days.map { HealthDataModel.MetricPoint(it, 70.0) },
            bandMin = days.map { HealthDataModel.MetricPoint(it, 50.0) },
            bandMax = days.map { HealthDataModel.MetricPoint(it, 130.0) },
            aggregation = AggregationMode.MIN_MAX_AVG
        )

        val range = openedRange(band, ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7), 3)

        // The band keeps its hour-by-hour link beside it.
        composeRule.onNodeWithTag("chart_readout_zoom").assertIsDisplayed()
        assertEquals(ChartRange(midnight(days[3]), midnight(days[4]), BucketSize.DAY), range)
    }

    @Test
    fun aSelectedHourlyPointOpensItsHoursEntries() {
        val start = midnight(today.minusDays(1))
        val hourly = HealthDataModel.MetricSeries(
            label = "Heart rate",
            unit = "bpm",
            points = (0 until 24).map { HealthDataModel.MetricPoint(start.plusSeconds(it * 3_600L), 60.0 + it) },
            bucketSize = BucketSize.HOUR
        )

        val range = openedRange(hourly, ChartSettings(timeWindow = TimeWindow.HOURS_24, bucketSize = BucketSize.HOUR), 5)

        assertEquals(ChartRange(start.plusSeconds(5 * 3_600L), start.plusSeconds(6 * 3_600L), BucketSize.HOUR), range)
    }

    @Test
    fun aSelectedMinutePointOpensThatMinutesEntries() {
        // A minute is the finest bucket: its point is the readings of that minute.
        val start = midnight(today).minusSeconds(3_600L)
        val minutes = HealthDataModel.MetricSeries(
            label = "Heart rate",
            unit = "bpm",
            points = (0 until 60).map { HealthDataModel.MetricPoint(start.plusSeconds(it * 60L), 60.0 + it) },
            bucketSize = BucketSize.MINUTE
        )

        val range = openedRange(minutes, ChartSettings(timeWindow = TimeWindow.HOURS_1, bucketSize = BucketSize.MINUTE), 7)

        assertEquals(ChartRange(start.plusSeconds(7 * 60L), start.plusSeconds(8 * 60L), BucketSize.MINUTE), range)
    }

    @Test
    fun aDeletedViewSaysSoInsteadOfLoadingForever() {
        every { healthDataModel.collectEntriesInRange(any(), any()) } returns emptyFlow()
        val gone = mockk<DataViewAdapterViewModel>(relaxed = true).also {
            every { it.dataViews } returns MutableStateFlow(DataViewInfoList(dataViews = emptyMap(), ordering = emptyList()))
            // A deleted view's row is gone, so its flow never emits.
            every { it.dataView(7) } returns emptyFlow()
        }
        var backs = 0
        var showChart by mutableStateOf(false)
        setContent {
            if (showChart) {
                ChartRangeScreen(7, secondWeek, onBack = { backs++ }, onOpenRange = {}, viewModel = gone, healthDataModel = healthDataModel)
            } else {
                EntriesRouteScreen(7, onBack = { backs++ }, viewModel = gone, healthDataModel = healthDataModel, range = secondWeek)
            }
        }

        composeRule.onNodeWithText("This view no longer exists.").assertIsDisplayed()
        composeRule.onNodeWithTag("route_unavailable_back").performClick()
        showChart = true
        composeRule.onNodeWithText("This view no longer exists.").assertIsDisplayed()
        composeRule.onNodeWithTag("route_unavailable_back").performClick()

        assertEquals(2, backs)
    }

    @Test
    fun aRouteRangeThatCannotBeABucketIsRejected() {
        fun arguments(start: Long, end: Long, bucket: String?) = Bundle().apply {
            putInt("viewId", 7)
            putLong("start", start)
            putLong("end", end)
            putString("bucket", bucket)
        }
        val week = secondWeek.start.toEpochMilli() to secondWeek.end.toEpochMilli()

        assertEquals(secondWeek, ChartRangeRoute.range(arguments(week.first, week.second, "WEEK")))
        assertNull(ChartRangeRoute.range(arguments(week.first, week.second, "FORTNIGHT")))
        assertNull(ChartRangeRoute.range(arguments(week.first, week.second, null)))
        assertNull(ChartRangeRoute.range(arguments(week.second, week.first, "WEEK")))
        assertNull(ChartRangeRoute.range(arguments(week.first, week.first, "WEEK")))
        // The route the readout builds parses back to the same range.
        assertEquals("entries/7/${week.first}/${week.second}/WEEK", ChartRangeRoute.entriesOf(7, secondWeek))
    }
}
