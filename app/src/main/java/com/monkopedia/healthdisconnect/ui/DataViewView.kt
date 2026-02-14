package com.monkopedia.healthdisconnect.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.model.isConfigValid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

private const val ENTRY_DETAILS_LOG_TAG = "HealthDisconnectEntry"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataViewView(
    viewModel: DataViewAdapterViewModel,
    page: Int,
    healthDataModel: HealthDataModel = viewModel()
) {
    val info by viewModel.dataViews.map { it?.dataViews?.get(it.ordering[page]) }
        .collectAsState(initial = null)
    if (info == null) return
    val view by viewModel.dataView(info!!.id).collectAsState(initial = null)
    if (view == null) return
    var refreshTick by rememberSaveable(view!!.id) { mutableStateOf(0) }
    var isRefreshing by rememberSaveable(view!!.id) { mutableStateOf(false) }
    val data by healthDataModel.collectData(view!!, refreshTick).collectAsState(initial = null)
    val metricSeriesList = if (data == null) {
        null
    } else {
        healthDataModel.aggregateMetricSeriesList(view!!, data!!)
    }
    val isShowingEntries = rememberSaveable(view!!.id) { mutableStateOf(view!!.alwaysShowEntries) }
    val isShowingChart = rememberSaveable(view!!.id) { mutableStateOf(true) }
    var chartSettings by remember(view!!.id, view!!.chartSettings) {
        mutableStateOf(view!!.chartSettings)
    }
    val isEditing =
        rememberSaveable(view!!.id) { mutableStateOf(!info.isConfigValid || !view.isConfigValid) }
    var selectedEntryForDetails by remember(view!!.id) { mutableStateOf<Record?>(null) }

    val refreshScope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            refreshScope.launch {
                isRefreshing = true
                healthDataModel.refreshMetricsWithData()
                refreshTick += 1
                isRefreshing = false
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
        Text(
            text = info!!.name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(16.dp))

        if (view!!.type == ViewType.CHART) {
            val dayCount = metricSeriesList?.flatMap { it.points }?.map { it.date }?.distinct()?.size ?: 0
            ToggleSection(stringResource(R.string.data_view_graph_days, dayCount), isShowingChart) {
                if (data == null) {
                    LoadingScreen()
                } else if (metricSeriesList.isNullOrEmpty()) {
                    val hasAnyEntries = data!!.isNotEmpty()
                    Text(
                        stringResource(
                            if (hasAnyEntries) {
                                R.string.data_view_no_graphable_with_hint
                            } else {
                                R.string.data_view_no_graphable
                            }
                        )
                    )
                } else {
                    if (view!!.records.size > HealthDataModel.MAX_CHART_SERIES) {
                        Text(
                            stringResource(
                                R.string.data_view_showing_first_metrics,
                                HealthDataModel.MAX_CHART_SERIES
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    MetricOverTimeChart(metricSeriesList, chartSettings)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        ToggleSection(stringResource(R.string.data_view_entries_count, data?.size ?: 0), isShowingEntries) {
            if (data == null) {
                LoadingScreen()
            } else {
                val list = data!!
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 260.dp)
                ) {
                    items(list.size) { index ->
                        val r: Record = list[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedEntryForDetails = r }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(r::class.simpleName ?: r::class.qualifiedName ?: "Record")
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleSection(stringResource(R.string.data_view_configuration), isEditing) {
            val available = healthDataModel.metricsWithData.collectAsState(null).value
            val all = (available ?: PermissionsViewModel.CLASSES).toList().sortedBy {
                PermissionsViewModel.RECORD_NAMES[it] ?: (it.simpleName ?: it.qualifiedName ?: "")
            }
            val selected =
                rememberSaveable(view!!.id) { mutableStateOf(view!!.records.map { it.fqn }) }
            val scope = rememberCoroutineScope()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.data_view_open_entries_default))
                Checkbox(checked = isShowingEntries.value, onCheckedChange = {
                    isShowingEntries.value =
                        it
                })
            }
            Spacer(Modifier.height(8.dp))
            SectionHeading(stringResource(R.string.data_view_graph_display_header))
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_aggregation),
                value = chartSettings.aggregation
            ) { chartSettings = chartSettings.copy(aggregation = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_time_window),
                value = chartSettings.timeWindow
            ) { chartSettings = chartSettings.copy(timeWindow = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_bucket_size),
                value = chartSettings.bucketSize
            ) { chartSettings = chartSettings.copy(bucketSize = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_chart_type),
                value = chartSettings.chartType
            ) { chartSettings = chartSettings.copy(chartType = it) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.data_view_label_show_data_points))
                Checkbox(
                    checked = chartSettings.showDataPoints,
                    onCheckedChange = { chartSettings = chartSettings.copy(showDataPoints = it) }
                )
            }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_y_axis),
                value = chartSettings.yAxisMode
            ) { chartSettings = chartSettings.copy(yAxisMode = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_smoothing),
                value = chartSettings.smoothing
            ) { chartSettings = chartSettings.copy(smoothing = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_units),
                value = chartSettings.unitPreference
            ) { chartSettings = chartSettings.copy(unitPreference = it) }
            EnumCycleRow(
                label = stringResource(R.string.data_view_label_background),
                value = chartSettings.backgroundStyle
            ) { chartSettings = chartSettings.copy(backgroundStyle = it) }
            Spacer(Modifier.height(8.dp))
            SectionHeading(stringResource(R.string.data_view_record_types_header))
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)) {
                items(all.size) { index ->
                    val cls = all[index]
                    val fqn = cls.qualifiedName ?: return@items
                    val label = PermissionsViewModel.RECORD_NAMES[cls] ?: (cls.simpleName ?: fqn)
                    val checked = selected.value.contains(fqn)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val list = selected.value.toMutableList()
                                if (checked) list.remove(fqn) else list.add(fqn)
                                selected.value = list
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label)
                        Checkbox(checked = checked, onCheckedChange = {
                            val list = selected.value.toMutableList()
                            if (it) list.add(fqn) else list.remove(fqn)
                            selected.value = list
                        })
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val newView = DataView(
                        id = view!!.id,
                        type = view!!.type,
                        records = selected.value.distinct().map { RecordSelection(it) },
                        alwaysShowEntries = isShowingEntries.value,
                        chartSettings = chartSettings
                    )
                    scope.launch { viewModel.updateView(newView) }
                    isEditing.value = false
                }) {
                    Text(stringResource(R.string.data_view_save))
                }
                TextButton(onClick = { isEditing.value = false }) {
                    Text(stringResource(R.string.data_view_cancel))
                }
            }
        }
    }
    }

    selectedEntryForDetails?.let { record ->
        val details = recordDetailsText(record)
        LaunchedEffect(record) {
            Log.d(ENTRY_DETAILS_LOG_TAG, details)
        }
        AlertDialog(
            onDismissRequest = { selectedEntryForDetails = null },
            title = { Text(stringResource(R.string.data_view_entry_details_title)) },
            text = {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedEntryForDetails = null }) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }
}

