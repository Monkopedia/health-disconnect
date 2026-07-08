package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.TimeWindow
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataModelMathTest {
    private val model by lazy {
        HealthDataModel(
            ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
    }

    private fun invokeWindowStart(timeWindow: TimeWindow): Instant? {
        return HealthDataModel::class.java
            .getDeclaredMethod("windowStart", TimeWindow::class.java)
            .also { it.isAccessible = true }
            .invoke(model, timeWindow) as Instant?
    }

    // The bucket/aggregate/smooth math lives in the aggregation engine; exercise it directly.
    // The engine's pure math doesn't touch the measurement extractor, so a relaxed mock suffices.
    private val engine = DefaultHealthDataAggregationEngine(mockk(relaxed = true))

    private fun smooth3(values: List<Double>): List<Double> {
        val points = values.mapIndexed { index, value ->
            HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 10 + index), value)
        }
        return engine.smooth3(points).map { it.value }
    }

    @Test
    fun `windowStart maps windows to now minus expected periods`() {
        val now = Instant.now()
        val start7 = invokeWindowStart(TimeWindow.DAYS_7)
        val start30 = invokeWindowStart(TimeWindow.DAYS_30)
        val startAll = invokeWindowStart(TimeWindow.ALL)

        assertNotNull(start7)
        assertNotNull(start30)
        assertNull(startAll)

        assertTrue(Duration.between(start7, now).toDays() in 6..8)
        assertTrue(Duration.between(start30, now).toDays() in 29..31)
    }

    @Test
    fun `toBucketInstant aligns to correct boundaries`() {
        // 2026-02-18 14:37:29 UTC, a Wednesday.
        val zone = ZoneId.of("UTC")
        val timestamp = ZonedDateTime.of(2026, 2, 18, 14, 37, 29, 0, zone).toInstant()
        fun expect(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
            ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()

        assertEquals(expect(2026, 2, 18, 14, 37), engine.toBucketInstant(timestamp, BucketSize.MINUTE, zone))
        assertEquals(expect(2026, 2, 18, 14, 0), engine.toBucketInstant(timestamp, BucketSize.HOUR, zone))
        assertEquals(expect(2026, 2, 18, 0, 0), engine.toBucketInstant(timestamp, BucketSize.DAY, zone))
        // Week truncates to the preceding Monday (2026-02-16).
        assertEquals(expect(2026, 2, 16, 0, 0), engine.toBucketInstant(timestamp, BucketSize.WEEK, zone))
        assertEquals(expect(2026, 2, 1, 0, 0), engine.toBucketInstant(timestamp, BucketSize.MONTH, zone))
    }

    @Test
    fun `toBucketInstant is DST-correct across a spring-forward day boundary`() {
        // US spring-forward 2026: clocks jump 02:00 -> 03:00 local on 2026-03-08. The day bucket
        // must be that day's real local midnight (not a fixed -24h slice), so a value logged just
        // after the jump still buckets to the 08th's local start-of-day.
        val zone = ZoneId.of("America/New_York")
        val afterJump = ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, zone).toInstant()
        val localMidnight = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone).toInstant()
        assertEquals(localMidnight, engine.toBucketInstant(afterJump, BucketSize.DAY, zone))
        // The hour bucket is the local 03:00 hour (the 02:00 hour does not exist that day).
        val localThreeAm = ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, zone).toInstant()
        assertEquals(localThreeAm, engine.toBucketInstant(afterJump, BucketSize.HOUR, zone))
    }

    @Test
    fun `aggregate values uses expected mode semantics`() {
        val values = listOf(1.0, 2.0, 3.0)
        assertEquals(2.0, engine.aggregateValues(values, AggregationMode.AVERAGE), 0.0001)
        assertEquals(6.0, engine.aggregateValues(values, AggregationMode.SUM), 0.0001)
        assertEquals(1.0, engine.aggregateValues(values, AggregationMode.MIN), 0.0001)
        assertEquals(3.0, engine.aggregateValues(values, AggregationMode.MAX), 0.0001)
    }

    @Test
    fun `smooth3 produces moving average with edge fallback`() {
        val smoothed = smooth3(listOf(10.0, 20.0, 30.0))
        assertEquals(listOf(15.0, 20.0, 25.0), smoothed)
    }
}
