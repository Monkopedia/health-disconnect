package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #139: a selected bucket's "View entries" lists that bucket's entries, read from Health
 * Connect for that range alone. The real [DefaultHealthConnectGateway] pages against a fake
 * [HealthConnectClient] that honours each request's time range, so the tests see exactly what was
 * asked of Health Connect.
 */
@RunWith(RobolectricTestRunner::class)
class HealthDataModelEntriesInRangeTest {

    private class FixedTime(private val now: Instant) : TimeProvider {
        override fun now() = now
        override fun zone(): ZoneId = ZoneOffset.UTC
    }

    /** [count] weight records, one an hour, newest an hour before [NOW]; records every request. */
    private class FakeWeightStore(count: Int) {
        val requests = mutableListOf<ReadRecordsRequest<Record>>()
        private val newestFirst: List<WeightRecord> = (1..count).map { hoursAgo ->
            WeightRecord(
                time = NOW.minus(Duration.ofHours(hoursAgo.toLong())),
                zoneOffset = ZoneOffset.UTC,
                weight = Mass.kilograms(70.0 + hoursAgo % 10),
                metadata = Metadata.manualEntry()
            )
        }

        val client: HealthConnectClient = mockk {
            coEvery { readRecords(any<ReadRecordsRequest<Record>>()) } answers { readPage(firstArg()) }
        }

        private fun readPage(request: ReadRecordsRequest<Record>): ReadRecordsResponse<Record> {
            requests += request
            val filter = request.timeRangeFilter
            // Health Connect's instant-record filter: start inclusive, end exclusive.
            val matching = newestFirst.filter { record ->
                filter.startTime?.let { record.time >= it } != false && filter.endTime?.let { record.time < it } != false
            }
            val ordered = if (request.ascendingOrder) matching.reversed() else matching
            val offset = request.pageToken?.toInt() ?: 0
            val page = ordered.drop(offset).take(request.pageSize)
            val next = (offset + page.size).takeIf { it < ordered.size }?.toString()
            return ReadRecordsResponse(page, next)
        }
    }

    private fun model(store: FakeWeightStore) = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = DefaultHealthConnectGateway(store.client),
        timeProvider = FixedTime(NOW)
    )

    private val view = DataView(
        id = 139,
        type = ViewType.CHART,
        records = listOf(RecordSelection(fqn = WeightRecord::class.qualifiedName!!)),
        chartSettings = ChartSettings(timeWindow = TimeWindow.ALL)
    )

    /** The UTC day [daysAgo] days before [NOW], as a selected day bucket. */
    private fun dayBucket(daysAgo: Long): ChartRange {
        val start = NOW.minus(Duration.ofDays(daysAgo))
        return ChartRange(start, start.plus(Duration.ofDays(1)), BucketSize.DAY)
    }

    private val TimeRangeFilter.span get() = startTime to endTime

    @Test
    fun aBucketOlderThanTheNewestListedRecordsStillListsItsEntries() = runBlocking {
        // 20,000 hourly records: the newest 10,000 reach back about 417 days, so a day 600 days ago
        // is outside the unscoped list entirely.
        val store = FakeWeightStore(count = 20_000)
        val model = model(store)
        val bucket = dayBucket(daysAgo = 600)

        val newest = withTimeout(60_000) {
            model.collectData(view).first { it.size == HealthDataModel.MAX_LISTED_RECORDS }
        }
        val scoped = model.collectEntriesInRange(view, bucket).first()

        assertTrue(
            "the newest list should not reach the bucket, or this test proves nothing",
            newest.none { (it as WeightRecord).time < bucket.end }
        )
        assertEquals(24, scoped.records.size)
        assertTrue(scoped.records.all { (it as WeightRecord).time >= bucket.start && it.time < bucket.end })
        assertEquals(scoped.records.sortedByDescending { (it as WeightRecord).time }, scoped.records)
        assertTrue(scoped.issues.isComplete)
    }

    @Test
    fun theReadIsBoundedToTheRange() = runBlocking {
        val store = FakeWeightStore(count = 20_000)
        val bucket = dayBucket(daysAgo = 600)

        model(store).collectEntriesInRange(view, bucket).first()

        // One page of 24 records, asked for exactly that day: nothing outside it is read.
        assertEquals(1, store.requests.size)
        assertEquals(bucket.start to bucket.end, store.requests.single().timeRangeFilter.span)
    }

    @Test
    fun aRangeOverTheListedCapStopsThereAndSaysWhere() = runBlocking {
        // 15,000 hourly records inside one range: the cap keeps the newest 10,000 and says so.
        val store = FakeWeightStore(count = 15_000)
        val range = ChartRange(NOW.minus(Duration.ofDays(1_000)), NOW, BucketSize.MONTH)

        val loaded = model(store).collectEntriesInRange(view, range).first()

        assertEquals(HealthDataModel.MAX_LISTED_RECORDS, loaded.records.size)
        val oldestShown = loaded.records.minOf { (it as WeightRecord).time }
        assertEquals(oldestShown, loaded.issues.truncatedSince)
        // 10,001 records (one past the cap, to tell "more exist") at 500 a page, all in the range.
        assertEquals(21, store.requests.size)
        assertTrue(store.requests.all { it.timeRangeFilter.span == range.start to range.end })
    }

    @Test
    fun exactlyTheCapIsNotReportedAsTruncated() = runBlocking {
        val store = FakeWeightStore(count = HealthDataModel.MAX_LISTED_RECORDS)
        val range = ChartRange(NOW.minus(Duration.ofDays(1_000)), NOW, BucketSize.MONTH)

        val loaded = model(store).collectEntriesInRange(view, range).first()

        assertEquals(HealthDataModel.MAX_LISTED_RECORDS, loaded.records.size)
        assertNull(loaded.issues.truncatedSince)
    }

    @Test
    fun aFailedReadIsReportedNotThrown() = runBlocking {
        val client = mockk<HealthConnectClient> {
            coEvery { readRecords(any<ReadRecordsRequest<Record>>()) } throws IllegalStateException("broken")
        }
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            healthConnectGateway = DefaultHealthConnectGateway(client),
            timeProvider = FixedTime(NOW)
        )

        val loaded = model.collectEntriesInRange(view, dayBucket(daysAgo = 3)).first()

        assertTrue(loaded.records.isEmpty())
        assertEquals(HealthDataModel.ReadFailure.OTHER, loaded.issues.readFailure)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-02-15T00:00:00Z")
    }
}
