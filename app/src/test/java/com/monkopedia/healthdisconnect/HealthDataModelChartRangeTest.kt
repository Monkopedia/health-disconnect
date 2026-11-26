package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.HealthDataModel.BandBucket
import com.monkopedia.healthdisconnect.HealthDataModel.BandExtremeTimes
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #137's reads for one range of a chart: [HealthDataModel.bandExtremeTimes] finds when a band
 * bucket's max and min were recorded with one capped read of just that bucket, and gives no time
 * rather than a wrong one; [HealthDataModel.collectSeriesInRange] charts a range on its own.
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
        // Hour buckets fall on the zone's hours; UTC keeps them where the assertions expect.
        timeProvider = object : TimeProvider {
            override fun now() = Instant.parse("2026-02-20T00:00:00Z")
            override fun zone() = ZoneOffset.UTC
        }
    )

    private fun weight(kilograms: Double, at: String) = WeightRecord(
        time = Instant.parse(at),
        zoneOffset = ZoneOffset.UTC,
        weight = Mass.kilograms(kilograms),
        metadata = Metadata.manualEntry()
    )

    private val day = listOf(
        weight(71.0, "2026-02-12T07:00:00Z"),
        weight(73.5, "2026-02-12T16:05:00Z"),
        weight(70.25, "2026-02-12T03:12:00Z"),
        // Outside the bucket, and heavier: it must not be taken for the max.
        weight(73.5, "2026-02-11T22:00:00Z")
    )

    private fun stores(records: List<Record>) {
        coEvery { gateway.readRecordsInRange(any(), any(), any(), any(), any(), any()) } answers {
            arg<(List<Record>) -> Unit>(5).invoke(records)
        }
    }

    private fun band() = HealthDataModel.MetricSeries(
        label = "Weight",
        unit = "kg",
        points = emptyList(),
        bandMin = emptyList(),
        bandMax = emptyList(),
        aggregation = AggregationMode.MIN_MAX_AVG,
        source = RecordSelection(
            fqn = WeightRecord::class.qualifiedName!!,
            metricSettings = MetricChartSettings(
                aggregation = AggregationMode.MIN_MAX_AVG,
                unitPreference = UnitPreference.METRIC
            )
        )
    )

    @Test
    fun oneCappedReadOfTheBucketFindsWhenItsMaxAndMinWereRecorded() = runBlocking {
        stores(day)

        // Two series of one record type share the read.
        val times = model.bandExtremeTimes(
            listOf(BandBucket(band(), max = 73.5, min = 70.25), BandBucket(band(), max = 71.0, min = 70.25)),
            start,
            end
        )

        assertEquals(
            listOf(
                BandExtremeTimes(Instant.parse("2026-02-12T16:05:00Z"), Instant.parse("2026-02-12T03:12:00Z")),
                BandExtremeTimes(Instant.parse("2026-02-12T07:00:00Z"), Instant.parse("2026-02-12T03:12:00Z"))
            ),
            times
        )
        coVerify(exactly = 1) {
            gateway.readRecordsInRange(WeightRecord::class, start, end, any(), HealthDataModel.MAX_LISTED_RECORDS, any())
        }
    }

    @Test
    fun aFailedReadGivesNoTimeInsteadOfThrowing() = runBlocking {
        coEvery { gateway.readRecordsInRange(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("rate limited")

        assertEquals(listOf(null), model.bandExtremeTimes(listOf(BandBucket(band(), 73.5, 70.25)), start, end))
    }

    @Test
    fun aRangeIsReadAndBucketedOnItsOwnWhateverTheViewsWindow() = runBlocking {
        stores(day)
        val settings = MetricChartSettings(
            aggregation = AggregationMode.MIN_MAX_AVG,
            timeWindow = TimeWindow.DAYS_7,
            bucketSize = BucketSize.DAY,
            unitPreference = UnitPreference.METRIC
        )
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(fqn = WeightRecord::class.qualifiedName!!, metricSettings = settings)),
            chartSettings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7)
        )

        val loaded = model.collectSeriesInRange(view, ChartRange(start, end, BucketSize.HOUR)).toList().last()

        // One point per hour that has a reading; the evening before is outside the range.
        assertEquals(
            listOf("2026-02-12T03:00:00Z", "2026-02-12T07:00:00Z", "2026-02-12T16:00:00Z").map(Instant::parse),
            loaded.series.single().points.map { it.instant }
        )
        assertEquals(BucketSize.HOUR, loaded.series.single().bucketSize)
        coVerify { gateway.readRecordsInRange(WeightRecord::class, start, end, any(), any(), any()) }
    }

    @Test
    fun aValueNoReadingHasGetsNoTime() = runBlocking {
        stores(day)

        // A max no reading in the bucket has, e.g. the data changed since the chart loaded.
        assertEquals(listOf(null), model.bandExtremeTimes(listOf(BandBucket(band(), 74.0, 70.25)), start, end))
    }
}