@Composable
private fun MetricOverTimeChart(
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings
) {
    if (seriesList.isEmpty()) return
    val allPoints = seriesList.flatMap { it.points }
    if (allPoints.isEmpty()) return
    val allDates = allPoints.map { it.date }.distinct().sorted()
    if (allDates.isEmpty()) return
    val axisColor: Color = MaterialTheme.colorScheme.outline
    val gridColor: Color = MaterialTheme.colorScheme.outlineVariant
    val normalizedAxisColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val seriesColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        Color(0xFF2E7D32),
        Color(0xFFC62828),
        Color(0xFF1565C0)
    )
    val labelFormatter = DateTimeFormatter.ofPattern("MMM d")
    val dateIndex = allDates.withIndex().associate { it.value to it.index }
    val useSeparateNormalization = seriesList.size > 1
    val globalRange = seriesRangeFromPoints(allPoints, settings.yAxisMode)
    val perSeriesRanges = seriesList.map { series ->
        seriesRangeFromPoints(series.points, settings.yAxisMode)
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
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(allDates.first().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
        Text(allDates.last().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
    }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        seriesList.forEachIndexed { index, series ->
            val seriesPeak = series.points.maxOfOrNull { it.value } ?: 0.0
            Text(
                stringResource(
                    R.string.data_view_peak_format,
                    "\u25A0 ${series.label}",
                    seriesPeak.roundToInt(),
                    series.unit?.let { " $it" } ?: ""
                ),
                color = seriesColors[index % seriesColors.size],
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private data class ValueRange(val min: Double, val max: Double)

private fun seriesRangeFromPoints(
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
private fun AxisLabels(
    label: String,
    unit: String?,
    range: ValueRange,
    color: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier
) {
    val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = Modifier
            .then(modifier)
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

private fun formatAxisValue(value: Double): String {
    return if (abs(value) < 10) {
        String.format("%.1f", value)
    } else {
        value.roundToInt().toString()
    }
}

private fun unitSuffix(unit: String?): String {
    return if (unit.isNullOrBlank()) "" else " $unit"
}

private fun recordDetailsText(record: Record): String {
    val lines = mutableListOf<String>()
    lines += "Type: ${record::class.qualifiedName ?: record::class.simpleName ?: "Record"}"
    val methodLines = record.javaClass.methods
        .asSequence()
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
        .sortedBy { it.name }
        .mapNotNull { method ->
            val value = runCatching { method.invoke(record) }.getOrNull() ?: return@mapNotNull null
            "${method.name.removePrefix("get")}: $value"
        }
        .take(40)
        .toList()
    if (methodLines.isNotEmpty()) {
        lines += ""
        lines += methodLines
    }
    return lines.joinToString("\n")
}

@Composable
private inline fun <reified T : Enum<T>> EnumCycleRow(
    label: String,
    value: T,
    crossinline onValueChanged: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = enumValues<T>()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(value.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercaseChar() })
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.name.replace('_', ' ').lowercase()
                                    .replaceFirstChar { it.uppercaseChar() }
                            )
                        },
                        onClick = {
                            onValueChanged(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun ToggleSection(
    labelText: String,
    visibleState: MutableState<Boolean>,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (visibleState.value) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "toggle_arrow_rotation"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { visibleState.value = !visibleState.value }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(labelText, style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        AnimatedVisibility(
            visible = visibleState.value,
            enter = fadeIn(animationSpec = tween(280)) +
                expandVertically(
                    expandFrom = Alignment.Top,
                    initialHeight = { (it * 0.88f).toInt() },
                    animationSpec = tween(230)
                ),
            exit = fadeOut(animationSpec = tween(220)) +
                shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    targetHeight = { (it * 0.88f).toInt() },
                    animationSpec = tween(180)
                )
        ) {
            Column {
                content()
            }
        }
    }
}
