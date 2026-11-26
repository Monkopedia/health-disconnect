package com.monkopedia.healthdisconnect.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.CoroutineDispatcherProvider
import com.monkopedia.healthdisconnect.HealthConnectGateway
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.TimeProvider
import com.monkopedia.healthdisconnect.formatExactValueWithUnit
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.zoomedTo
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #137 review: a band's Average counts every reading once. A mean of bucket averages lets one
 * reading in a quiet bucket weigh as much as a busy bucket's ten, so a day drawn hour by hour
 * summarised to a different Average than the day itself showed, and so did the whole chart.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BandSummaryAverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)
    private val busyDay = LocalDate.of(2026, 2, 11)

    private fun at(day: LocalDate, hour: Int, minute: Int): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    private fun weight(kilograms: Double, at: Instant) = WeightRecord(
        time = at,
        zoneOffset = zone.rules.getOffset(at),
        weight = Mass.kilograms(kilograms),
        metadata = Metadata.manualEntry()
    )

    // One 80 kg reading on Feb 10; on Feb 11 one 50 kg reading at 3 AM and ten of 100 kg from 4 PM.
    private val records: List<WeightRecord> = listOf(weight(80.0, at(busyDay.minusDays(1), 9, 0))) +
        weight(50.0, at(busyDay, 3, 0)) +
        (0 until 10).map { weight(100.0, at(busyDay, 16, it)) }

    private val gateway = mockk<HealthConnectGateway> {
        coEvery { readRecordsInRange(any(), any(), any(), any(), any(), any()) } answers {
            val start = arg<Instant>(1)
            val end = arg<Instant>(2)
            arg<(List<Record>) -> Unit>(5).invoke(
                records.filter { !it.time.isBefore(start) && it.time.isBefore(end) }.sortedByDescending { it.time }
            )
        }
    }

    private val model = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = gateway,
        dispatchers = object : CoroutineDispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
        },
        timeProvider = object : TimeProvider {
            override fun now() = clock.instant()
            override fun zone() = zone
        }
    )

    private val view = DataView(
        id = 1,
        type = ViewType.CHART,
        records = listOf(
            RecordSelection(
                fqn = WeightRecord::class.qualifiedName!!,
                metricSettings = MetricChartSettings(
                    aggregation = AggregationMode.MIN_MAX_AVG,
                    timeWindow = TimeWindow.DAYS_7,
                    unitPreference = UnitPreference.METRIC
                )
            )
        ),
        chartSettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7)
    )

    private val week: HealthDataModel.MetricSeries = runBlocking { model.collectAggregatedSeries(view).toList().last().single() }
    private val unit = week.unit

    private fun panelText(tag: String, value: Double): SemanticsMatcher =
        hasText(formatExactValueWithUnit(value, unit)) and
            hasAnyAncestor(hasTestTag(tag)) and
            hasAnyAncestor(hasTestTag("chart_readout_panel"))

    private fun assertPanelShows(tag: String, vararg values: Double) = values.forEach {
        composeRule.onNode(panelText(tag, it), useUnmergedTree = true).assertExists()
    }

    private fun chart(
        series: HealthDataModel.MetricSeries,
        settings: ChartSettings,
        selectedSlot: Int? = null,
        window: ChartRange? = null
    ) = @androidx.compose.runtime.Composable {
        MetricOverTimeChart(
            seriesList = listOf(series),
            settings = settings,
            selectedSlot = selectedSlot,
            onSelectedSlotChange = {},
            window = window
        )
    }

    private fun show(vararg charts: Pair<String, @androidx.compose.runtime.Composable () -> Unit>) {
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        Column {
                            charts.forEach { (tag, content) -> Box(Modifier.testTag(tag)) { Column { content() } } }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aDayDrawnHourByHourSummarisesToTheValuesTheDayShowed() {
        val range = ChartRange.zoomInto(busyDay.atStartOfDay(zone).toInstant(), BucketSize.DAY, zone)!!
        val hours = runBlocking { model.collectSeriesInRange(view, range).toList().last().series.single() }
        val daySlot = week.points.indexOfFirst { it.date == busyDay }

        show(
            "day" to chart(week, view.chartSettings, selectedSlot = daySlot),
            "zoom" to chart(hours, view.zoomedTo(range).chartSettings, window = range)
        )

        // Max, Average and Min of the day: 100, (50 + 10 × 100) / 11 and 50, both ways.
        val dayValues = doubleArrayOf(100.0, 1_050.0 / 11, 50.0)
        assertEquals(1_050.0 / 11, week.points[daySlot].value, 1e-9)
        assertPanelShows("day", *dayValues)
        assertPanelShows("zoom", *dayValues)
    }

    @Test
    fun theChartsIdleAverageCountsEveryReadingOnce() {
        show("week" to chart(week, view.chartSettings))

        // Twelve readings: 80, 50 and ten of 100. The two days' averages, 80 and 95.5, would say 87.7.
        assertPanelShows("week", 100.0, 1_130.0 / 12, 50.0)
    }
}
