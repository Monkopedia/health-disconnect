package com.monkopedia.healthdisconnect

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.FileProvider
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.RangeDisplay
import com.monkopedia.healthdisconnect.ui.ChartGeometry
import com.monkopedia.healthdisconnect.ui.ValueRange
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val BASELINE_WIDTH = 1600f
private const val BASELINE_HEIGHT = 1000f

fun writeGraphSharePng(
    context: Context,
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    theme: GraphShareTheme,
    seriesColorsOverride: List<Int>? = null
): File {
    val bitmap = renderGraphBitmap(
        title = title,
        seriesList = seriesList,
        settings = settings,
        theme = theme,
        seriesColorsOverride = seriesColorsOverride
    )
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeTitle = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "graph" }
    val file = File(exportDir, "health_disconnect_graph_${safeTitle}_${System.currentTimeMillis()}.png")
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

fun shareGraphImage(context: Context, imageFile: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.graph_share_subject, title))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(
            shareIntent,
            context.getString(R.string.graph_share_chooser)
        )
    )
}

internal data class GraphShareLayout(
    val chartLeft: Float,
    val chartTop: Float,
    val chartRight: Float,
    val chartBottom: Float,
    val dateY: Float,
    val dividerY: Float,
    val peakStartY: Float,
    val peakRowHeight: Float,
    val contentBottom: Float
)

internal fun computeGraphShareLayout(width: Int, height: Int, seriesCount: Int): GraphShareLayout {
    val xScale = width / BASELINE_WIDTH
    val yScale = height / BASELINE_HEIGHT
    fun sx(value: Float): Float = value * xScale
    fun sy(value: Float): Float = value * yScale

    val chartLeft = sx(160f)
    val chartTop = sy(140f)
    val chartRight = width - sx(120f)
    val chartBottom = sy(640f)
    val dateY = chartBottom + sy(44f)
    val dividerY = dateY + sy(20f)
    val peakStartY = dividerY + sy(40f)
    val peakRowHeight = sy(36f)
    val contentBottom = peakStartY + (peakRowHeight * seriesCount.coerceAtLeast(1))

    return GraphShareLayout(
        chartLeft = chartLeft,
        chartTop = chartTop,
        chartRight = chartRight,
        chartBottom = chartBottom,
        dateY = dateY,
        dividerY = dividerY,
        peakStartY = peakStartY,
        peakRowHeight = peakRowHeight,
        contentBottom = contentBottom
    )
}

internal fun graphShareContentHeight(
    width: Int,
    height: Int,
    seriesCount: Int,
    bottomPaddingPx: Float = 0f
): Int {
    val contentBottom = computeGraphShareLayout(
        width = width,
        height = height,
        seriesCount = seriesCount
    ).contentBottom
    return ceil(contentBottom + bottomPaddingPx).toInt().coerceIn(1, height)
}

