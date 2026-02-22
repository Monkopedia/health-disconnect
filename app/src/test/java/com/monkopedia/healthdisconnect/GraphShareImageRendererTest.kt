package com.monkopedia.healthdisconnect

import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
            seriesList = demoWidgetSeries(today),
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

    @Test
    fun renderWidgetGraphBitmap_generatesSnapshotsForCommonWidgetSizes() {
        val today = LocalDate.of(2026, 2, 22)
        val outputDir = File("build/outputs/widget-renders").apply { mkdirs() }
        val sizes = listOf(
            Triple("widget_4x2_default", 1240, 320),
            Triple("widget_4x3_tall", 1240, 520),
            Triple("widget_5x2_wide", 1520, 320),
            Triple("widget_3x2_compact", 980, 320)
        )

        sizes.forEach { (name, width, height) ->
            val bitmap = renderWidgetGraphBitmap(
                title = "Weight",
                seriesList = demoWidgetSeries(today),
                settings = ChartSettings(
                    chartType = ChartType.LINE,
                    backgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
                    showDataPoints = false
                ),
                theme = GraphShareTheme.DARK,
                width = width,
                height = height
            )
            val outputFile = File(outputDir, "$name.png")
            outputFile.outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0L)
            bitmap.recycle()
        }
    }

    private fun demoWidgetSeries(today: LocalDate): List<HealthDataModel.MetricSeries> {
        return listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = listOf(
                    HealthDataModel.MetricPoint(today.minusDays(6), 258.9),
                    HealthDataModel.MetricPoint(today.minusDays(5), 258.4),
                    HealthDataModel.MetricPoint(today.minusDays(4), 258.0),
                    HealthDataModel.MetricPoint(today.minusDays(3), 257.8),
                    HealthDataModel.MetricPoint(today.minusDays(2), 258.1),
                    HealthDataModel.MetricPoint(today.minusDays(1), 257.7),
                    HealthDataModel.MetricPoint(today, 256.3)
                ),
                peakValueInWindow = 258.9,
                minValueInWindow = 256.3
            )
        )
    }
}
