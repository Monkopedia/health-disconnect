package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.HealthDataModel.BandExtremeTimes
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #137: a band carries when each bucket's max and min were read, from the same fold that
 * plots them, so the readout needs no read of its own; and [HealthDataModel.collectSeriesInRange]
 * charts one range of a view on its own.
 */
@RunWith(RobolectricTestRunner::class)
class HealthDataModelChartRangeTest {
    private val start = Instant.parse("2026-02-12T00:00:00Z")
    private val end = Instant.parse("2026-02-13T00:00:00Z")
    private val gateway = mockk<HealthConnectGateway>()
    private val model = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = gateway,
        // Buckets fall on the zone's days and hours; UTC keeps them where the assertions expect.
        timeProvider = object : TimeProvider {
            override fun now() = Instant.parse("2026-02-20T00:00:00Z")
            override fun zone() = ZoneOffset.UTC
        }
    )

    private fun weight(kilograms: Double, at: Instant) = WeightRecord(
        time = at,
        zoneOffset = ZoneOffset.UTC,
        weight = Mass.kilograms(kilograms),
        metadata = Metadata.manualEntry()
    )

    private fun weight(kilograms: Double, at: String) = weight(kilograms, Instant.parse(at))

    private val day = listOf(
        weight(71.0, "2026-02-12T07:00:00Z"),
        weight(73.5, "2026-02-12T16:05:00Z"),
        weight(70.25, "2026-02-12T03:12:00Z"),
        // The evening before: its own bucket, and as heavy as the 16:05 reading.
        weight(73.5, "2026-02-11T22:00:00Z")
    )

    /** Health Connect holding [records]: newest first, paged, stopping at the requested cap. */
    private fun stores(records: List<Record>) {
        val newestFirst = records.sortedByDescending { (it as WeightRecord).time }
        coEvery { gateway.readRecordsInRange(any(), any(), any(), any(), any(), any()) } answers {
            val pageSize = arg<Int>(3)
            val maxRecords = arg<Int>(4)
            val onPage = arg<(List<Record>) -> Unit>(5)
            newestFirst.take(maxRecords).chunked(pageSize).forEach(onPage)
        }
    }

    private fun weightView(smoothing: SmoothingMode = SmoothingMode.OFF): DataView {
        val settings = MetricChartSettings(
            aggregation = AggregationMode.MIN_MAX_AVG,
            timeWindow = TimeWindow.ALL,
            bucketSize = BucketSize.DAY,
            unitPreference = UnitPreference.METRIC,
            smoothing = smoothing
        )
        return DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(fqn = WeightRecord::class.qualifiedName!!, metricSettings = settings)),
            chartSettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.ALL)
        )
    }

    private fun HealthDataModel.MetricSeries.timesAt(bucket: Instant): BandExtremeTimes? =
        bandTimes?.get(points.indexOfFirst { it.instant == bucket })

    @Test
    fun aRawBandCarriesWhenEachBucketsMaxAndMinWereRead() = runBlocking {
        stores(day)

        val series = model.collectAggregatedSeries(weightView()).toList().last().single()

        assertEquals(
            BandExtremeTimes(maxAt = Instant.parse("2026-02-12T16:05:00Z"), minAt = Instant.parse("2026-02-12T03:12:00Z")),
            series.timesAt(start)
        )
        assertEquals(
            BandExtremeTimes(maxAt = Instant.parse("2026-02-11T22:00:00Z"), minAt = Instant.parse("2026-02-11T22:00:00Z")),
            series.timesAt(Instant.parse("2026-02-11T00:00:00Z"))
        )
    }

    @Test
    fun aBucketCutShortByTheRecordCapTimesTheMaxItPlots() = runBlocking {
        // Newest first: 99,998 readings of 70 kg a second apart back from the end of Feb 11, an 80 kg
        // reading on the evening of Feb 10, and a 70 kg one at 20:00 make the 100,000 the chart
        // reads. Older still, and cut off by the cap: a 99 kg reading the morning of Feb 10.
        val latest = Instant.parse("2026-02-11T23:59:59Z")
        val records = List(99_998) { weight(70.0, latest.minusSeconds(it.toLong())) } +
            weight(80.0, "2026-02-10T22:00:00Z") +
            weight(70.0, "2026-02-10T20:00:00Z") +
            weight(99.0, "2026-02-10T05:00:00Z")
        stores(records)

        val loaded = model.loadAggregatedSeriesForExport(weightView())

        assertNotNull("the cap must have cut the read short", loaded.issues.truncatedSince)
        val series = loaded.series.single()
        val boundary = Instant.parse("2026-02-10T00:00:00Z")
        val index = series.points.indexOfFirst { it.instant == boundary }
        assertEquals(80.0, series.bandMax!![index].value, 0.0)
        assertEquals(
            BandExtremeTimes(maxAt = Instant.parse("2026-02-10T22:00:00Z"), minAt = Instant.parse("2026-02-10T20:00:00Z")),
            series.timesAt(boundary)
        )
    }

    @Test
    fun aSmoothedBandCarriesNoTimes() = runBlocking {
        stores(day)

        val series = model.collectAggregatedSeries(weightView(SmoothingMode.MOVING_AVERAGE_3)).toList().last().single()

        // A smoothed edge averages neighbouring buckets, so no reading has its value.
        assertNotNull(series.bandMax)
        assertNull(series.bandTimes)
    }

    @Test
    fun aRangeIsReadAndBucketedOnItsOwnWhateverTheViewsWindow() = runBlocking {
        stores(day)

        val loaded = model.collectSeriesInRange(weightView(), ChartRange(start, end, BucketSize.HOUR)).toList().last()

        // One point per hour that has a reading; the evening before is outside the range.
        assertEquals(
            listOf("2026-02-12T03:00:00Z", "2026-02-12T07:00:00Z", "2026-02-12T16:00:00Z").map(Instant::parse),
            loaded.series.single().points.map { it.instant }
        )
        assertEquals(BucketSize.HOUR, loaded.series.single().bucketSize)
        coVerify { gateway.readRecordsInRange(WeightRecord::class, start, end, any(), any(), any()) }
    }
}