fun renderGraphBitmap(
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    theme: GraphShareTheme,
    width: Int = 1600,
    height: Int = 1000,
    seriesColorsOverride: List<Int>? = null
): Bitmap {
    val palette = paletteFor(theme)
    val seriesColors = seriesColorsOverride
        ?.takeIf { it.isNotEmpty() }
        ?: palette.seriesColors
    val xScale = width / BASELINE_WIDTH
    val yScale = height / BASELINE_HEIGHT
    val scale = min(xScale, yScale)
    fun sx(value: Float): Float = value * xScale
    fun sy(value: Float): Float = value * yScale
    fun ss(value: Float): Float = value * scale

    val layout = computeGraphShareLayout(
        width = width,
        height = height,
        seriesCount = seriesList.size
    )

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(palette.backgroundColor)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textPrimary
        textSize = ss(48f)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textSecondary
        textSize = ss(28f)
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textSecondary
        textSize = ss(24f)
    }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.axisColor
        strokeWidth = ss(2f)
    }
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.gridColor
        strokeWidth = ss(1f)
    }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.dividerColor
        strokeWidth = ss(1f)
    }

    canvas.drawText(title, sx(56f), sy(72f), titlePaint)

    val allPoints = seriesList.flatMap { it.points }
    if (allPoints.isEmpty()) {
        canvas.drawText("No graphable metric values available yet.", sx(56f), sy(144f), bodyPaint)
        return bitmap
    }

    val allDates = allPoints.map { it.date }.distinct().sorted()

    val chartLeft = layout.chartLeft
    val chartTop = layout.chartTop
    val chartRight = layout.chartRight
    val chartBottom = layout.chartBottom
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop

    val geometry = ChartGeometry.create(
        seriesList = seriesList,
        settings = settings,
        xAxis = ChartGeometry.XAxisMode.DISCRETE_INDEX
    )

    fun rangeFor(index: Int): ValueRange = geometry.rangeFor(index)

    if (settings.backgroundStyle == ChartBackgroundStyle.HORIZONTAL_LINES ||
        settings.backgroundStyle == ChartBackgroundStyle.GRID
    ) {
        val rows = 4
        (0..rows).forEach { row ->
            val y = chartTop + (chartHeight * row / rows)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
    }
    if (settings.backgroundStyle == ChartBackgroundStyle.GRID) {
        val columns = max(1, allDates.size - 1)
        (0..columns).forEach { col ->
            val x = chartLeft + (chartWidth * col / columns)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
    }

    canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)
    canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

    fun pointToXY(point: HealthDataModel.MetricPoint, seriesIndex: Int): Pair<Float, Float> {
        val x = chartLeft + (chartWidth * geometry.xFraction(point.instant))
        val yNorm = geometry.normalized(point.value, seriesIndex)
        val y = chartBottom - (yNorm * chartHeight)
        return x to y
    }

    fun bandEdgeToXY(edge: ChartGeometry.BandEdge): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val x = chartLeft + (chartWidth * edge.x)
        val minY = chartTop + (edge.minYFractionFromTop * chartHeight)
        val maxY = chartTop + (edge.maxYFractionFromTop * chartHeight)
        return (x to minY) to (x to maxY)
    }

    if (settings.chartType == ChartType.LINE) {
        seriesList.forEachIndexed { seriesIndex, series ->
            geometry.bandFor(seriesIndex)?.let { band ->
                drawMetricBand(
                    canvas = canvas,
                    band = band,
                    color = seriesColors[seriesIndex % seriesColors.size],
                    rangeDisplay = settings.rangeDisplay,
                    edgeToXY = ::bandEdgeToXY,
                    strokeWidth = ss(2.5f)
                )
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = seriesColors[seriesIndex % seriesColors.size]
                strokeWidth = ss(4f)
                style = Paint.Style.STROKE
            }
            val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = seriesColors[seriesIndex % seriesColors.size]
                strokeWidth = ss(3f)
                style = Paint.Style.STROKE
            }
            val path = Path()
            series.points.forEachIndexed { index, point ->
                val (x, y) = pointToXY(point, seriesIndex)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                if (settings.showDataPoints) {
                    canvas.drawCircle(x, y, ss(5f), pointPaint)
                }
            }
            canvas.drawPath(path, linePaint)
        }
    } else {
        val slotWidth = chartWidth / allDates.size
        val barGroupWidth = slotWidth * 0.75f
        val barWidth = barGroupWidth / max(1, seriesList.size)
        allDates.forEachIndexed { index, date ->
            val groupLeft = chartLeft + (slotWidth * index) + ((slotWidth - barGroupWidth) / 2f)
            seriesList.forEachIndexed { seriesIndex, series ->
                val value = series.points.firstOrNull { it.date == date }?.value ?: return@forEachIndexed
                val yNorm = geometry.normalized(value, seriesIndex)
                val top = chartBottom - (yNorm * chartHeight)
                val left = groupLeft + (barWidth * seriesIndex)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = seriesColors[seriesIndex % seriesColors.size]
                    style = Paint.Style.FILL
                }
                canvas.drawRect(left, top, left + barWidth, chartBottom, barPaint)
            }
        }
    }

    when {
        seriesList.size == 1 -> {
            drawAxisLabels(
                canvas = canvas,
                x = sx(28f),
                yTop = chartTop + sy(8f),
                yBottom = chartBottom - sy(8f),
                alignRight = false,
                range = rangeFor(0),
                label = seriesList[0].label,
                unit = seriesList[0].unit,
                paint = labelPaint,
                color = seriesColors[0]
            )
        }

        seriesList.size == 2 -> {
            drawAxisLabels(
                canvas = canvas,
                x = sx(28f),
                yTop = chartTop + sy(8f),
                yBottom = chartBottom - sy(8f),
                alignRight = false,
                range = rangeFor(0),
                label = seriesList[0].label,
                unit = seriesList[0].unit,
                paint = labelPaint,
                color = seriesColors[0]
            )
            drawAxisLabels(
                canvas = canvas,
                x = width - sx(28f),
                yTop = chartTop + sy(8f),
                yBottom = chartBottom - sy(8f),
                alignRight = true,
                range = rangeFor(1),
                label = seriesList[1].label,
                unit = seriesList[1].unit,
                paint = labelPaint,
                color = seriesColors[1]
            )
        }

        else -> {
            drawAxisLabels(
                canvas = canvas,
                x = sx(28f),
                yTop = chartTop + sy(8f),
                yBottom = chartBottom - sy(8f),
                alignRight = false,
                range = ValueRange(0.0, 1.0),
                label = "Normalized",
                unit = null,
                paint = labelPaint,
                color = palette.textSecondary
            )
        }
    }

    val (firstDate, lastDate) = geometry.dataRangeLabels()
    val dateY = layout.dateY
    canvas.drawText(firstDate, chartLeft, dateY, bodyPaint)
    canvas.drawText(lastDate, chartRight - bodyPaint.measureText(lastDate), dateY, bodyPaint)

    val dividerY = layout.dividerY
    canvas.drawLine(chartLeft, dividerY, chartRight, dividerY, dividerPaint)

    var peakY = layout.peakStartY
    seriesList.forEachIndexed { index, series ->
        val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = seriesColors[index % seriesColors.size]
            textSize = ss(28f)
        }
        val text = "\u25A0 ${series.label} peak: ${formatValueWithUnit(series.peakValueInWindow, series.unit)}"
        canvas.drawText(text, chartLeft, peakY, peakPaint)
        peakY += layout.peakRowHeight
    }

    return bitmap
}

