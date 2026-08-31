package com.monkopedia.healthdisconnect

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        // The "dark background" half of this test's name, which used to be asserted nowhere: the
        // widget paints its own darker panel color rather than the share palette's background.
        assertEquals(DARK_WIDGET_BACKGROUND, bitmap.getPixel(1, 1))
        assertNotEquals(DARK_SHARE_BACKGROUND, bitmap.getPixel(1, 1))
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()
        val ink = bitmap.countPixels { it == seriesColor }
        assertTrue(
            "expected a drawn series in the widget bitmap, saw $ink px",
            ink >= MIN_WIDGET_SERIES_INK
        )
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
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            assertTrue(outputFile.exists())

            // `length() > 0` passes on an all-transparent bitmap, so the snapshot is checked for
            // painted content at every size instead.
            val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()
            val ink = bitmap.countPixels { it == seriesColor }
            assertTrue("$name rendered only $ink px of series ink", ink >= MIN_WIDGET_SERIES_INK)
            val distinctColors = bitmap.distinctColorCount()
            assertTrue(
                "$name rendered only $distinctColors colors",
                distinctColors >= MIN_WIDGET_DISTINCT_COLORS
            )
            assertEquals(DARK_WIDGET_BACKGROUND, bitmap.getPixel(1, 1))
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

            // The graph bitmap is scaled fitXY into the ImageView, so its series color is
            // resampled and no exact color survives; what a blank host capture cannot fake is a
            // fully painted, many-colored surface. (#91's transparent Roborazzi captures are the
            // failure this shape is aimed at.)
            val opaqueFraction = launcherBitmap.opaqueFraction()
            assertTrue(
                "$name captured only ${(opaqueFraction * 100).roundToInt()}% opaque pixels",
                opaqueFraction >= MIN_LAUNCHER_OPAQUE_FRACTION
            )
            val distinctColors = launcherBitmap.distinctColorCount()
            assertTrue(
                "$name captured only $distinctColors colors",
                distinctColors >= MIN_LAUNCHER_DISTINCT_COLORS
            )
            val graphInk = graphBitmap.countPixels {
                it == defaultChartSeriesColors(GraphShareTheme.DARK).first()
            }
            assertTrue("$name embedded a graph with only $graphInk px of series ink", graphInk > 0)

            graphBitmap.recycle()
            launcherBitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Share-image renderer (renderGraphBitmap / writeGraphSharePng).
    //
    // Every assertion below is chosen so a renderer that draws nothing — a blank or fully
    // transparent bitmap — fails it. Width/height/exists/length are deliberately never the whole
    // of a test here: an all-transparent bitmap satisfies all of those (issue #84).
    // ---------------------------------------------------------------------------------------

    @Test
    fun renderGraphBitmap_drawsSeriesLineOnDarkBackground() {
        val bitmap = renderShareBitmap(
            seriesList = listOf(risingSeries()),
            theme = GraphShareTheme.DARK
        )
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()

        // The "dark background" claim itself: an untouched corner carries the dark palette color,
        // and it is not the light palette's white.
        assertEquals(DARK_SHARE_BACKGROUND, bitmap.getPixel(1, 1))
        assertNotEquals(LIGHT_SHARE_BACKGROUND, bitmap.getPixel(1, 1))

        val ink = bitmap.countPlotPixels(plot) { it == seriesColor }
        assertTrue(
            "expected the series stroke to cover a run of the plot, saw $ink px",
            ink >= MIN_SHARE_SERIES_INK
        )
        val distinctColors = bitmap.distinctColorCount()
        assertTrue(
            "expected axes, grid, text and series to produce many colors, saw $distinctColors",
            distinctColors >= MIN_SHARE_DISTINCT_COLORS
        )
        bitmap.recycle()
    }

    @Test
    fun renderGraphBitmap_placesRisingSeriesFromPlotFloorToPlotCeiling() {
        val bitmap = renderShareBitmap(
            seriesList = listOf(risingSeries()),
            theme = GraphShareTheme.DARK
        )
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()

        // The lowest value is the leftmost point and the highest the rightmost, so the stroke has
        // to start on the plot floor and end on the plot ceiling. A renderer that draws nothing, a
        // flat line, or an offset line fails this.
        val leftColumn = plot.chartLeft.roundToInt() + 1
        val rightColumn = plot.chartRight.roundToInt() - 1
        val leftInkY = bitmap.firstInkYInColumn(leftColumn, plot) { it == seriesColor }
        val rightInkY = bitmap.firstInkYInColumn(rightColumn, plot) { it == seriesColor }
        assertTrue("no series ink at the left edge of the plot", leftInkY >= 0)
        assertTrue("no series ink at the right edge of the plot", rightInkY >= 0)
        assertTrue(
            "series should start on the plot floor (${plot.chartBottom}), ink was at $leftInkY",
            abs(leftInkY - plot.chartBottom) <= SHARE_LINE_STROKE_PX
        )
        assertTrue(
            "series should end on the plot ceiling (${plot.chartTop}), ink was at $rightInkY",
            abs(rightInkY - plot.chartTop) <= SHARE_LINE_STROKE_PX
        )
        bitmap.recycle()
    }

    @Test
    fun renderGraphBitmap_barsRiseFromTheAxisBaseline() {
        val bitmap = renderShareBitmap(
            seriesList = listOf(risingSeries()),
            theme = GraphShareTheme.DARK,
            chartType = ChartType.BARS
        )
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()

        val ink = bitmap.countPlotPixels(plot) { it == seriesColor }
        assertTrue("expected filled bars inside the plot, saw $ink px", ink >= MIN_SHARE_SERIES_INK)

        // A bar is anchored on the baseline and the tallest bar is the last slot, so that slot's
        // center column is series-colored from just above the axis all the way to the plot ceiling.
        val slotWidth = (plot.chartRight - plot.chartLeft) / 7f
        val lastSlotCenterX = (plot.chartRight - (slotWidth / 2f)).roundToInt()
        val baselineY = plot.chartBottom.roundToInt() - 1
        assertEquals(seriesColor, bitmap.getPixel(lastSlotCenterX, baselineY))
        assertEquals(seriesColor, bitmap.getPixel(lastSlotCenterX, plot.chartTop.roundToInt() + 1))
        bitmap.recycle()
    }

    @Test
    fun renderGraphBitmap_lightThemeUsesLightBackgroundAndStillDrawsSeries() {
        val bitmap = renderShareBitmap(
            seriesList = listOf(risingSeries()),
            theme = GraphShareTheme.LIGHT
        )
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.LIGHT).first()

        assertEquals(LIGHT_SHARE_BACKGROUND, bitmap.getPixel(1, 1))
        val ink = bitmap.countPlotPixels(plot) { it == seriesColor }
        assertTrue(
            "expected the series stroke in the light theme, saw $ink px",
            ink >= MIN_SHARE_SERIES_INK
        )
        bitmap.recycle()
    }

    /**
     * The standing negative control for the ink assertions above: with no data there is no series
     * ink at all. It pins the zero the other tests measure against — if the empty render ever
     * starts painting series color inside the plot, "ink is present" stops distinguishing anything
     * and this test says so. It still demands the painted empty state (background plus title and
     * placeholder text), so a renderer that returns a blank bitmap fails here too.
     */
    @Test
    fun renderGraphBitmap_withNoDataDrawsTheEmptyStateAndNoSeriesInk() {
        val bitmap = renderShareBitmap(seriesList = emptyList(), theme = GraphShareTheme.DARK)
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 0)
        val seriesColors = defaultChartSeriesColors(GraphShareTheme.DARK).toSet()

        assertEquals(0, bitmap.countPlotPixels(plot) { it in seriesColors })
        assertEquals(DARK_SHARE_BACKGROUND, bitmap.getPixel(1, 1))
        val titleInk = bitmap.countPixels { it == DARK_TEXT_PRIMARY }
        assertTrue(
            "the title was not drawn, saw $titleInk px",
            titleInk >= MIN_EMPTY_STATE_TEXT_INK
        )
        val messageInk = bitmap.countPixels { it == DARK_TEXT_SECONDARY }
        assertTrue(
            "the empty-state message was not drawn, saw $messageInk px",
            messageInk >= MIN_EMPTY_STATE_TEXT_INK
        )
        bitmap.recycle()
    }

    @Test
    fun writeGraphSharePng_writesAPngThatDecodesBackToARenderedChart() {
        val file = writeGraphSharePng(
            context = app,
            title = "Weight",
            seriesList = listOf(risingSeries()),
            settings = shareSettings(ChartType.LINE),
            theme = GraphShareTheme.DARK
        )
        assertTrue(file.exists())

        // Decoding the file is what makes this more than a length check: an all-transparent bitmap
        // also compresses to a valid, non-empty PNG.
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(SHARE_WIDTH, decoded.width)
        assertEquals(SHARE_HEIGHT, decoded.height)
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()
        val ink = decoded.countPlotPixels(plot) { it == seriesColor }
        assertTrue(
            "the shared PNG decoded with only $ink px of series ink",
            ink >= MIN_SHARE_SERIES_INK
        )
        assertEquals(DARK_SHARE_BACKGROUND, decoded.getPixel(1, 1))
        decoded.recycle()
        file.delete()
    }

    // ---------------------------------------------------------------------------------------
    // A lone data point (#83). One point gives the line Path a single `moveTo` and no `lineTo`,
    // and `drawPath` paints nothing for such a path, so the marker is the only thing that can
    // carry the value on these two bitmap surfaces. The on-screen chart already draws it
    // unconditionally; these tests hold the bitmaps to the same contract, in both settings of
    // `showDataPoints` — false is the ChartSettings default and the configuration users hit.
    //
    // The assertion is a position property, not an ink floor: the mark has to sit on the lone
    // point's own coordinates and nowhere else, so a renderer that paints something plentiful
    // but wrong fails just as a blank one does.
    // ---------------------------------------------------------------------------------------

    @Test
    fun renderGraphBitmap_marksALonePointWithDataPointsHidden() {
        assertShareMarksLonePoint(showDataPoints = false)
    }

    @Test
    fun renderGraphBitmap_marksALonePointWithDataPointsShown() {
        assertShareMarksLonePoint(showDataPoints = true)
    }

    @Test
    fun renderWidgetGraphBitmap_marksALonePointWithDataPointsHidden() {
        assertWidgetMarksLonePoint(showDataPoints = false)
    }

    @Test
    fun renderWidgetGraphBitmap_marksALonePointWithDataPointsShown() {
        assertWidgetMarksLonePoint(showDataPoints = true)
    }

    private fun assertShareMarksLonePoint(showDataPoints: Boolean) {
        val bitmap = renderShareBitmap(
            seriesList = listOf(lonePointSeries()),
            theme = GraphShareTheme.DARK,
            showDataPoints = showDataPoints
        )
        val plot = computeGraphShareLayout(SHARE_WIDTH, SHARE_HEIGHT, seriesCount = 1)
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()

        assertLonePointMark(
            bitmap = bitmap,
            area = plot.chartRect(),
            // The single bucket is the discrete axis' only slot, so it sits on the plot's left
            // edge; its value is the only one in range, so it normalizes to the vertical middle.
            expectedX = plot.chartLeft,
            expectedY = (plot.chartTop + plot.chartBottom) / 2f,
            tolerancePx = SHARE_MARKER_TOLERANCE_PX,
            what = "share PNG (showDataPoints=$showDataPoints)",
            predicate = { it == seriesColor }
        )
        bitmap.recycle()
    }

    private fun assertWidgetMarksLonePoint(showDataPoints: Boolean) {
        val bitmap = renderWidgetGraphBitmap(
            title = "Weight",
            seriesList = listOf(lonePointSeries()),
            settings = ChartSettings(
                chartType = ChartType.LINE,
                backgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
                showDataPoints = showDataPoints
            ),
            theme = GraphShareTheme.DARK,
            width = WIDGET_WIDTH,
            height = WIDGET_HEIGHT
        )
        val seriesColor = defaultChartSeriesColors(GraphShareTheme.DARK).first()

        assertLonePointMark(
            bitmap = bitmap,
            area = Rect(0, 0, bitmap.width, bitmap.height),
            // The widget's panel inset, and the same centered normalization as the share render.
            expectedX = WIDGET_WIDTH * 0.01f,
            expectedY = WIDGET_HEIGHT / 2f,
            tolerancePx = WIDGET_MARKER_TOLERANCE_PX,
            what = "widget (showDataPoints=$showDataPoints)",
            // The widget fills its markers at 95% alpha, so the painted pixels are the series
            // color blended into the panel rather than an exact match.
            predicate = { it.isNear(seriesColor) }
        )
        bitmap.recycle()
    }

    /**
     * Asserts [bitmap] carries a mark for a lone data point at ([expectedX], [expectedY]): some
     * matching pixels exist, they bracket the point's value, and none of them stray further than
     * [tolerancePx] from it.
     */
    private fun assertLonePointMark(
        bitmap: Bitmap,
        area: Rect,
        expectedX: Float,
        expectedY: Float,
        tolerancePx: Float,
        what: String,
        predicate: (Int) -> Boolean
    ) {
        val ink = bitmap.inkBounds(area, predicate)
        assertNotNull("$what drew no series ink at all for a single-point line chart", ink)
        checkNotNull(ink)
        val allowed = InkBounds(
            left = (expectedX - tolerancePx).roundToInt(),
            top = (expectedY - tolerancePx).roundToInt(),
            right = (expectedX + tolerancePx).roundToInt(),
            bottom = (expectedY + tolerancePx).roundToInt()
        )
        assertTrue(
            "$what painted series ink at $ink, which strays outside $allowed around the lone point",
            ink.left >= allowed.left && ink.top >= allowed.top &&
                ink.right <= allowed.right && ink.bottom <= allowed.bottom
        )
        assertTrue(
            "$what marked $ink, which does not span the lone point's value at y=$expectedY",
            ink.top <= expectedY && ink.bottom >= expectedY
        )
    }

    private fun renderShareBitmap(
        seriesList: List<HealthDataModel.MetricSeries>,
        theme: GraphShareTheme,
        chartType: ChartType = ChartType.LINE,
        showDataPoints: Boolean = false
    ): Bitmap = renderGraphBitmap(
        title = "Weight",
        seriesList = seriesList,
        settings = shareSettings(chartType, showDataPoints),
        theme = theme,
        width = SHARE_WIDTH,
        height = SHARE_HEIGHT
    )

    private fun shareSettings(chartType: ChartType, showDataPoints: Boolean = false) = ChartSettings(
        chartType = chartType,
        backgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
        showDataPoints = showDataPoints
    )

    /** A series with exactly one point — the case that has no line segment to draw. */
    private fun lonePointSeries(
        today: LocalDate = LocalDate.of(2026, 2, 22)
    ): HealthDataModel.MetricSeries = HealthDataModel.MetricSeries(
        label = "Weight",
        unit = "lb",
        points = listOf(HealthDataModel.MetricPoint(today, 200.0))
    )

    /** Seven strictly increasing points, so the drawn line has a known start and end position. */
    private fun risingSeries(
        today: LocalDate = LocalDate.of(2026, 2, 22)
    ): HealthDataModel.MetricSeries {
        return HealthDataModel.MetricSeries(
            label = "Weight",
            unit = "lb",
            points = (0..6).map { day ->
                val date = today.minusDays((6 - day).toLong())
                HealthDataModel.MetricPoint(date, 200.0 + (day * 10.0))
            }
        )
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
        view.findViewById<View>(R.id.widget_graph_labels_top_row).visibility =
            if (graphLabels.maxLabel.isNullOrBlank() && graphLabels.minLabel.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        view.findViewById<View>(R.id.widget_graph_labels_bottom_row).visibility =
            if (graphLabels.startDateLabel.isNullOrBlank() && graphLabels.endDateLabel.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
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

    // ---------------------------------------------------------------------------------------
    // Pixel helpers. These read what was actually painted, which is the only way these tests can
    // tell a rendered chart apart from a blank bitmap.
    // ---------------------------------------------------------------------------------------

    /** Number of pixels inside [plot]'s chart rect whose color satisfies [predicate]. */
    private fun Bitmap.countPlotPixels(plot: GraphShareLayout, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in plot.chartTop.roundToInt() until plot.chartBottom.roundToInt()) {
            for (x in plot.chartLeft.roundToInt() until plot.chartRight.roundToInt()) {
                if (predicate(getPixel(x, y))) count++
            }
        }
        return count
    }

    /** Number of pixels anywhere in the bitmap whose color satisfies [predicate]. */
    private fun Bitmap.countPixels(predicate: (Int) -> Boolean): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count(predicate)
    }

    /** Inclusive bounding box of matching pixels. */
    private data class InkBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Bounding box of the pixels inside [area] matching [predicate], or null when none match. */
    private fun Bitmap.inkBounds(area: Rect, predicate: (Int) -> Boolean): InkBounds? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                if (!predicate(getPixel(x, y))) continue
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        return if (right < left) null else InkBounds(left, top, right, bottom)
    }

    /** The plot rect this layout describes, rounded to whole pixels. */
    private fun GraphShareLayout.chartRect(): Rect = Rect(
        chartLeft.roundToInt(),
        chartTop.roundToInt(),
        chartRight.roundToInt(),
        chartBottom.roundToInt()
    )

    /** True when every channel is within [tolerance] of [target]'s — for blended, non-exact paint. */
    private fun Int.isNear(target: Int, tolerance: Int = CHANNEL_TOLERANCE): Boolean =
        (0..24 step 8).all { shift ->
            abs(((this ushr shift) and 0xFF) - ((target ushr shift) and 0xFF)) <= tolerance
        }

    /** Topmost Y inside [plot] on column [x] matching [predicate], or -1 if the column is bare. */
    private fun Bitmap.firstInkYInColumn(
        x: Int,
        plot: GraphShareLayout,
        predicate: (Int) -> Boolean
    ): Int {
        for (y in plot.chartTop.roundToInt() until plot.chartBottom.roundToInt()) {
            if (predicate(getPixel(x, y))) return y
        }
        return -1
    }

    /** Distinct ARGB values across the whole bitmap. A blank render has exactly one. */
    private fun Bitmap.distinctColorCount(): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.toHashSet().size
    }

    /** Fraction of pixels that are fully opaque — a transparent capture scores 0. */
    private fun Bitmap.opaqueFraction(): Float {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count { (it ushr 24) == 0xFF }.toFloat() / pixels.size
    }

    private companion object {
        const val SHARE_WIDTH = 1600
        const val SHARE_HEIGHT = 1000

        const val WIDGET_WIDTH = 800
        const val WIDGET_HEIGHT = 220

        /** `ss(4f)` in [renderGraphBitmap] at the baseline 1600x1000 size. */
        const val SHARE_LINE_STROKE_PX = 4f

        /**
         * How far a lone point's marker may reach from the point itself: the marker radius plus
         * its stroke, rounded up. `ss(5f)` + `ss(3f)` in the share render at the baseline size;
         * `min(w, h) * 0.011` filled in the widget render.
         */
        const val SHARE_MARKER_TOLERANCE_PX = 8f
        const val WIDGET_MARKER_TOLERANCE_PX = 6f

        /** Per-channel slack for paint composited at less than full alpha. */
        const val CHANNEL_TOLERANCE = 16

        val DARK_SHARE_BACKGROUND = 0xFF1C1B1F.toInt()
        val LIGHT_SHARE_BACKGROUND = 0xFFFFFFFF.toInt()
        val DARK_TEXT_PRIMARY = 0xFFE6E0E9.toInt()
        val DARK_TEXT_SECONDARY = 0xFFCAC4D0.toInt()

        /** Dark widget panel background from `renderWidgetGraphBitmap`. */
        val DARK_WIDGET_BACKGROUND = 0xFF2A2832.toInt()

        /**
         * Floors, not tuned thresholds: the drawn stroke spans the plot width at several pixels
         * thick, so real renders land far above these while a blank one scores zero.
         */
        const val MIN_SHARE_SERIES_INK = 500
        const val MIN_EMPTY_STATE_TEXT_INK = 100
        const val MIN_SHARE_DISTINCT_COLORS = 16
        const val MIN_WIDGET_SERIES_INK = 100
        const val MIN_WIDGET_DISTINCT_COLORS = 8
        const val MIN_LAUNCHER_DISTINCT_COLORS = 8
        const val MIN_LAUNCHER_OPAQUE_FRACTION = 0.5f
    }
}
