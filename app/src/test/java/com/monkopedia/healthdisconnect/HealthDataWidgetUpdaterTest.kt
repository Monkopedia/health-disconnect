package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataWidgetUpdaterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun buildWidgetSummaryText_includesEnabledMinAndMaxRows() {
        val day = LocalDate.of(2026, 2, 22)
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(HealthDataModel.MetricPoint(day, 158.0)),
                peakValueInWindow = 160.0,
                minValueInWindow = 155.5,
                showMaxLabel = true,
                showMinLabel = true
            ),
            HealthDataModel.MetricSeries(
                label = "Heart rate",
                unit = "bpm",
                points = listOf(HealthDataModel.MetricPoint(day, 70.0)),
                peakValueInWindow = 81.0,
                minValueInWindow = 60.0,
                showMaxLabel = false,
                showMinLabel = true
            )
        )

        val summary = HealthDataWidgetUpdater.buildWidgetSummaryText(app, series)

        assertTrue(summary.contains("Weight max"))
        assertTrue(summary.contains("Weight min"))
        assertTrue(summary.contains("Heart rate min"))
        assertFalse(summary.contains("Heart rate max"))
    }
}