fun renderWidgetGraphBitmap(
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    theme: GraphShareTheme,
    width: Int = 1200,
    height: Int = 760,
    showCornerLabels: Boolean = true
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val palette = paletteFor(theme)
    val backgroundColor = if (theme == GraphShareTheme.DARK) 0xFF2A2832.toInt() else palette.backgroundColor
    canvas.drawColor(backgroundColor)
    val seriesColors = palette.seriesColors

    val allPoints = seriesList.flatMap { it.points }
    if (allPoints.isEmpty()) {
        return bitmap
    }

    val panelPaddingX = width * 0.01f
    val panelPaddingY = height * 0.02f
    val chartLeft = panelPaddingX
    val chartTop = panelPaddingY
    val chartRight = width - panelPaddingX
    val chartBottom = height - panelPaddingY
    val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
    val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

    val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(palette.gridColor, 0.1f)
        style = Paint.Style.FILL
    }
    val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(palette.axisColor, 0.14f)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, min(width, height) * 0.0024f)
    }
    val cornerRadius = min(width, height) * 0.035f
    canvas.drawRoundRect(
        chartLeft,
        chartTop,
        chartRight,
        chartBottom,
        cornerRadius,
        cornerRadius,
        panelPaint
    )
    canvas.drawRoundRect(
        chartLeft,
        chartTop,
        chartRight,
        chartBottom,
        cornerRadius,
        cornerRadius,
        panelStroke
    )

    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(palette.gridColor, 0.28f)
        strokeWidth = max(1f, min(width, height) * 0.0024f)
    }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(palette.axisColor, 0.42f)
        strokeWidth = max(1f, min(width, height) * 0.0032f)
    }

    if (settings.backgroundStyle == ChartBackgroundStyle.HORIZONTAL_LINES ||
        settings.backgroundStyle == ChartBackgroundStyle.GRID
    ) {
        val rows = if (height < 280) 2 else 3
        (0..rows).forEach { row ->
            val y = chartTop + (chartHeight * row / rows)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }
    }
    if (settings.backgroundStyle == ChartBackgroundStyle.GRID) {
        val allDates = allPoints.map { it.date }.distinct().sorted()
        val cols = max(1, allDates.size - 1)
        (0..cols).forEach { col ->
            val x = chartLeft + (chartWidth * col / cols)
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint)
        }
    }

    canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

    val allDates = allPoints.map { it.date }.distinct().sorted()
    val geometry = ChartGeometry.create(
        seriesList = seriesList,
        settings = settings,
        xAxis = ChartGeometry.XAxisMode.DISCRETE_INDEX
    )
    fun rangeFor(index: Int): ValueRange = geometry.rangeFor(index)

    fun pointToXY(point: HealthDataModel.MetricPoint, seriesIndex: Int): Pair<Float, Float> {
        val x = chartLeft + (chartWidth * geometry.xFraction(point.instant))
        val yNorm = geometry.normalized(point.value, seriesIndex)
        val y = chartBottom - (yNorm * chartHeight)
        return x to y
    }

    fun bandEdgeToXY(edge: ChartGeometry.BandEdge): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val x = chartLeft + (chartWidth * edge.x)
        val minY = chartTop + (edge.minYFractionFromTop * chartHeight)
        val maxY = chartTop + (edge.maxYFractionFromTop * chartHeight)
        return (x to minY) to (x to maxY)
    }

    if (settings.chartType == ChartType.LINE) {
        val pointRadius = max(1.5f, min(width, height) * 0.011f)
        val lineStroke = max(2.2f, min(width, height) * 0.011f)
        seriesList.forEachIndexed { seriesIndex, series ->
            val color = seriesColors[seriesIndex % seriesColors.size]
            geometry.bandFor(seriesIndex)?.let { band ->
                drawMetricBand(
                    canvas = canvas,
                    band = band,
                    color = color,
                    rangeDisplay = settings.rangeDisplay,
                    edgeToXY = ::bandEdgeToXY,
                    strokeWidth = lineStroke * 0.7f
                )
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = lineStroke
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            series.points.forEachIndexed { index, point ->
                val (x, y) = pointToXY(point, seriesIndex)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)
            if (settings.showDataPoints) {
                val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = withAlpha(color, 0.95f)
                    style = Paint.Style.FILL
                }
                series.points.forEach { point ->
                    val (x, y) = pointToXY(point, seriesIndex)
                    canvas.drawCircle(x, y, pointRadius, pointFill)
                }
            }
        }
    } else {
        val slotWidth = chartWidth / allDates.size
        val barGroupWidth = slotWidth * 0.82f
        val barWidth = barGroupWidth / max(1, seriesList.size)
        allDates.forEachIndexed { dateIndexValue, date ->
            val groupLeft = chartLeft + (slotWidth * dateIndexValue) + ((slotWidth - barGroupWidth) / 2f)
            seriesList.forEachIndexed { seriesIndex, series ->
                val value = series.points.firstOrNull { it.date == date }?.value ?: return@forEachIndexed
                val yNorm = geometry.normalized(value, seriesIndex)
                val top = chartBottom - (yNorm * chartHeight)
                val left = groupLeft + (barWidth * seriesIndex)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = seriesColors[seriesIndex % seriesColors.size]
                    style = Paint.Style.FILL
                }
                canvas.drawRect(left, top, left + barWidth, chartBottom, paint)
            }
        }
    }

    if (showCornerLabels) {
        val labelTextSize = (height * 0.11f).coerceIn(20f, 40f)
        val dateTextSize = (labelTextSize * 0.9f).coerceAtLeast(18f)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (theme == GraphShareTheme.DARK) 0xFFE6E0E9.toInt() else 0xFF1D1B20.toInt()
            textSize = labelTextSize
        }
        val datePaint = Paint(labelPaint).apply {
            textSize = dateTextSize
        }
        val labelChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(backgroundColor, if (theme == GraphShareTheme.DARK) 0.72f else 0.84f)
            style = Paint.Style.FILL
        }
        val labelChipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.axisColor, if (theme == GraphShareTheme.DARK) 0.28f else 0.2f)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, min(width, height) * 0.0022f)
        }
        val labelChipRadius = max(4f, min(width, height) * 0.02f)
        val labelPadX = labelTextSize * 0.24f
        val labelPadY = labelTextSize * 0.16f
        val labelInset = max(labelPadX, min(width, height) * 0.015f)

        fun drawLabelChip(
            text: String,
            anchorX: Float,
            baselineY: Float,
            alignRight: Boolean,
            paint: Paint
        ) {
            val textWidth = paint.measureText(text)
            val left = if (alignRight) anchorX - textWidth else anchorX
            val right = if (alignRight) anchorX else anchorX + textWidth
            val top = baselineY + paint.ascent()
            val bottom = baselineY + paint.descent()
            canvas.drawRoundRect(
                left - labelPadX,
                top - labelPadY,
                right + labelPadX,
                bottom + labelPadY,
                labelChipRadius,
                labelChipRadius,
                labelChipPaint
            )
            canvas.drawRoundRect(
                left - labelPadX,
                top - labelPadY,
                right + labelPadX,
                bottom + labelPadY,
                labelChipRadius,
                labelChipRadius,
                labelChipStroke
            )
            canvas.drawText(text, left, baselineY, paint)
        }

        val topLabelBaseline = chartTop + labelInset + labelTextSize
        val bottomLabelBaseline = chartBottom - labelInset - (labelTextSize * 0.08f)
        if (geometry.sortedInstants.size > 1) {
            // Intraday inherits the time-of-day labels from ChartGeometry; day-granular keeps the
            // widget's compact "MMM yy"/"MMM d" formatting for long/short spans.
            val (firstLabel, lastLabel) = if (geometry.isIntraday) {
                geometry.dataRangeLabels()
            } else {
                val dateFormatter = if (allDates.size > 365) {
                    DateTimeFormatter.ofPattern("MMM yy")
                } else {
                    DateTimeFormatter.ofPattern("MMM d")
                }
                allDates.first().format(dateFormatter) to allDates.last().format(dateFormatter)
            }
            drawLabelChip(
                firstLabel,
                chartLeft + labelInset,
                bottomLabelBaseline,
                alignRight = false,
                paint = datePaint
            )
            drawLabelChip(
                lastLabel,
                chartRight - labelInset,
                bottomLabelBaseline,
                alignRight = true,
                paint = datePaint
            )
        }
        if (seriesList.size == 1) {
            val range = rangeFor(0)
            val maxLabel = "\u2191 ${formatValueWithUnit(range.max, seriesList.first().unit)}"
            val minLabel = "\u2193 ${formatValueWithUnit(range.min, seriesList.first().unit)}"
            val maxLabelWidth = labelPaint.measureText(maxLabel)
            val minLabelWidth = labelPaint.measureText(minLabel)
            val neededWidth = maxLabelWidth + minLabelWidth + (labelInset * 5f)
            if (neededWidth <= chartWidth) {
                drawLabelChip(
                    maxLabel,
                    chartLeft + labelInset,
                    topLabelBaseline,
                    alignRight = false,
                    paint = labelPaint
                )
                drawLabelChip(
                    minLabel,
                    chartRight - labelInset,
                    topLabelBaseline,
                    alignRight = true,
                    paint = labelPaint
                )
            } else {
                drawLabelChip(
                    maxLabel,
                    chartLeft + labelInset,
                    topLabelBaseline,
                    alignRight = false,
                    paint = labelPaint
                )
            }
        }
    }

    return bitmap
}

