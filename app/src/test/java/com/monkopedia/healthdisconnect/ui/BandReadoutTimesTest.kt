package com.monkopedia.healthdisconnect.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.CoroutineDispatcherProvider
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthConnectGateway
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.HealthDataModel.BandExtremeTimes
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.TimeProvider
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #137: a selected band bucket shows when its max and min were read. The times travel with
 * the series from the fold that plotted them, so selecting reads nothing from Health Connect.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BandReadoutTimesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)
    private val days = (0..7).map { today.minusDays(7L - it) }

    private fun at(day: LocalDate, hour: Int, minute: Int): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    /** Eight daily buckets across a 7-day window, each max read at 4:05 PM and min at 3:12 AM. */
    private fun dailyBand(bandTimes: List<BandExtremeTimes>? = days.map { BandExtremeTimes(at(it, 16, 5), at(it, 3, 12)) }) =
        HealthDataModel.MetricSeries(
            label = "Heart rate",
            unit = "bpm",
            points = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 70.0 + i) },
            bandMin = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 50.0 + i) },
            bandMax = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 130.0 + i) },
            aggregation = AggregationMode.MIN_MAX_AVG,
            bandTimes = bandTimes
        )

    /** Four Monday-started weeks; each max falls on the week's Tuesday afternoon, each min Sunday night. */
    private fun weeklyBand(bandTimesAreDays: Boolean): HealthDataModel.MetricSeries {
        val mondays = (0..3).map { LocalDate.of(2026, 1, 19).plusWeeks(it.toLong()) }
        return HealthDataModel.MetricSeries(
            label = "Steps",
            unit = "count",
            points = mondays.map { HealthDataModel.MetricPoint(it, 8_000.0) },
            bandMin = mondays.map { HealthDataModel.MetricPoint(it, 2_000.0) },
            bandMax = mondays.map { HealthDataModel.MetricPoint(it, 14_000.0) },
            bucketSize = BucketSize.WEEK,
            aggregation = AggregationMode.MIN_MAX_AVG,
            bandTimes = mondays.map {
                if (bandTimesAreDays) {
                    BandExtremeTimes(it.plusDays(1).atStartOfDay(zone).toInstant(), it.plusDays(6).atStartOfDay(zone).toInstant())
                } else {
                    BandExtremeTimes(at(it.plusDays(1), 16, 5), at(it.plusDays(6), 23, 40))
                }
            },
            bandTimesAreDays = bandTimesAreDays
        )
    }

    private var selection by mutableStateOf<Int?>(null)
    private val openedRanges = mutableListOf<ChartRange>()

    private fun setChart(series: HealthDataModel.MetricSeries, settings: ChartSettings) {
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        MetricOverTimeChart(
                            seriesList = listOf(series),
                            settings = settings,
                            selectedSlot = selection,
                            onSelectedSlotChange = { selection = it },
                            onOpenRange = { openedRanges += it }
                        )
                    }
                }
            }
        }
    }

    private val dailySettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7)

    /** X of [slot] inside the plot node: the canvas insets its plot by 8dp on each side. */
    private fun TouchInjectionScope.slotX(slot: Int): Float = plotX(slot / 7f)

    private fun TouchInjectionScope.plotX(fraction: Float): Float {
        val pad = 8.dp.toPx()
        return pad + (width - 2 * pad) * fraction
    }

    private val weeklySettings = ChartSettings(timeWindow = TimeWindow.DAYS_30, bucketSize = BucketSize.WEEK)

    /** Selects the last of the four weeks, the one starting Monday Feb 9. */
    private fun selectLastWeek(series: HealthDataModel.MetricSeries) {
        val geometry = ChartGeometry.create(
            listOf(series),
            weeklySettings,
            ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            clock.instant(),
            zone
        )
        plot().performTouchInput { click(Offset(plotX(geometry.slotXFraction(3)), centerY)) }
    }

    private fun plot() = composeRule.onNodeWithTag("data_view_chart_plot")

    private fun text(value: String) = composeRule.onNodeWithText(value, useUnmergedTree = true)

    private fun dayDate(day: LocalDate) =
        "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} Feb ${day.dayOfMonth}"

    @Test
    fun aSelectedBandBucketShowsWhenItsMaxAndMinWereRead() {
        setChart(dailyBand(), dailySettings)

        plot().performTouchInput { click(Offset(slotX(3), centerY)) }

        text("133 bpm").assertIsDisplayed()
        text("4:05 PM").assertIsDisplayed()
        text("3:12 AM").assertIsDisplayed()
    }

    @Test
    fun aBandWithoutTimesShowsNoneAndNoNote() {
        // A smoothed band carries no times: the values stand alone.
        setChart(dailyBand(bandTimes = null), dailySettings)

        plot().performTouchInput { click(Offset(slotX(3), centerY)) }

        text("133 bpm").assertIsDisplayed()
        text("4:05 PM").assertDoesNotExist()
        composeRule.onNodeWithText("unavailable", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aWeekBucketNamesTheDayAsWellAsTheTime() {
        val series = weeklyBand(bandTimesAreDays = false)
        setChart(series, weeklySettings)

        selectLastWeek(series)

        text("${dayDate(LocalDate.of(2026, 2, 10))}, 4:05 PM").assertIsDisplayed()
        text("${dayDate(LocalDate.of(2026, 2, 15))}, 11:40 PM").assertIsDisplayed()
    }

    @Test
    fun aWeekOfDailyStepTotalsNamesOnlyTheDay() {
        val series = weeklyBand(bandTimesAreDays = true)
        setChart(series, weeklySettings)

        selectLastWeek(series)

        text(dayDate(LocalDate.of(2026, 2, 10))).assertIsDisplayed()
        text(dayDate(LocalDate.of(2026, 2, 15))).assertIsDisplayed()
        composeRule.onNodeWithText("12:00 AM", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun selectingABandBucketReadsNothingFromHealthConnect() {
        // The real model and the real view, over a Health Connect that counts its reads.
        var reads = 0
        val records = days.dropLast(1).flatMapIndexed { i, day ->
            listOf(3 to 12, 9 to 0, 16 to 5).mapIndexed { which, (hour, minute) ->
                WeightRecord(
                    time = at(day, hour, minute),
                    zoneOffset = zone.rules.getOffset(at(day, hour, minute)),
                    weight = Mass.kilograms(listOf(60.0, 70.0, 80.0)[which] + i),
                    metadata = Metadata.manualEntry()
                )
            }
        }
        val gateway = mockk<HealthConnectGateway> {
            coEvery { readRecordsInRange(any(), any(), any(), any(), any(), any()) } answers {
                reads++
                arg<(List<Record>) -> Unit>(5).invoke(records.sortedByDescending { it.time })
            }
        }
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            healthConnectGateway = gateway,
            // Loads run where the test can wait them out.
            dispatchers = object : CoroutineDispatcherProvider {
                override val io = Dispatchers.Unconfined
                override val main = Dispatchers.Main
                override val default = Dispatchers.Unconfined
            },
            timeProvider = object : TimeProvider {
                override fun now() = clock.instant()
                override fun zone() = zone
            }
        )
        val settings = MetricChartSettings(
            aggregation = AggregationMode.MIN_MAX_AVG,
            timeWindow = TimeWindow.DAYS_7,
            unitPreference = UnitPreference.METRIC
        )
        val view = DataView(
            id = 3,
            type = ViewType.CHART,
            records = listOf(RecordSelection(fqn = WeightRecord::class.qualifiedName!!, metricSettings = settings)),
            chartSettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7)
        )
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true) {
            every { dataViews } returns MutableStateFlow(
                DataViewInfoList(dataViews = mapOf(3 to DataViewInfo(id = 3, name = "Weight")), ordering = listOf(3))
            )
            every { dataView(3) } returns MutableStateFlow(view)
        }
        val permissions = mockk<PermissionsViewModel>(relaxed = true) {
            every { grantedPermissions } returns MutableStateFlow(setOf(PermissionsViewModel.HISTORY_PERMISSION))
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        DataViewView(viewModel = viewModel, page = 0, healthDataModel = model, permissionsViewModel = permissions)
                    }
                }
            }
        }
        plot().assertIsDisplayed()
        val readsToLoad = reads

        // Feb 11, the fourth day with data, sits at 3/7 of the week-long axis.
        plot().performTouchInput { click(Offset(slotX(3), centerY)) }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        text("4:05 PM").assertIsDisplayed()
        text("3:12 AM").assertIsDisplayed()
        assertEquals(readsToLoad, reads)
    }

    @Test
    fun theZoomLinkOpensTheSelectedDayHourByHour() {
        setChart(dailyBand(), dailySettings)
        plot().performTouchInput { click(Offset(slotX(3), centerY)) }
        val weekday = days[3].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        text("Show $weekday hour by hour").assertIsDisplayed()

        composeRule.onNodeWithTag("chart_readout_zoom").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(ChartRange(days[3].atStartOfDay(zone).toInstant(), days[4].atStartOfDay(zone).toInstant(), BucketSize.HOUR)),
                openedRanges
            )
        }
    }
}
