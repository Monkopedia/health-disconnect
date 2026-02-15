package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.TimeWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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

    private fun invokeToBucketDate(date: LocalDate, bucketSize: BucketSize): LocalDate {
        return HealthDataModel::class.java
            .getDeclaredMethod("toBucketDate", LocalDate::class.java, BucketSize::class.java)
            .also { it.isAccessible = true }
            .invoke(model, date, bucketSize) as LocalDate
    }

    private fun invokeAggregateValues(values: List<Double>, mode: AggregationMode): Double {
        return HealthDataModel::class.java
            .getDeclaredMethod("aggregateValues", List::class.java, AggregationMode::class.java)
            .also { it.isAccessible = true }
            .invoke(model, values, mode) as Double
    }

    private fun invokeSmooth3(values: List<Double>): List<Double> {
        val points = values.mapIndexed { index, value ->
            HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 10 + index), value)
        }
        val smoothed = HealthDataModel::class.java
            .getDeclaredMethod("smooth3", List::class.java)
            .also { it.isAccessible = true }
            .invoke(model, points) as List<*>
        return smoothed.filterIsInstance<HealthDataModel.MetricPoint>()
            .map { it.value }
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
    fun `toBucketDate aligns to correct boundaries`() {
        val date = LocalDate.parse("2026-02-18")
        assertEquals(
            LocalDate.parse("2026-02-18"),
            invokeToBucketDate(date, BucketSize.DAY)
        )
        assertEquals(
            LocalDate.parse("2026-02-16"),
            invokeToBucketDate(date, BucketSize.WEEK)
        )
        assertEquals(
            LocalDate.parse("2026-02-01"),
            invokeToBucketDate(date, BucketSize.MONTH)
        )
    }

    @Test
    fun `aggregate values uses expected mode semantics`() {
        val values = listOf(1.0, 2.0, 3.0)
        assertEquals(2.0, invokeAggregateValues(values, AggregationMode.AVERAGE), 0.0001)
        assertEquals(6.0, invokeAggregateValues(values, AggregationMode.SUM), 0.0001)
        assertEquals(1.0, invokeAggregateValues(values, AggregationMode.MIN), 0.0001)
        assertEquals(3.0, invokeAggregateValues(values, AggregationMode.MAX), 0.0001)
    }

    @Test
    fun `smooth3 produces moving average with edge fallback`() {
        val smoothed = invokeSmooth3(listOf(10.0, 20.0, 30.0))
        assertEquals(listOf(15.0, 20.0, 25.0), smoothed)
    }
}
