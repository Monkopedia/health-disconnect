package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.formatAxisValue
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.unitSuffix
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
internal fun GraphStatePlaceholder(
    isLoading: Boolean,
    message: String,
    reserveLegendRows: Int
) {
    val axisColor: Color = MaterialTheme.colorScheme.outline
    val gridColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(start = 72.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            ) {
                val leftPad = 8.dp.toPx()
                val rightPad = 8.dp.toPx()
                val topPad = 12.dp.toPx()
                val bottomPad = 24.dp.toPx()
                val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val rows = 4
                (0..rows).forEach { row ->
                    val y = topPad + (chartHeight * row / rows)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                val columns = 5
                (0..columns).forEach { column ->
                    val x = leftPad + (chartWidth * column / columns)
                    drawLine(
                        color = gridColor,
                        start = Offset(x, topPad),
                        end = Offset(x, topPad + chartHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad + chartHeight),
                    end = Offset(leftPad + chartWidth, topPad + chartHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad, topPad),
                    end = Offset(leftPad, topPad + chartHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = Modifier.padding(top = 4.dp)) {
            repeat(reserveLegendRows) {
                Text(
                    text = " ",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun MetricOverTimeChart(
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    onShareClick: (() -> Unit)? = null
) {
    if (seriesList.isEmpty()) return
    val allPoints = seriesList.flatMap { it.points }
    if (allPoints.isEmpty()) return
    val allDates = allPoints.map { it.date }.distinct().sorted()
    if (allDates.isEmpty()) return
    val axisColor: Color = MaterialTheme.colorScheme.outline
    val gridColor: Color = MaterialTheme.colorScheme.outlineVariant
    val normalizedAxisColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val seriesColors = if (isDarkTheme) {
        listOf(
            MaterialTheme.colorScheme.primary,
            Color(0xFFD1B3FF),
            Color(0xFFFFB74D),
            Color(0xFF81C784),
            Color(0xFF4FC3F7),
            Color(0xFFE57373)
        )
    } else {
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            Color(0xFF2E7D32),
            Color(0xFFC62828),
            Color(0xFF1565C0)
        )
    }
    val spansMultipleYears = allDates.map { it.year }.distinct().size > 1
    val labelFormatter = if (spansMultipleYears) {
        DateTimeFormatter.ofPattern("MMM d, yyyy")
    } else {
        DateTimeFormatter.ofPattern("MMM d")
    }
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
        val normalized = ((value - range.min) / (range.max - range.min)).toFloat()
        return normalized.coerceIn(0f, 1f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(
                    start = 72.dp,
                    end = if (seriesList.size == 2) 72.dp else 8.dp,
                    top = 8.dp,
                    bottom = 8.dp
                )
        ) {
            val leftPad = 8.dp.toPx()
            val rightPad = 8.dp.toPx()
            val topPad = 12.dp.toPx()
            val bottomPad = 24.dp.toPx()

            val chartWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val gridRows = 4

            if (settings.backgroundStyle == ChartBackgroundStyle.HORIZONTAL_LINES ||
                settings.backgroundStyle == ChartBackgroundStyle.GRID
            ) {
                (0..gridRows).forEach { row ->
                    val y = topPad + (chartHeight * row / gridRows)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(leftPad + chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            if (settings.backgroundStyle == ChartBackgroundStyle.GRID) {
                val columns = max(1, allDates.size - 1)
                (0..columns).forEach { col ->
                    val x = leftPad + (chartWidth * col / columns)
                    drawLine(
                        color = gridColor,
                        start = Offset(x, topPad),
                        end = Offset(x, topPad + chartHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            drawLine(
                color = axisColor,
                start = Offset(leftPad, topPad + chartHeight),
                end = Offset(leftPad + chartWidth, topPad + chartHeight),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = axisColor,
                start = Offset(leftPad, topPad),
                end = Offset(leftPad, topPad + chartHeight),
                strokeWidth = 1.dp.toPx()
            )

            val xStep = if (allDates.size > 1) chartWidth / (allDates.size - 1) else 0f
            fun pointToOffset(point: HealthDataModel.MetricPoint, seriesIndex: Int): Offset {
                val index = dateIndex[point.date] ?: 0
                val x = leftPad + (xStep * index)
                val yNorm = normalized(point.value, rangeFor(seriesIndex))
                val y = topPad + chartHeight - (yNorm * chartHeight)
                return Offset(x, y)
            }

            if (settings.chartType == ChartType.LINE) {
                seriesList.forEachIndexed { seriesIndex, series ->
                    val lineColor = seriesColors[seriesIndex % seriesColors.size]
                    val coordinates = series.points.map { pointToOffset(it, seriesIndex) }
                    coordinates.zipWithNext().forEach { (start, end) ->
                        drawLine(
                            color = lineColor,
                            start = start,
                            end = end,
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    if (settings.showDataPoints) {
                        coordinates.forEach { point ->
                            drawCircle(
                                color = lineColor,
                                radius = 3.dp.toPx(),
                                center = point,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            } else {
                val slotWidth = chartWidth / allDates.size
                val barGroupWidth = slotWidth * 0.75f
                val barWidth = barGroupWidth / max(1, seriesList.size)
                val baselineY = topPad + chartHeight
                allDates.forEachIndexed { dateIndex, date ->
                    val groupLeft = leftPad + (slotWidth * dateIndex) + ((slotWidth - barGroupWidth) / 2f)
                    seriesList.forEachIndexed { seriesIndex, series ->
                        val value = series.points.firstOrNull { it.date == date }?.value ?: return@forEachIndexed
                        val yNorm = normalized(value, rangeFor(seriesIndex))
                        val barTop = topPad + chartHeight - (yNorm * chartHeight)
                        val left = groupLeft + (barWidth * seriesIndex)
                        drawRect(
                            color = seriesColors[seriesIndex % seriesColors.size],
                            topLeft = Offset(left, barTop),
                            size = Size(barWidth, baselineY - barTop),
                            style = Fill
                        )
                    }
                }
            }
        }

        when {
            seriesList.size == 1 -> AxisLabels(
                label = seriesList[0].label,
                unit = seriesList[0].unit,
                range = rangeFor(0),
                color = seriesColors[0],
                alignEnd = false,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            seriesList.size == 2 -> AxisLabels(
                label = seriesList[0].label,
                unit = seriesList[0].unit,
                range = rangeFor(0),
                color = seriesColors[0],
                alignEnd = false,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            else -> AxisLabels(
                label = stringResource(R.string.data_view_normalized),
                unit = null,
                range = ValueRange(0.0, 1.0),
                color = normalizedAxisColor,
                alignEnd = false,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        if (seriesList.size == 2) {
            AxisLabels(
                label = seriesList[1].label,
                unit = seriesList[1].unit,
                range = rangeFor(1),
                color = seriesColors[1],
                alignEnd = true,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(allDates.first().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
        Text(allDates.last().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        val summaryRows = buildList {
            seriesList.forEachIndexed { index, series ->
                val rowColor = seriesColors[index % seriesColors.size].copy(alpha = 0.9f)
                if (series.showMaxLabel) {
                    add(
                        stringResource(
                            R.string.data_view_max_format,
                            "\u25A0 ${series.label}",
                            formatAxisValue(series.peakValueInWindow),
                            series.unit?.let { " $it" } ?: ""
                        ) to rowColor
                    )
                }
                if (series.showMinLabel) {
                    add(
                        stringResource(
                            R.string.data_view_min_format,
                            "\u25A0 ${series.label}",
                            formatAxisValue(series.minValueInWindow),
                            series.unit?.let { " $it" } ?: ""
                        ) to rowColor
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            summaryRows.forEach { (text, color) ->
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (onShareClick != null) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier
                    .padding(start = 8.dp, top = 1.dp)
                    .testTag("data_view_graph_share_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.graph_share_button_content_description)
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

internal data class ValueRange(val min: Double, val max: Double)

internal fun seriesRangeFromPoints(
    points: List<HealthDataModel.MetricPoint>,
    yAxisMode: YAxisMode
): ValueRange {
    val rawMin = points.minOfOrNull { it.value } ?: 0.0
    val rawMax = points.maxOfOrNull { it.value } ?: 1.0
    val min = if (yAxisMode == YAxisMode.START_AT_ZERO) 0.0 else rawMin
    val max = max(min + 1.0, rawMax)
    return ValueRange(min = min, max = max)
}

@Composable
internal fun AxisLabels(
    label: String,
    unit: String?,
    range: ValueRange,
    color: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier
) {
    val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = modifier
            .width(72.dp)
            .height(220.dp)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            "${formatAxisValue(range.max)}${unitSuffix(unit)}",
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            label,
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "${formatAxisValue(range.min)}${unitSuffix(unit)}",
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
