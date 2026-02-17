package com.monkopedia.healthdisconnect

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.FileProvider
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.ui.ValueRange
import com.monkopedia.healthdisconnect.ui.seriesRangeFromPoints
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.max

fun writeGraphSharePng(
    context: Context,
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    theme: GraphShareTheme
): File {
    val bitmap = renderGraphBitmap(
        title = title,
        seriesList = seriesList,
        settings = settings,
        theme = theme
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

fun renderGraphBitmap(
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    theme: GraphShareTheme,
    width: Int = 1600,
    height: Int = 1000
): Bitmap {
    val palette = paletteFor(theme)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(palette.backgroundColor)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textPrimary
        textSize = 48f
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textSecondary
        textSize = 28f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textSecondary
        textSize = 24f
    }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.axisColor
        strokeWidth = 2f
    }
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.gridColor
        strokeWidth = 1f
    }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.dividerColor
        strokeWidth = 1f
    }

    canvas.drawText(title, 56f, 72f, titlePaint)

    val allPoints = seriesList.flatMap { it.points }
    if (allPoints.isEmpty()) {
        canvas.drawText("No graphable metric values available yet.", 56f, 144f, bodyPaint)
        return bitmap
    }

    val allDates = allPoints.map { it.date }.distinct().sorted()
    val spansMultipleYears = allDates.map { it.year }.distinct().size > 1
    val dateFormatter = if (spansMultipleYears) {
        DateTimeFormatter.ofPattern("MMM d, yyyy")
    } else {
        DateTimeFormatter.ofPattern("MMM d")
    }

    val chartLeft = 160f
    val chartTop = 140f
    val chartRight = width - 120f
    val chartBottom = 640f
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop

    val dateIndex = allDates.withIndex().associate { it.value to it.index }
    val useSeparateNormalization = seriesList.size > 1
    val globalRange = seriesRangeFromPoints(
        allPoints,
        seriesList.firstOrNull()?.yAxisMode ?: YAxisMode.AUTO
    )
    val perSeriesRanges = seriesList.map { series ->
        seriesRangeFromPoints(series.points, series.yAxisMode)
    }

    fun rangeFor(index: Int): ValueRange {
        return if (useSeparateNormalization) perSeriesRanges[index] else globalRange
    }

    fun normalized(value: Double, range: ValueRange): Float {
        return ((value - range.min) / (range.max - range.min)).toFloat().coerceIn(0f, 1f)
    }

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

    val xStep = if (allDates.size > 1) chartWidth / (allDates.size - 1) else 0f
    fun pointToXY(point: HealthDataModel.MetricPoint, seriesIndex: Int): Pair<Float, Float> {
        val index = dateIndex[point.date] ?: 0
        val x = chartLeft + (xStep * index)
        val yNorm = normalized(point.value, rangeFor(seriesIndex))
        val y = chartBottom - (yNorm * chartHeight)
        return x to y
    }

    val seriesColors = palette.seriesColors
    if (settings.chartType == ChartType.LINE) {
        seriesList.forEachIndexed { seriesIndex, series ->
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = seriesColors[seriesIndex % seriesColors.size]
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }
            val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = seriesColors[seriesIndex % seriesColors.size]
                strokeWidth = 3f
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
                    canvas.drawCircle(x, y, 5f, pointPaint)
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
                val yNorm = normalized(value, rangeFor(seriesIndex))
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
                x = 28f,
                yTop = chartTop + 8f,
                yBottom = chartBottom - 8f,
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
                x = 28f,
                yTop = chartTop + 8f,
                yBottom = chartBottom - 8f,
                alignRight = false,
                range = rangeFor(0),
                label = seriesList[0].label,
                unit = seriesList[0].unit,
                paint = labelPaint,
                color = seriesColors[0]
            )
            drawAxisLabels(
                canvas = canvas,
                x = width - 28f,
                yTop = chartTop + 8f,
                yBottom = chartBottom - 8f,
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
                x = 28f,
                yTop = chartTop + 8f,
                yBottom = chartBottom - 8f,
                alignRight = false,
                range = ValueRange(0.0, 1.0),
                label = "Normalized",
                unit = null,
                paint = labelPaint,
                color = palette.textSecondary
            )
        }
    }

    val firstDate = allDates.first().format(dateFormatter)
    val lastDate = allDates.last().format(dateFormatter)
    val dateY = chartBottom + 44f
    canvas.drawText(firstDate, chartLeft, dateY, bodyPaint)
    canvas.drawText(lastDate, chartRight - bodyPaint.measureText(lastDate), dateY, bodyPaint)

    val dividerY = dateY + 20f
    canvas.drawLine(chartLeft, dividerY, chartRight, dividerY, dividerPaint)

    var peakY = dividerY + 40f
    seriesList.forEachIndexed { index, series ->
        val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = seriesColors[index % seriesColors.size]
            textSize = 28f
        }
        val text = "\u25A0 ${series.label} peak: ${formatAxisValue(series.peakValueInWindow)}${unitSuffix(series.unit)}"
        canvas.drawText(text, chartLeft, peakY, peakPaint)
        peakY += 36f
    }

    return bitmap
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
    val maxText = "${formatAxisValue(range.max)}${unitSuffix(unit)}"
    val minText = "${formatAxisValue(range.min)}${unitSuffix(unit)}"
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
            seriesColors = listOf(
                0xFFD0BCFF.toInt(),
                0xFFD1B3FF.toInt(),
                0xFFFFB74D.toInt(),
                0xFF81C784.toInt(),
                0xFF4FC3F7.toInt(),
                0xFFE57373.toInt()
            )
        )
    } else {
        GraphSharePalette(
            backgroundColor = 0xFFFFFFFF.toInt(),
            axisColor = 0xFF79747E.toInt(),
            gridColor = 0x6679747E,
            dividerColor = 0x6679747E,
            textPrimary = 0xFF1D1B20.toInt(),
            textSecondary = 0xFF49454F.toInt(),
            seriesColors = listOf(
                0xFF6750A4.toInt(),
                0xFF7D5260.toInt(),
                0xFF625B71.toInt(),
                0xFF2E7D32.toInt(),
                0xFFC62828.toInt(),
                0xFF1565C0.toInt()
            )
        )
    }
}
