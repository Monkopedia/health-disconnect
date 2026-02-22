package com.monkopedia.healthdisconnect

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphShareImageRendererTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

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
            Triple("widget_3x2_compact", 980, 320),
            Triple("widget_runtime_default_4x2", 760, 210),
            Triple("widget_runtime_compact_4x2", 640, 170)
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

    @Test
    fun renderWidgetGraphBitmap_generatesLauncherHostSnapshots() {
        val today = LocalDate.of(2026, 2, 22)
        val outputDir = File("build/outputs/widget-renders").apply { mkdirs() }
        val widgetSizes = listOf(
            Triple("widget_launcher_host_default_4x2", 322, 121),
            Triple("widget_launcher_host_compact_4x2", 280, 112),
            Triple("widget_launcher_host_tall_4x3", 322, 180)
        )

        widgetSizes.forEach { (name, widthDp, heightDp) ->
            val sizeInfo = widgetSizeInfo(widthDp = widthDp, heightDp = heightDp)
            val layoutProfile = HealthDataWidgetUpdater.widgetLayoutProfile(widthDp, heightDp)
            val seriesList = demoWidgetSeries(today)
            val graphRenderSize = HealthDataWidgetUpdater.estimateGraphRenderSizePx(
                sizeInfo = sizeInfo,
                layoutProfile = layoutProfile,
                displayMetrics = app.resources.displayMetrics
            )
            val graphBitmap = renderWidgetGraphBitmap(
                title = "Weight",
                seriesList = seriesList,
                settings = ChartSettings(
                    chartType = ChartType.LINE,
                    backgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
                    showDataPoints = false
                ),
                theme = GraphShareTheme.DARK,
                width = graphRenderSize.widthPx,
                height = graphRenderSize.heightPx,
                showCornerLabels = false
            )
            val graphLabels = HealthDataWidgetUpdater.buildWidgetGraphLabels(seriesList)
            val launcherBitmap = renderWidgetInLauncherHost(
                widthPx = sizeInfo.widthPx,
                heightPx = sizeInfo.heightPx,
                layoutProfile = layoutProfile,
                graphBitmap = graphBitmap,
                graphLabels = graphLabels
            )

            val outputFile = File(outputDir, "$name.png")
            outputFile.outputStream().use { stream ->
                launcherBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0L)

            graphBitmap.recycle()
            launcherBitmap.recycle()
        }
    }

    private fun widgetSizeInfo(widthDp: Int, heightDp: Int): HealthDataWidgetUpdater.WidgetSizeInfo {
        val density = app.resources.displayMetrics.density
        return HealthDataWidgetUpdater.WidgetSizeInfo(
            widthDp = widthDp,
            heightDp = heightDp,
            widthPx = (widthDp * density).roundToInt(),
            heightPx = (heightDp * density).roundToInt()
        )
    }

    private fun renderWidgetInLauncherHost(
        widthPx: Int,
        heightPx: Int,
        layoutProfile: HealthDataWidgetUpdater.WidgetLayoutProfile,
        graphBitmap: Bitmap,
        graphLabels: HealthDataWidgetUpdater.WidgetGraphLabels
    ): Bitmap {
        val view = LayoutInflater.from(app).inflate(
            R.layout.health_graph_widget,
            null,
            false
        )

        view.findViewById<TextView>(R.id.widget_title).apply {
            text = "Weight"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, layoutProfile.titleTextSizeSp)
        }
        view.findViewById<TextView>(R.id.widget_empty).visibility = View.GONE
        view.findViewById<ImageView>(R.id.widget_graph).apply {
            visibility = View.VISIBLE
            setImageBitmap(graphBitmap)
        }
        applyWidgetGraphLabel(
            labelView = view.findViewById(R.id.widget_graph_label_top_start),
            text = graphLabels.maxLabel
        )
        applyWidgetGraphLabel(
            labelView = view.findViewById(R.id.widget_graph_label_top_end),
            text = graphLabels.minLabel
        )
        applyWidgetGraphLabel(
            labelView = view.findViewById(R.id.widget_graph_label_bottom_start),
            text = graphLabels.startDateLabel
        )
        applyWidgetGraphLabel(
            labelView = view.findViewById(R.id.widget_graph_label_bottom_end),
            text = graphLabels.endDateLabel
        )
        view.findViewById<TextView>(R.id.widget_summary).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, layoutProfile.summaryTextSizeSp)
            maxLines = layoutProfile.summaryMaxLines
            text = "Weight max: 259 pounds"
            visibility = if (layoutProfile.showSummary) View.VISIBLE else View.GONE
        }

        view.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, widthPx, heightPx)

        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            view.draw(canvas)
        }
    }

    private fun applyWidgetGraphLabel(labelView: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            labelView.visibility = View.GONE
            labelView.text = ""
        } else {
            labelView.visibility = View.VISIBLE
            labelView.text = text
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
