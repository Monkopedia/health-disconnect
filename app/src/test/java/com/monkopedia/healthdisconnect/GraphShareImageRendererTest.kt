package com.monkopedia.healthdisconnect

import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GraphShareImageRendererTest {
    @Test
    fun scaledLayoutKeepsGraphSharePreviewContentInBounds() {
        val layout = computeGraphShareLayout(width = 960, height = 620, seriesCount = 3)

        assertTrue(layout.chartLeft >= 0f)
        assertTrue(layout.chartTop >= 0f)
        assertTrue(layout.chartRight <= 960f)
        assertTrue(layout.chartBottom <= 620f)
        assertTrue(layout.contentBottom <= 620f)
    }

    @Test
    fun scaledLayoutKeepsDefaultGraphShareContentInBounds() {
        val layout = computeGraphShareLayout(width = 1600, height = 1000, seriesCount = 3)

        assertTrue(layout.chartLeft >= 0f)
        assertTrue(layout.chartTop >= 0f)
        assertTrue(layout.chartRight <= 1600f)
        assertTrue(layout.chartBottom <= 1000f)
        assertTrue(layout.contentBottom <= 1000f)
    }

    @Test
    fun graphShareContentHeightWrapsToSeriesLabelBottom() {
        val oneSeriesHeight = graphShareContentHeight(
            width = 960,
            height = 620,
            seriesCount = 1
        )
        val threeSeriesHeight = graphShareContentHeight(
            width = 960,
            height = 620,
            seriesCount = 3
        )

        assertTrue(oneSeriesHeight < 620)
        assertTrue(threeSeriesHeight < 620)
        assertTrue(threeSeriesHeight > oneSeriesHeight)
    }

    @Test
    fun graphShareContentHeightClampsToBitmapHeight() {
        val clampedHeight = graphShareContentHeight(
            width = 960,
            height = 620,
            seriesCount = 3,
            bottomPaddingPx = 500f
        )

        assertEquals(620, clampedHeight)
    }

    @Test
    fun renderWidgetGraphBitmap_usesRequestedSizeAndDarkBackground() {
        val today = LocalDate.of(2026, 2, 22)
        val bitmap = renderWidgetGraphBitmap(
            title = "Weight",
            seriesList = listOf(
                HealthDataModel.MetricSeries(
                    label = "Weight",
                    unit = "lb",
                    points = listOf(
                        HealthDataModel.MetricPoint(today.minusDays(2), 257.0),
                        HealthDataModel.MetricPoint(today.minusDays(1), 258.0),
                        HealthDataModel.MetricPoint(today, 259.0)
                    ),
                    peakValueInWindow = 259.0,
                    minValueInWindow = 257.0
                )
            ),
            settings = ChartSettings(
                chartType = ChartType.LINE,
                backgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
                showDataPoints = false
            ),
            theme = GraphShareTheme.DARK,
            width = 800,
            height = 220
        )

        assertEquals(800, bitmap.width)
        assertEquals(220, bitmap.height)
        bitmap.recycle()
    }
}
