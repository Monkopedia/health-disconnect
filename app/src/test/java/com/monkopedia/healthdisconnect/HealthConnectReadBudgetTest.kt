package com.monkopedia.healthdisconnect

import android.app.Application
import android.health.connect.HealthConnectException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.HealthDataModel.ReadFailure
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
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 *
 * "Now" and the zone are fixed and shared by the fake and the model (issue #131): read from the
 * wall clock and host zone, the day counts moved by one whenever the window straddled a DST change.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectReadBudgetTest {

    private class FixedTime(private val now: Instant, private val zone: ZoneId) : TimeProvider {
        override fun now() = now
        override fun zone() = zone
    }

    /**
     * Fake Health Connect holding [days] local days of [recordsPerDay] evenly-spaced step records
     * ending at [time]'s now, each of [STEPS_PER_RECORD] steps. Days are calendar days in [time]'s
     * zone, so a window across a DST change is an hour longer or shorter than days × 24h. Records
     * are built per page, never all at once. Every call counts against [quota]; past it, a call
     * throws what connect-client 1.1.0 throws for
     * the platform's ERROR_RATE_LIMIT_EXCEEDED: `ExceptionConverter.toKtException` has no case for
     * that code, so it becomes an IllegalStateException wrapping the HealthConnectException.
     * It also holds [WEIGHT_RECORDS] weight records, read in one page.
     */
    private class FakeStepsStore(
        val time: TimeProvider = FixedTime(NOW, ZONE),
        private val days: Int = 3 * 365,
        private val recordsPerDay: Int = 1_000,
        private val quota: Int = Int.MAX_VALUE,
        private val overQuota: () -> Exception = {
            val rateLimit = mockk<HealthConnectException> {
                every { errorCode } returns HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
            }
            IllegalStateException(rateLimit)
        }
    ) {
        val total = days * recordsPerDay
        var calls = 0
        private val oldest = daysBeforeNow(days)
        private val spacing = Duration.between(oldest, time.now()).dividedBy(total.toLong())
        private val metadata = Metadata.manualEntry()

        val client: HealthConnectClient = mockk {
            coEvery { readRecords(any<ReadRecordsRequest<Record>>()) } answers { readPage(firstArg()) }
            coEvery { aggregateGroupByPeriod(any()) } answers { aggregateByPeriod(firstArg()) }
        }

        private fun daysBeforeNow(days: Int): Instant =
            time.now().atZone(time.zone()).minusDays(days.toLong()).toInstant()

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
            if (request.recordType == WeightRecord::class) {
                val weights = (1..WEIGHT_RECORDS).map { daysAgo ->
                    WeightRecord(
                        time = daysBeforeNow(daysAgo),
                        zoneOffset = ZoneOffset.UTC,
                        weight = Mass.kilograms(70.0),
                        metadata = metadata
                    )
                }
                return ReadRecordsResponse(weights, null)
            }
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
        healthConnectGateway = DefaultHealthConnectGateway(store.client),
        timeProvider = store.time
    )

    private fun stepsView(
        aggregation: AggregationMode,
        bucketSize: BucketSize = BucketSize.DAY,
        alsoWeight: Boolean = false
    ): DataView {
        val settings = MetricChartSettings(
            aggregation = aggregation,
            timeWindow = TimeWindow.ALL,
            bucketSize = bucketSize
        )
        val types = listOfNotNull(StepsRecord::class, WeightRecord::class.takeIf { alsoWeight })
        return DataView(
            id = 112,
            type = ViewType.CHART,
            records = types.map { RecordSelection(fqn = it.qualifiedName!!, metricSettings = settings) },
            chartSettings = ChartSettings(
                aggregation = aggregation,
                timeWindow = TimeWindow.ALL,
                bucketSize = bucketSize
            )
        )
    }

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
    fun `a three-year daily chart has one point per local day in any zone and across DST`() = runBlocking {
        val instants = listOf(
            "2026-11-01T05:59:00Z", // 01:59 EDT, a minute before New York falls back
            "2026-11-01T06:01:00Z", // 01:01 EST, a minute after
            "2026-11-03T04:30:00Z", // 23:30 EST, two days after: the issue's off-by-one
            "2027-03-14T06:59:00Z", // 01:59 EST, a minute before New York springs forward
            "2027-03-14T07:01:00Z" // 03:01 EDT, a minute after
        ).map(Instant::parse)
        for (zone in listOf(ZONE, ZoneOffset.UTC)) {
            for (now in instants) {
                val store = FakeStepsStore(time = FixedTime(now, zone))

                val points = model(store).collectAggregatedSeries(stepsView(AggregationMode.SUM))
                    .toList().last().single().points

                assertEquals("$now in $zone", 3 * 365 + 1, points.size)
            }
        }
    }

    @Test
    fun `a three-year average-steps chart is served from daily totals too`() = runBlocking {
        for (bucketSize in listOf(BucketSize.DAY, BucketSize.WEEK)) {
            val store = FakeStepsStore()

            val series = model(store).collectAggregatedSeries(stepsView(AggregationMode.AVERAGE, bucketSize))
                .toList().last().single()

            // Average used to read every raw record; now it averages the days in each bucket.
            assertTrue("$bucketSize: expected ≤ 5 calls, made ${store.calls}", store.calls <= 5)
            assertEquals(AGGREGATED_STEPS_PER_PERIOD.toDouble(), series.points.first().value, 0.0)
            // Nothing was cut short: the chart reaches back to the first day of data.
            assertTrue(Duration.between(series.points.first().instant, NOW) > Duration.ofDays(3 * 365 - 7))
        }
    }

    @Test
    fun `a chart that needs raw records stops at the cap and says where it starts`() = runBlocking {
        // Hourly buckets cannot come from daily totals, so this one reads raw records.
        val store = FakeStepsStore()
        val model = model(store)

        val series = model.collectAggregatedSeries(stepsView(AggregationMode.SUM, BucketSize.HOUR))
            .toList().last().single()

        // 100,001 newest records (one past the cap, to tell "more exist") at 500 a page.
        assertTrue("expected ≤ 201 calls, made ${store.calls}", store.calls <= 201)
        // The cap keeps the newest 100,000 records — 100 days at this density — and says so.
        val since = model.collectChartLoadIssues(112).first().truncatedSince!!
        val shownDays = Duration.between(since, NOW).toDays()
        assertTrue("truncated at $shownDays days", shownDays in 99..100)
        assertEquals(since.truncatedTo(ChronoUnit.HOURS), series.points.first().instant.truncatedTo(ChronoUnit.HOURS))
    }

    @Test
    fun `an export cut short by the cap says where it starts`() = runBlocking {
        val export = model(FakeStepsStore())
            .loadAggregatedSeriesForExport(stepsView(AggregationMode.SUM, BucketSize.HOUR))

        // The export used to read every record; it now shares the chart's cap, so it must carry
        // the same "since" the chart shows rather than silently starting later.
        val shownDays = Duration.between(export.issues.truncatedSince!!, NOW).toDays()
        assertTrue("truncated at $shownDays days", shownDays in 99..100)
    }

    @Test
    fun `exactly the capped number of records is not reported as truncated`() = runBlocking {
        // 100 days × 1,000 = exactly 100,000 records: all of them fit.
        val model = model(FakeStepsStore(days = 100))

        model.collectAggregatedSeries(stepsView(AggregationMode.SUM, BucketSize.HOUR)).toList()

        assertNull(model.collectChartLoadIssues(112).first().truncatedSince)
    }

    @Test
    fun `a complete raw chart reports no truncation`() = runBlocking {
        val model = model(FakeStepsStore(days = 30))

        model.collectAggregatedSeries(stepsView(AggregationMode.SUM, BucketSize.HOUR)).toList()

        assertEquals(HealthDataModel.ChartLoadIssues(), model.collectChartLoadIssues(112).first())
    }

    @Test
    fun `the entries count stops reading at the listed-records cap`() = runBlocking {
        val store = FakeStepsStore()

        val count = model(store).collectRecordCount(stepsView(AggregationMode.SUM)).toList().last()

        assertEquals(HealthDataModel.RecordCount(10_000, atLeast = true), count)
        // 10,001 newest records (one past the cap) at 500 a page.
        assertTrue("expected ≤ 21 calls, made ${store.calls}", store.calls <= 21)
    }

    @Test
    fun `a capped type does not crowd another out of the entries list or count`() = runBlocking {
        val model = model(FakeStepsStore())
        val view = stepsView(AggregationMode.SUM, alsoWeight = true)

        val count = model.collectRecordCount(view).toList().last()
        val entries = withTimeout(60_000) {
            model.collectData(view).first { list -> list.count { it is WeightRecord } == WEIGHT_RECORDS }
        }

        // Every weight entry is listed next to the 10,000 newest steps, and the header agrees.
        assertEquals(HealthDataModel.RecordCount(10_000 + WEIGHT_RECORDS, atLeast = true), count)
        assertEquals(10_000, entries.count { it is StepsRecord })
    }

    @Test
    fun `an exhausted read quota ends the chart load instead of failing it`() = runBlocking {
        // Quota for one call: the oldest-record probe, or the first raw page. The next call throws.
        val totals = model(FakeStepsStore(quota = 1))
            .collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList().last()
        val raw = model(FakeStepsStore(quota = 1))
            .collectAggregatedSeries(stepsView(AggregationMode.SUM, BucketSize.HOUR)).toList().last()

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
        assertEquals(chart.single().points, export.series.single().points)
        assertTrue(export.issues.isComplete)
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
    fun `a failed chart read is reported so the chart can say why`() = runBlocking {
        val rateLimited = model(FakeStepsStore(quota = 1))
        rateLimited.collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList()
        val otherFailure = model(FakeStepsStore(quota = 1, overQuota = { IllegalStateException("broken") }))
        otherFailure.collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList()
        val healthy = model(FakeStepsStore())
        healthy.collectAggregatedSeries(stepsView(AggregationMode.SUM)).toList()

        assertEquals(ReadFailure.RATE_LIMITED, rateLimited.collectChartLoadIssues(112).first().readFailure)
        assertEquals(ReadFailure.OTHER, otherFailure.collectChartLoadIssues(112).first().readFailure)
        assertNull(healthy.collectChartLoadIssues(112).first().readFailure)
    }

    @Test
    fun `the widget still reports a missing read permission`() = runBlocking {
        val store = FakeStepsStore(quota = 0, overQuota = { SecurityException("no read permission") })

        val failure = runCatching { model(store).loadAggregatedSeriesForWidget(stepsView(AggregationMode.SUM)) }

        val denied = failure.exceptionOrNull() as HealthDataPermissionDeniedException
        assertEquals(setOf(StepsRecord::class.qualifiedName), denied.deniedRecordTypes)
    }

    @Test
    fun `a band of daily step totals names the day of each max and min, and a day band none`() = runBlocking {
        // Issue #137: a daily total has no time of its own, only its day.
        val week = model(FakeStepsStore()).collectAggregatedSeries(stepsView(AggregationMode.MIN_MAX_AVG, BucketSize.WEEK))
            .toList().last().single()

        assertTrue(week.bandTimesAreDays)
        val times = week.bandTimes!!
        assertEquals(week.points.size, times.size)
        week.points.zip(times).forEach { (point, extremes) ->
            listOf(extremes.maxAt, extremes.minAt).forEach { day ->
                val local = day.atZone(ZONE)
                assertEquals(LocalTime.MIDNIGHT, local.toLocalTime())
                assertTrue(!day.isBefore(point.instant) && day.isBefore(point.instant.atZone(ZONE).plusWeeks(1).toInstant()))
            }
        }
        // This week's lowest day is today, still in progress.
        assertEquals(NOW.atZone(ZONE).toLocalDate(), times.last().minAt.atZone(ZONE).toLocalDate())

        // A day bucket's value is the day itself.
        val day = model(FakeStepsStore()).collectAggregatedSeries(stepsView(AggregationMode.MIN_MAX_AVG, BucketSize.DAY))
            .toList().last().single()
        assertNotNull(day.bandMax)
        assertNull(day.bandTimes)
    }

    private companion object {
        /** 23:30 in New York two days after DST ended: the instant issue #131 failed at. */
        val NOW: Instant = Instant.parse("2026-11-03T04:30:00Z")
        val ZONE: ZoneId = ZoneId.of("America/New_York")
        const val STEPS_PER_RECORD = 10L
        const val AGGREGATED_STEPS_PER_PERIOD = 4_321L
        const val IN_PROGRESS_STEPS = 7L
        const val WEIGHT_RECORDS = 3
    }
}
