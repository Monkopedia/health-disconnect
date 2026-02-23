package com.monkopedia.healthdisconnect

import android.app.Application
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlin.math.roundToInt
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
    fun buildWidgetSummaryRows_mirrorsConfiguredSeriesRows() {
        val day = LocalDate.of(2026, 2, 22)
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(HealthDataModel.MetricPoint(day, 158.0)),
                peakValueInWindow = 160.0,
                minValueInWindow = 155.5,
                showMaxLabel = true,
                showMinLabel = false
            ),
            HealthDataModel.MetricSeries(
                label = "Heart rate",
                unit = "bpm",
                points = listOf(HealthDataModel.MetricPoint(day, 70.0)),
                peakValueInWindow = 81.0,
                minValueInWindow = 60.0,
                showMaxLabel = true,
                showMinLabel = true
            )
        )

        val rows = HealthDataWidgetUpdater.buildWidgetSummaryRows(app, series)

        assertEquals(3, rows.size)
        assertTrue(rows[0].startsWith("\u25A0 Weight max"))
        assertTrue(rows[1].startsWith("\u25A0 Heart rate max"))
        assertTrue(rows[2].startsWith("\u25A0 Heart rate min"))
    }

    @Test
    fun buildWidgetGraphLabels_singleSeriesIncludesValueLabelsWithoutWords() {
        val day = LocalDate.of(2026, 2, 22)
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(
                    HealthDataModel.MetricPoint(day.minusDays(6), 259.0),
                    HealthDataModel.MetricPoint(day, 256.0)
                ),
                showMaxLabel = true,
                showMinLabel = true
            )
        )

        val labels = HealthDataWidgetUpdater.buildWidgetGraphLabels(series)

        assertEquals("259 lb", labels.maxLabel)
        assertEquals("256 lb", labels.minLabel)
        assertTrue(labels.startDateLabel?.endsWith("16") == true)
        assertTrue(labels.endDateLabel?.endsWith("22") == true)
    }

    @Test
    fun buildWidgetGraphLabels_singleSeriesIncludesAxisLabelsEvenWhenSummaryMinMaxDisabled() {
        val day = LocalDate.of(2026, 2, 22)
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(
                    HealthDataModel.MetricPoint(day.minusDays(6), 259.0),
                    HealthDataModel.MetricPoint(day, 256.0)
                ),
                showMaxLabel = false,
                showMinLabel = false
            )
        )

        val labels = HealthDataWidgetUpdater.buildWidgetGraphLabels(series)

        assertEquals("259 lb", labels.maxLabel)
        assertEquals("256 lb", labels.minLabel)
    }

    @Test
    fun buildWidgetGraphLabels_forMultiSeriesStillUsesSharedDates() {
        val day = LocalDate.of(2026, 2, 22)
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(
                    HealthDataModel.MetricPoint(day.minusDays(6), 259.0),
                    HealthDataModel.MetricPoint(day, 256.0)
                )
            ),
            HealthDataModel.MetricSeries(
                label = "Heart rate",
                unit = "bpm",
                points = listOf(
                    HealthDataModel.MetricPoint(day.minusDays(5), 81.0),
                    HealthDataModel.MetricPoint(day, 66.0)
                )
            )
        )

        val labels = HealthDataWidgetUpdater.buildWidgetGraphLabels(series)

        assertEquals(null, labels.maxLabel)
        assertEquals(null, labels.minLabel)
        assertTrue(labels.startDateLabel?.endsWith("16") == true)
        assertTrue(labels.endDateLabel?.endsWith("22") == true)
    }

    @Test
    fun widgetLayoutProfile_compactHeight_hidesSummary() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 220, heightDp = 100)

        assertFalse(profile.showSummary)
        assertEquals(0, profile.summaryMaxLines)
    }

    @Test
    fun widgetLayoutProfile_default4x2_usesSingleSummaryLineAndWideGraph() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 250, heightDp = 120)

        assertTrue(profile.showSummary)
        assertEquals(1, profile.summaryMaxLines)
    }

    @Test
    fun widgetLayoutProfile_largeHeight_allowsMoreSummaryLines() {
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp = 320, heightDp = 240)

        assertTrue(profile.showSummary)
        assertEquals(3, profile.summaryMaxLines)
    }

    @Test
    fun resolveSummaryLayoutProfile_expandsSummaryLinesToFitConfiguredRows() {
        val metrics = app.resources.displayMetrics
        val sizeInfo = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 250,
            heightDp = 120,
            widthPx = (250f * metrics.density).roundToInt(),
            heightPx = (120f * metrics.density).roundToInt()
        )
        val baseProfile = HealthDataWidgetUpdater.widgetLayoutProfile(sizeInfo.widthDp, sizeInfo.heightDp)

        val resolvedProfile = HealthDataWidgetUpdater.resolveSummaryLayoutProfile(
            sizeInfo = sizeInfo,
            layoutProfile = baseProfile,
            summaryRowCount = 2,
            displayMetrics = metrics
        )

        assertTrue(resolvedProfile.showSummary)
        assertTrue(resolvedProfile.summaryMaxLines >= 2)
    }

    @Test
    fun resolveSummaryMaxLines_clampsRequestedRowsWhenHeightIsLimited() {
        val metrics = app.resources.displayMetrics
        val sizeInfo = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 250,
            heightDp = 120,
            widthPx = (250f * metrics.density).roundToInt(),
            heightPx = (120f * metrics.density).roundToInt()
        )
        val baseProfile = HealthDataWidgetUpdater.widgetLayoutProfile(sizeInfo.widthDp, sizeInfo.heightDp)

        val resolvedLines = HealthDataWidgetUpdater.resolveSummaryMaxLines(
            sizeInfo = sizeInfo,
            layoutProfile = baseProfile,
            requestedSummaryLines = 8,
            displayMetrics = metrics
        )

        assertTrue(resolvedLines in 1..7)
    }

    @Test
    fun estimateGraphRenderSizePx_scalesWithWidgetSize() {
        val metrics = app.resources.displayMetrics
        val small = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 250,
            heightDp = 120,
            widthPx = (250f * metrics.density).roundToInt(),
            heightPx = (120f * metrics.density).roundToInt()
        )
        val large = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 320,
            heightDp = 220,
            widthPx = (320f * metrics.density).roundToInt(),
            heightPx = (220f * metrics.density).roundToInt()
        )

        val smallRenderSize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
            sizeInfo = small,
            layoutProfile = HealthDataWidgetUpdater.widgetLayoutProfile(small.widthDp, small.heightDp),
            displayMetrics = metrics
        )
        val largeRenderSize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
            sizeInfo = large,
            layoutProfile = HealthDataWidgetUpdater.widgetLayoutProfile(large.widthDp, large.heightDp),
            displayMetrics = metrics
        )

        assertTrue(largeRenderSize.widthPx > smallRenderSize.widthPx)
        assertTrue(largeRenderSize.heightPx > smallRenderSize.heightPx)
    }

    @Test
    fun estimateGraphRenderSizePx_givesMoreHeightWhenSummaryIsHidden() {
        val metrics = app.resources.displayMetrics
        val sizeInfo = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 250,
            heightDp = 120,
            widthPx = (250f * metrics.density).roundToInt(),
            heightPx = (120f * metrics.density).roundToInt()
        )
        val hiddenSummaryProfile = HealthDataWidgetUpdater.WidgetLayoutProfile(
            showSummary = false,
            summaryMaxLines = 0,
            titleTextSizeSp = 14f,
            summaryTextSizeSp = 12f
        )
        val shownSummaryProfile = HealthDataWidgetUpdater.WidgetLayoutProfile(
            showSummary = true,
            summaryMaxLines = 1,
            titleTextSizeSp = 14f,
            summaryTextSizeSp = 12f
        )

        val hiddenSummarySize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
            sizeInfo = sizeInfo,
            layoutProfile = hiddenSummaryProfile,
            displayMetrics = metrics
        )
        val shownSummarySize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
            sizeInfo = sizeInfo,
            layoutProfile = shownSummaryProfile,
            displayMetrics = metrics
        )

        assertEquals(hiddenSummarySize.widthPx, shownSummarySize.widthPx)
        assertTrue(hiddenSummarySize.heightPx > shownSummarySize.heightPx)
    }

    @Test
    fun estimateGraphRenderSizePx_defaultWidgetAvoidsHeavyOversampling() {
        val metrics = app.resources.displayMetrics
        fun dp(value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            metrics
        )
        fun sp(value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            metrics
        )

        val sizeInfo = HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = 250,
            heightDp = 120,
            widthPx = (250f * metrics.density).roundToInt(),
            heightPx = (120f * metrics.density).roundToInt()
        )
        val profile = HealthDataWidgetUpdater.widgetLayoutProfile(sizeInfo.widthDp, sizeInfo.heightDp)
        val renderSize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
            sizeInfo = sizeInfo,
            layoutProfile = profile,
            displayMetrics = metrics
        )

        val verticalPadding = dp(8f) * 2f
        val graphTopMargin = dp(4f)
        val titleLineHeight = sp(profile.titleTextSizeSp) * 1.18f
        val summaryBlockHeight = dp(4f) + (sp(profile.summaryTextSizeSp) * profile.summaryMaxLines * 1.18f)
        val graphWidthPx = (sizeInfo.widthPx - dp(2f)).roundToInt()
        val graphHeightPx = (
            sizeInfo.heightPx -
                verticalPadding -
                graphTopMargin -
                titleLineHeight -
                summaryBlockHeight
            ).roundToInt()

        assertTrue(renderSize.widthPx <= (graphWidthPx * 1.1f).roundToInt())
        assertTrue(renderSize.heightPx <= (graphHeightPx * 1.1f).roundToInt())
    }

}