/** Alpha of a series color for the filled BAND envelope (matches the on-screen chart). */
private const val BAND_FILL_ALPHA = 0.16f

/** Alpha of a series color for the LINES min/max dashed strokes (matches the on-screen chart). */
private const val BAND_LINE_ALPHA = 0.55f

/**
 * Draws a min/max/avg series' envelope onto [canvas]. BAND fills the min–max area at low alpha;
 * LINES draws lighter dashed min and max strokes. Shared by the share and widget bitmaps; the
 * envelope [band] geometry is produced by [ChartGeometry] so all surfaces draw the same shape.
 */
private fun drawMetricBand(
    canvas: Canvas,
    band: ChartGeometry.BandGeometry,
    color: Int,
    rangeDisplay: RangeDisplay,
    edgeToXY: (ChartGeometry.BandEdge) -> Pair<Pair<Float, Float>, Pair<Float, Float>>,
    strokeWidth: Float
) {
    val edges = band.edges
    if (edges.isEmpty()) return
    val bounds = edges.map(edgeToXY)
    when (rangeDisplay) {
        RangeDisplay.BAND -> {
            val path = Path()
            bounds.forEachIndexed { index, (_, maxXY) ->
                if (index == 0) path.moveTo(maxXY.first, maxXY.second) else path.lineTo(maxXY.first, maxXY.second)
            }
            bounds.asReversed().forEach { (minXY, _) -> path.lineTo(minXY.first, minXY.second) }
            path.close()
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(color, BAND_FILL_ALPHA)
                style = Paint.Style.FILL
            }
            canvas.drawPath(path, fillPaint)
        }
        RangeDisplay.LINES -> {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(color, BAND_LINE_ALPHA)
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 4f, strokeWidth * 3f), 0f)
            }
            listOf(
                bounds.map { it.first },
                bounds.map { it.second }
            ).forEach { line ->
                val path = Path()
                line.forEachIndexed { index, (x, y) ->
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }
}

