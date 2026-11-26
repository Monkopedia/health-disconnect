package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.GraphShareTheme
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.chartSeriesColors
import com.monkopedia.healthdisconnect.formatValueWithUnit
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.YAxisMode
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
    selectedSlot: Int?,
    onSelectedSlotChange: (Int?) -> Unit,
    onOpenEntries: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    /** The range this chart draws, when it is one range of a view rather than its own window. */
    window: ChartRange? = null,
    /** Opens a selected band bucket drawn on its own, with finer buckets. */
    onOpenRange: ((ChartRange) -> Unit)? = null
) {
    if (seriesList.isEmpty()) return
    val geometry = ChartGeometry.create(
        seriesList = seriesList,
        settings = settings,
        xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
        now = LocalClock.current.instant(),
        window = window
    )
    val allDates = geometry.sortedDates
    val axisColor: Color = MaterialTheme.colorScheme.outline
    val gridColor: Color = MaterialTheme.colorScheme.outlineVariant
    val normalizedAxisColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val chartTheme = if (isDarkTheme) GraphShareTheme.DARK else GraphShareTheme.LIGHT
    val seriesColors = chartSeriesColors(
        theme = chartTheme,
        primaryColor = MaterialTheme.colorScheme.primary.toArgb(),
        secondaryColor = MaterialTheme.colorScheme.secondary.toArgb()
    ).map { Color(it) }
    // Line charts plot on a continuous time axis spanning the view's time window, so a single
    // bucket still sits within a real range (different endpoints on each end) and a lone point is
    // positioned correctly; intraday windows show time-of-day labels. Bar charts keep their
    // discrete per-bucket slots and label the actual data range. Both formats come from
    // ChartGeometry so the axis labelling is defined once for every renderer.
    val (labelStart, labelEnd) = if (settings.chartType == ChartType.LINE) {
        geometry.axisWindowLabels()
    } else {
        geometry.dataRangeLabels()
    }

    fun rangeFor(index: Int): ValueRange = geometry.rangeFor(index)

    // A slot from before the data changed may no longer exist.
    val activeSlot = selectedSlot?.takeIf { it < geometry.slotCount }
    val guideColor = MaterialTheme.colorScheme.onSurface
    // The gesture detectors outlive recompositions (restarting them would cancel a drag mid-way),
    // so they read the latest geometry/selection through these rather than capturing them.
    val currentGeometry by rememberUpdatedState(geometry)
    val currentSlot by rememberUpdatedState(activeSlot)
    val currentOnSelect by rememberUpdatedState(onSelectedSlotChange)
    fun PointerInputScope.slotAt(x: Float): Int? {
        val plotPad = 8.dp.toPx()
        val plotWidth = (size.width - 2 * plotPad).coerceAtLeast(1f)
        return currentGeometry.nearestSlot((x - plotPad) / plotWidth)
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
                .testTag("data_view_chart_plot")
                .pointerInput(Unit) {
                    // Tapping the selected bucket again clears it.
                    detectTapGestures { offset ->
                        val slot = slotAt(offset.x)
                        currentOnSelect(if (slot == currentSlot) null else slot)
                    }
                }
                // Dragging scrubs only once something is selected. While idle a horizontal swipe
                // that starts on the chart still pages between views; vertical drags always go to
                // the screen's scroll, because a vertical slop consumed by the list cancels this.
                .pointerInput(activeSlot != null) {
                    if (activeSlot == null) return@pointerInput
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        currentOnSelect(slotAt(change.position.x))
                    }
                }
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

            // A lone point is centered horizontally (and the symmetric value range centers
            // it vertically) so it sits in the middle of the chart instead of at an edge.
            fun pointToOffset(point: HealthDataModel.MetricPoint, seriesIndex: Int): Offset {
                val fraction = geometry.xFraction(point.instant)
                val x = leftPad + (chartWidth * fraction)
                val yNorm = geometry.normalized(point.value, seriesIndex)
                val y = topPad + chartHeight - (yNorm * chartHeight)
                return Offset(x, y)
            }

            fun bandEdgeToOffsets(edge: ChartGeometry.BandEdge): Pair<Offset, Offset> {
                val x = leftPad + (chartWidth * edge.x)
                val minY = topPad + (edge.minYFractionFromTop * chartHeight)
                val maxY = topPad + (edge.maxYFractionFromTop * chartHeight)
                return Offset(x, minY) to Offset(x, maxY)
            }

            if (settings.chartType == ChartType.LINE) {
                seriesList.forEachIndexed { seriesIndex, series ->
                    val lineColor = seriesColors[seriesIndex % seriesColors.size]
                    geometry.bandFor(seriesIndex)?.let { band ->
                        drawMetricBand(
                            band = band,
                            color = lineColor,
                            rangeDisplay = settings.rangeDisplay,
                            bandEdgeToOffsets = ::bandEdgeToOffsets,
                            strokeWidthPx = 1.5.dp.toPx()
                        )
                    }
                    val coordinates = series.points.map { pointToOffset(it, seriesIndex) }
                    coordinates.zipWithNext().forEach { (start, end) ->
                        drawLine(
                            color = lineColor,
                            start = start,
                            end = end,
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    // A lone point has no segment to draw, so always render it as a dot;
                    // otherwise honor the "show data points" setting.
                    if (settings.showDataPoints || coordinates.size == 1) {
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
                if (activeSlot != null) {
                    val x = leftPad + chartWidth * geometry.slotXFraction(activeSlot)
                    drawLine(
                        color = guideColor,
                        start = Offset(x, topPad),
                        end = Offset(x, topPad + chartHeight),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    )
                    seriesList.forEachIndexed { seriesIndex, series ->
                        val index = geometry.pointIndexAt(seriesIndex, activeSlot) ?: return@forEachIndexed
                        val color = seriesColors[seriesIndex % seriesColors.size]
                        // A band marks its max and min with hollow dots around the filled avg dot.
                        listOfNotNull(series.bandMax, series.bandMin).forEach { edge ->
                            val center = pointToOffset(edge[index], seriesIndex)
                            drawCircle(Color.White, radius = 4.dp.toPx(), center = center)
                            drawCircle(color, radius = 4.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                        }
                        val center = pointToOffset(series.points[index], seriesIndex)
                        drawCircle(Color.White, radius = 7.5.dp.toPx(), center = center)
                        drawCircle(color, radius = 5.dp.toPx(), center = center)
                    }
                }
            } else {
                val slotWidth = chartWidth / allDates.size
                val barGroupWidth = slotWidth * 0.75f
                val barWidth = barGroupWidth / max(1, seriesList.size)
                val baselineY = topPad + chartHeight
                allDates.forEachIndexed { dateIndex, date ->
                    val groupLeft = leftPad + (slotWidth * dateIndex) + ((slotWidth - barGroupWidth) / 2f)
                    // A selection keeps its bars at full colour and dims the rest.
                    val alpha = if (activeSlot == null || activeSlot == dateIndex) 1f else DIMMED_BAR_ALPHA
                    var groupTop = baselineY
                    seriesList.forEachIndexed { seriesIndex, series ->
                        val value = series.points.firstOrNull { it.date == date }?.value ?: return@forEachIndexed
                        val yNorm = geometry.normalized(value, seriesIndex)
                        val barTop = topPad + chartHeight - (yNorm * chartHeight)
                        val left = groupLeft + (barWidth * seriesIndex)
                        groupTop = minOf(groupTop, barTop)
                        drawRect(
                            color = seriesColors[seriesIndex % seriesColors.size].copy(alpha = alpha),
                            topLeft = Offset(left, barTop),
                            size = Size(barWidth, baselineY - barTop),
                            style = Fill
                        )
                    }
                    if (activeSlot == dateIndex) {
                        val outline = 2.dp.toPx()
                        drawRect(
                            color = guideColor,
                            topLeft = Offset(groupLeft - outline, groupTop - outline),
                            size = Size(barGroupWidth + 2 * outline, baselineY - groupTop + outline),
                            style = Stroke(outline)
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
        Text(labelStart, style = MaterialTheme.typography.labelSmall)
        Text(labelEnd, style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChartReadoutPanel(
            geometry = geometry,
            seriesColors = seriesColors,
            selectedSlot = activeSlot,
            onClear = { onSelectedSlotChange(null) },
            onOpenEntries = onOpenEntries,
            modifier = Modifier.weight(1f),
            onOpenRange = onOpenRange
        )
        if (onShareClick != null) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier
                    .padding(start = 4.dp)
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

/** Alpha of the unselected bars while one bar is selected. */
private const val DIMMED_BAR_ALPHA = 0.35f

/** Alpha applied to a series color for the filled BAND envelope. */
private const val BAND_FILL_ALPHA = 0.16f

/** Alpha applied to a series color for the LINES min/max dashed strokes. */
private const val BAND_LINE_ALPHA = 0.55f

/**
 * Draws a min/max/avg series' envelope for the on-screen chart. BAND fills the min–max area at low
 * alpha; LINES draws lighter dashed min and max strokes. The avg line is drawn separately by the
 * caller. Geometry (the [band] edges) comes from [ChartGeometry] so every renderer draws the same
 * shape.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMetricBand(
    band: ChartGeometry.BandGeometry,
    color: Color,
    rangeDisplay: com.monkopedia.healthdisconnect.model.RangeDisplay,
    bandEdgeToOffsets: (ChartGeometry.BandEdge) -> Pair<Offset, Offset>,
    strokeWidthPx: Float
) {
    val edges = band.edges
    if (edges.isEmpty()) return
    val bounds = edges.map(bandEdgeToOffsets)
    when (rangeDisplay) {
        com.monkopedia.healthdisconnect.model.RangeDisplay.BAND -> {
            val path = androidx.compose.ui.graphics.Path()
            bounds.forEachIndexed { index, (_, maxOffset) ->
                if (index == 0) path.moveTo(maxOffset.x, maxOffset.y) else path.lineTo(maxOffset.x, maxOffset.y)
            }
            bounds.asReversed().forEach { (minOffset, _) -> path.lineTo(minOffset.x, minOffset.y) }
            path.close()
            drawPath(path, color = color.copy(alpha = BAND_FILL_ALPHA), style = Fill)
        }
        com.monkopedia.healthdisconnect.model.RangeDisplay.LINES -> {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(strokeWidthPx * 4f, strokeWidthPx * 3f)
            )
            val edgeColor = color.copy(alpha = BAND_LINE_ALPHA)
            listOf(
                bounds.map { it.first },
                bounds.map { it.second }
            ).forEach { line ->
                line.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = edgeColor,
                        start = start,
                        end = end,
                        strokeWidth = strokeWidthPx,
                        pathEffect = dash
                    )
                }
            }
        }
    }
}

internal data class ValueRange(val min: Double, val max: Double)

/**
 * The points that define a series' Y-range. For a min/max/avg series this is the min–max envelope
 * (so the shared range spans the whole band); otherwise it's just the plotted points.
 */
internal fun HealthDataModel.MetricSeries.rangePoints(): List<HealthDataModel.MetricPoint> {
    val min = bandMin
    val max = bandMax
    return if (min != null && max != null) points + min + max else points
}

internal fun seriesRangeFromPoints(
    points: List<HealthDataModel.MetricPoint>,
    yAxisMode: YAxisMode
): ValueRange {
    val rawMin = points.minOfOrNull { it.value } ?: 0.0
    val rawMax = points.maxOfOrNull { it.value } ?: 1.0
    if (rawMin == rawMax) {
        // A single distinct value: center it with a symmetric band so a lone point sits
        // in the middle of the chart vertically rather than pinned to the top or bottom.
        val pad = max(1.0, kotlin.math.abs(rawMin) * 0.1)
        return ValueRange(min = rawMin - pad, max = rawMin + pad)
    }
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
            formatValueWithUnit(range.max, unit),
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
            formatValueWithUnit(range.min, unit),
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
