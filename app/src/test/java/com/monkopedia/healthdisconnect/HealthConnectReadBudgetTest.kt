package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #112: "In steps the app crashes loading several years of data. It won't reload for some
 * time even after clearing out of app list and clearing cache."
 *
 * Health Connect rate-limits each app's reads, and charts used to read every raw record in the
 * window, one read per 500. These tests run the real [DefaultHealthConnectGateway] paging code
 * against a fake [HealthConnectClient] holding three years of per-minute-style wearable steps
 * (1,000 records a day, 1,095,000 records, 2,190 pages), and count the Health Connect calls.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectReadBudgetTest {

    /**
     * Fake Health Connect holding [days] days of [recordsPerDay] evenly-spaced step records ending
     * now, each of [STEPS_PER_RECORD] steps. Records are built per page, never all at once. Every
     * call counts against [quota]; past it, a call throws what connect-client 1.1.0 throws for
     * the platform's ERROR_RATE_LIMIT_EXCEEDED: `ExceptionConverter.toKtException` has no case for
     * that code, so it becomes an IllegalStateException wrapping the HealthConnectException.
     */
    private class FakeStepsStore(
        private val days: Int = 3 * 365,
        private val recordsPerDay: Int = 1_000,
        private val quota: Int = Int.MAX_VALUE,
        private val overQuota: () -> Exception = {
            IllegalStateException("android.health.connect.HealthConnectException: API call quota exceeded")
        }
    ) {
        val total = days * recordsPerDay
        var calls = 0
        private val spacing = Duration.ofDays(1).dividedBy(recordsPerDay.toLong())
        private val oldest = Instant.now().minus(Duration.ofDays(days.toLong()))
        private val metadata = Metadata.manualEntry()

        val client: HealthConnectClient = mockk {
            coEvery { readRecords(any<ReadRecordsRequest<Record>>()) } answers { readPage(firstArg()) }
            coEvery { aggregateGroupByPeriod(any()) } answers { aggregateByPeriod(firstArg()) }
        }

        private fun spend() {
            if (++calls > quota) throw overQuota()
        }

        private fun record(index: Int): StepsRecord {
            val start = oldest.plus(spacing.multipliedBy(index.toLong()))
            return StepsRecord(
                startTime = start,
                startZoneOffset = ZoneOffset.UTC,
                endTime = start.plus(spacing),
                endZoneOffset = ZoneOffset.UTC,
                count = STEPS_PER_RECORD,
                metadata = metadata
            )
        }

        private fun readPage(request: ReadRecordsRequest<Record>): ReadRecordsResponse<Record> {
            spend()
            val offset = request.pageToken?.toInt() ?: 0
            val size = minOf(request.pageSize, total - offset)
            val records = (offset until offset + size).map { position ->
                record(if (request.ascendingOrder) position else total - 1 - position)
            }
            val next = (offset + size).takeIf { it < total }?.toString()
            return ReadRecordsResponse(records, next)
        }

        private fun aggregateByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> {
            spend()
            // The request's fields are internal to connect-client; read them reflectively.
            val filter = request.javaClass.getMethod("getTimeRangeFilter\$connect_client_release")
                .invoke(request) as TimeRangeFilter
            val period = request.javaClass.getMethod("getTimeRangeSlicer\$connect_client_release")
                .invoke(request) as Period
            val whole = mockk<AggregationResult> {
                every { get(StepsRecord.COUNT_TOTAL) } returns AGGREGATED_STEPS_PER_PERIOD
            }
            val inProgress = mockk<AggregationResult> {
                every { get(StepsRecord.COUNT_TOTAL) } returns IN_PROGRESS_STEPS
            }
            val slices = mutableListOf<AggregationResultGroupedByPeriod>()
            var sliceStart: LocalDateTime = filter.localStartTime!!
            while (sliceStart < filter.localEndTime!!) {
                val sliceEnd = sliceStart.plus(period)
                // A slice running past the request's end is the period still in progress.
                val result = if (sliceEnd > filter.localEndTime!!) inProgress else whole
                slices += AggregationResultGroupedByPeriod(result, sliceStart, sliceEnd)
                sliceStart = sliceEnd
            }
            return slices
        }
    }

    private fun model(store: FakeStepsStore) = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = DefaultHealthConnectGateway(store.client)
    )

    private fun stepsView(aggregation: AggregationMode) = DataView(
        id = 112,
        type = ViewType.CHART,
        records = listOf(
            RecordSelection(
                fqn = StepsRecord::class.qualifiedName!!,
                metricSettings = MetricChartSettings(
                    aggregation = aggregation,
                    timeWindow = TimeWindow.ALL,
                    bucketSize = BucketSize.DAY
                )
            )
        ),
        chartSettings = ChartSettings(
            aggregation = aggregation,
            timeWindow = TimeWindow.ALL,
            bucketSize = BucketSize.DAY
        )
    )

    @Test
    fun `a three-year daily step total chart takes a handful of Health Connect calls`() = runBlocking {
        val store = FakeStepsStore()

        val series = model(store).collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList().last()

        // One read for the oldest record, then one aggregate per 365 days: 1 + 4 for 1,095 days.
        // Reading raw records took one call per 500 records — 2,190 here.
        assertTrue("expected ≤ 5 Health Connect calls, made ${store.calls}", store.calls <= 5)
        val points = series.single().points
        // 1,095 days ending now span 1,096 calendar days: partial first and last.
        assertEquals(3 * 365 + 1, points.size)
        // The chart shows what Health Connect aggregated (a fixed value here), not the raw-record
        // sum of 10,000 a day. This proves the routing; the fake does not model deduplication.
        assertTrue(points.dropLast(1).all { it.value == AGGREGATED_STEPS_PER_PERIOD.toDouble() })
        assertEquals(IN_PROGRESS_STEPS.toDouble(), points.last().value, 0.0)
    }

    @Test
    fun `a three-year step chart that needs raw records stops at the chart cap`() = runBlocking {
        val store = FakeStepsStore()

        val series = model(store).collectAggregatedSeries(stepsView(AggregationMode.AVERAGE)).toList().last()

        val maxCalls = HealthDataModel.MAX_CHART_RECORDS / 500
        assertTrue("expected ≤ $maxCalls calls, made ${store.calls}", store.calls <= maxCalls)
        // The cap keeps the newest records, so the chart still reaches today.
        val newestDay = series.single().points.last().instant
        assertTrue(Duration.between(newestDay, Instant.now()) < Duration.ofDays(2))
    }

    @Test
    fun `the entries count stops reading at the listed-records cap`() = runBlocking {
        val store = FakeStepsStore()

        val count = model(store).collectRecordCount(stepsView(AggregationMode.SUM)).toList().last()

        assertEquals(HealthDataModel.MAX_LISTED_RECORDS, count)
        val maxCalls = HealthDataModel.MAX_LISTED_RECORDS / 500
        assertTrue("expected ≤ $maxCalls calls, made ${store.calls}", store.calls <= maxCalls)
    }

    @Test
    fun `an exhausted read quota ends the step chart load instead of failing it`() = runBlocking {
        // Quota for one call: the oldest-record probe, or the first raw page. The next call throws.
        val totals = model(FakeStepsStore(quota = 1))
            .collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList().last()
        val raw = model(FakeStepsStore(quota = 1))
            .collectAggregatedSeries(stepsView(AggregationMode.AVERAGE)).toList().last()

        // No totals were read, so no series; the raw chart keeps the page it read before the limit.
        assertTrue(totals.isEmpty())
        assertTrue(raw.single().points.isNotEmpty())
    }

    @Test
    fun `an exhausted read quota does not fail the recent-data probe`() = runBlocking {
        val store = FakeStepsStore(quota = 0)

        // Callers launch this with no handler; a throw here was a crash when adding a metric.
        // Unknown is reported as "has data" so no spurious "no recent data" warning is shown.
        assertTrue(model(store).hasRecentMetricData(StepsRecord::class, metricKey = null))
    }

    @Test
    fun `the widget and the CSV export show the chart's step totals`() = runBlocking {
        val view = stepsView(AggregationMode.SUM)
        val chart = model(FakeStepsStore()).collectAggregatedSeries(view).toList().last()
        val widgetStore = FakeStepsStore()
        val widget = model(widgetStore).loadAggregatedSeriesForWidget(view)
        val exportStore = FakeStepsStore()
        val export = model(exportStore).loadAggregatedSeriesForExport(view)

        // One view, one total: a widget or export summing raw records would show double-counted
        // totals beside a deduplicated chart, and read all 2,190 pages to do it.
        assertEquals(chart.single().points, widget.single().points)
        assertEquals(chart.single().points, export.single().points)
        assertTrue("widget made ${widgetStore.calls} calls", widgetStore.calls <= 5)
        assertTrue("export made ${exportStore.calls} calls", exportStore.calls <= 5)
    }

    @Test
    fun `step totals Min skips the bucket still in progress`() = runBlocking {
        val series = model(FakeStepsStore()).collectAggregatedSeries(stepsView(AggregationMode.SUM))
            .toList().last().single()

        // Today's partial total (IN_PROGRESS_STEPS) is drawn but is not the window's minimum.
        assertEquals(AGGREGATED_STEPS_PER_PERIOD.toDouble(), series.minValueInWindow, 0.0)
        assertEquals(AGGREGATED_STEPS_PER_PERIOD.toDouble(), series.peakValueInWindow, 0.0)
    }

    @Test
    fun `a failed chart read is reported so the chart can say so`() = runBlocking {
        val failing = model(FakeStepsStore(quota = 1))
        failing.collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList()
        val healthy = model(FakeStepsStore())
        healthy.collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList()

        assertTrue(failing.collectChartReadFailed(112).first())
        assertFalse(healthy.collectChartReadFailed(112).first())
    }

    @Test
    fun `the widget still reports a missing read permission`() = runBlocking {
        val store = FakeStepsStore(quota = 0, overQuota = { SecurityException("no read permission") })

        val failure = runCatching { model(store).loadAggregatedSeriesForWidget(stepsView(AggregationMode.SUM)) }

        val denied = failure.exceptionOrNull() as HealthDataPermissionDeniedException
        assertEquals(setOf(StepsRecord::class.qualifiedName), denied.deniedRecordTypes)
    }

    private companion object {
        const val STEPS_PER_RECORD = 10L
        const val AGGREGATED_STEPS_PER_PERIOD = 4_321L
        const val IN_PROGRESS_STEPS = 7L
    }
}