private fun withAlpha(color: Int, alphaFraction: Float): Int {
    val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

private fun drawAxisLabels(
    canvas: Canvas,
    x: Float,
    yTop: Float,
    yBottom: Float,
    alignRight: Boolean,
    range: ValueRange,
    label: String,
    unit: String?,
    paint: Paint,
    color: Int
) {
    val axisPaint = Paint(paint).apply { this.color = color }
    val maxText = formatValueWithUnit(range.max, unit)
    val minText = formatValueWithUnit(range.min, unit)
    drawAxisText(canvas, maxText, x, yTop, alignRight, axisPaint)
    drawAxisText(canvas, label, x, (yTop + yBottom) / 2f, alignRight, axisPaint)
    drawAxisText(canvas, minText, x, yBottom, alignRight, axisPaint)
}

private fun drawAxisText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    alignRight: Boolean,
    paint: Paint
) {
    val textX = if (alignRight) x - paint.measureText(text) else x
    canvas.drawText(text, textX, y, paint)
}

private data class GraphSharePalette(
    val backgroundColor: Int,
    val axisColor: Int,
    val gridColor: Int,
    val dividerColor: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val seriesColors: List<Int>
)

private fun paletteFor(theme: GraphShareTheme): GraphSharePalette {
    return if (theme == GraphShareTheme.DARK) {
        GraphSharePalette(
            backgroundColor = 0xFF1C1B1F.toInt(),
            axisColor = 0xFFCAC4D0.toInt(),
            gridColor = 0x66CAC4D0,
            dividerColor = 0x66CAC4D0,
            textPrimary = 0xFFE6E0E9.toInt(),
            textSecondary = 0xFFCAC4D0.toInt(),
            seriesColors = defaultChartSeriesColors(GraphShareTheme.DARK)
        )
    } else {
        GraphSharePalette(
            backgroundColor = 0xFFFFFFFF.toInt(),
            axisColor = 0xFF79747E.toInt(),
            gridColor = 0x6679747E,
            dividerColor = 0x6679747E,
            textPrimary = 0xFF1D1B20.toInt(),
            textSecondary = 0xFF49454F.toInt(),
            seriesColors = defaultChartSeriesColors(GraphShareTheme.LIGHT)
        )
    }
}
