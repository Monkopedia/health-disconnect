package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.YAxisMode
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataModelStreamingTest {

    @Test
    fun `split bucket across pages aggregates correctly`() = runBlocking {
        val model = HealthDataModel(
            ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
        val selection = RecordSelection(
            fqn = StepsRecord::class.qualifiedName!!,
            metricSettings = MetricChartSettings(
                aggregation = AggregationMode.SUM,
                timeWindow = TimeWindow.ALL,
                bucketSize = BucketSize.DAY,
                yAxisMode = YAxisMode.AUTO,
                smoothing = SmoothingMode.OFF,
                unitPreference = UnitPreference.AUTO
            )
        )
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL, bucketSize = BucketSize.DAY)
        )

        val day = Instant.parse("2026-02-10T10:00:00Z")
        val page1 = listOf(stepsRecord(1000, day))
        val page2 = listOf(stepsRecord(1500, day))

        val emissions = model.collectAggregatedSeries(view) { _, _, _, onPage ->
            onPage(page1)
            onPage(page2)
        }.toList()

        val finalSeries = emissions.last().single()
        val finalPoint = finalSeries.points.single()
        assertEquals(2500.0, finalPoint.value, 0.001)
    }

    @Test
    fun `stream emits intermediate then final values`() = runBlocking {
        val model = HealthDataModel(
            ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
        val selection = RecordSelection(
            fqn = StepsRecord::class.qualifiedName!!,
            metricSettings = MetricChartSettings(
                aggregation = AggregationMode.SUM,
                timeWindow = TimeWindow.ALL,
                bucketSize = BucketSize.DAY,
                yAxisMode = YAxisMode.AUTO,
                smoothing = SmoothingMode.OFF,
                unitPreference = UnitPreference.AUTO
            )
        )
        val view = DataView(
            id = 2,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL, bucketSize = BucketSize.DAY)
        )

        val day1 = Instant.parse("2026-02-11T10:00:00Z")
        val day2 = Instant.parse("2026-02-12T10:00:00Z")
        val page1 = listOf(stepsRecord(500, day1))
        val page2 = listOf(stepsRecord(900, day2))

        val emissions = model.collectAggregatedSeries(view) { _, _, _, onPage ->
            onPage(page1)
            onPage(page2)
        }.toList()

        assertTrue(emissions.size >= 2)
        val first = emissions.first().single()
        val firstPoints = first.points.associate { it.date to it.value }
        assertEquals(500.0, firstPoints.values.single(), 0.001)

        val final = emissions.last().single()
        val finalPoints = final.points.associate { it.date to it.value }
        assertEquals(2, finalPoints.size)
        assertTrue(finalPoints.values.contains(500.0))
        assertTrue(finalPoints.values.contains(900.0))
    }

    @Test
    fun `stream handles large dataset with many pages`() = runBlocking {
        val model = HealthDataModel(
            ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
        val selection = RecordSelection(
            fqn = StepsRecord::class.qualifiedName!!,
            metricSettings = MetricChartSettings(
                aggregation = AggregationMode.AVERAGE,
                timeWindow = TimeWindow.ALL,
                bucketSize = BucketSize.DAY,
                yAxisMode = YAxisMode.AUTO,
                smoothing = SmoothingMode.OFF,
                unitPreference = UnitPreference.AUTO
            )
        )
        val view = DataView(
            id = 3,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL, bucketSize = BucketSize.DAY)
        )

        val now = Instant.parse("2026-02-16T00:00:00Z")
        val startNanos = System.nanoTime()
        val emissions = model.collectAggregatedSeries(view) { _, _, _, onPage ->
            var index = 0
            val pageSize = 1_000
            val totalRecords = 20_000
            while (index < totalRecords) {
                val end = (index + pageSize).coerceAtMost(totalRecords)
                val page = (index until end).map { i -> stepsRecord(i.toLong(), now) }
                onPage(page)
                index = end
            }
        }.toList()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.size > 1)
        assertTrue("aggregation should stay responsive at scale", elapsedMs < 60_000)

        val final = emissions.last().singleOrNull()
        assertNotNull(final)
        assertEquals(1, final!!.points.size)
        val finalPoint = final.points.single()
        assertTrue(finalPoint.value > 0.0)
        assertTrue(finalPoint.value < 50_000.0)
        assertTrue(finalPoint.value.isFinite())
    }

    @Test
    fun `collectRecordCount emits incremental totals`() = runBlocking {
        var pageNumber = 0
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            pageReaderOverride = { _, _, _, onPage ->
                when (pageNumber++) {
                    0 -> {
                        onPage(listOf(stepsRecord(1, Instant.parse("2026-02-13T10:00:00Z"))))
                    }
                    1 -> {
                        onPage(listOf(
                            stepsRecord(2, Instant.parse("2026-02-14T10:00:00Z")),
                            stepsRecord(3, Instant.parse("2026-02-15T10:00:00Z"))
                        ))
                    }
                }
            }
        )
        val view = DataView(
            id = 4,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(StepsRecord::class.qualifiedName!!),
                RecordSelection(DistanceRecord::class.qualifiedName!!)
            ),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL, bucketSize = BucketSize.DAY)
        )

        val counts = withTimeout(5_000) {
            model.collectRecordCount(view).take(2).toList()
        }

        assertEquals(listOf(1, 3), counts)
    }

    @Test
    fun `stream propagates min max label preferences and min value`() = runBlocking {
        val model = HealthDataModel(
            ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
        val selection = RecordSelection(
            fqn = StepsRecord::class.qualifiedName!!,
            metricSettings = MetricChartSettings(
                aggregation = AggregationMode.SUM,
                timeWindow = TimeWindow.ALL,
                bucketSize = BucketSize.DAY,
                yAxisMode = YAxisMode.AUTO,
                smoothing = SmoothingMode.OFF,
                unitPreference = UnitPreference.AUTO,
                showMaxLabel = false,
                showMinLabel = true
            )
        )
        val view = DataView(
            id = 5,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL, bucketSize = BucketSize.DAY)
        )

        val day = Instant.parse("2026-02-17T10:00:00Z")
        val emissions = model.collectAggregatedSeries(view) { _, _, _, onPage ->
            onPage(
                listOf(
                    stepsRecord(100, day),
                    stepsRecord(900, day)
                )
            )
        }.toList()

        val finalSeries = emissions.last().single()
        assertEquals(900.0, finalSeries.peakValueInWindow, 0.001)
        assertEquals(100.0, finalSeries.minValueInWindow, 0.001)
        assertEquals(false, finalSeries.showMaxLabel)
        assertEquals(true, finalSeries.showMinLabel)
    }

    private fun stepsRecord(count: Long, time: Instant): StepsRecord {
        val record = mockk<StepsRecord>(relaxed = true)
        every { record.count } returns count
        every { record.startTime } returns time
        every { record.endTime } returns time
        return record
    }
}
