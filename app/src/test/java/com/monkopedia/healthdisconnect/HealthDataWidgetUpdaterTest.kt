package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    @Test
    fun widgetLayoutProfile_compactHeight_hidesSummary() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 220, heightDp = 100)

        assertFalse(profile.showSummary)
        assertEquals(0, profile.summaryMaxLines)
        assertEquals(0.66f, profile.graphHeightFraction, 0.0001f)
    }

    @Test
    fun widgetLayoutProfile_default4x2_usesSingleSummaryLineAndWideGraph() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 250, heightDp = 120)

        assertTrue(profile.showSummary)
        assertEquals(1, profile.summaryMaxLines)
        assertEquals(0.53f, profile.graphHeightFraction, 0.0001f)
    }

    @Test
    fun widgetLayoutProfile_largeHeight_allowsMoreSummaryLines() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 320, heightDp = 240)

        assertTrue(profile.showSummary)
        assertEquals(3, profile.summaryMaxLines)
        assertEquals(0.62f, profile.graphHeightFraction, 0.0001f)
    }
}
