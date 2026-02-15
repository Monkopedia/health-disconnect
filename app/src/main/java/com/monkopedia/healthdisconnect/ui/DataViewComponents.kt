package com.monkopedia.healthdisconnect.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
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
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.model.isConfigValid
import java.time.format.DateTimeFormatter
import kotlin.math.max
import com.monkopedia.healthdisconnect.formatAxisValue
import com.monkopedia.healthdisconnect.recordDetailsText
import com.monkopedia.healthdisconnect.recordPrimaryValueLabel
import com.monkopedia.healthdisconnect.recordTimestampLabel
import com.monkopedia.healthdisconnect.unitSuffix
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun LazyListScope.entriesSection(
    recordCount: Int?,
    onOpenEntries: () -> Unit
) {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenEntries() }
                .testTag("entries_row")
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.data_view_entries_count, recordCount ?: 0),
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.data_view_view_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (recordCount == null) {
            Text(
                text = stringResource(R.string.loading_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun EntriesRouteScreen(
    viewId: Int,
    onBack: () -> Unit,
    viewModel: DataViewAdapterViewModel = viewModel(),
    healthDataModel: HealthDataModel = viewModel(),
    initialSelectedEntry: Record? = null
) {
    val info by viewModel.dataViews.map { it?.dataViews?.get(viewId) }.collectAsState(initial = null)
    val view by viewModel.dataView(viewId).collectAsState(initial = null)
    if (view == null) {
        LoadingScreen()
        return
    }
    val data by healthDataModel.collectData(view!!).collectAsState(initial = null)
    var selectedEntryForDetails by remember(viewId, initialSelectedEntry) {
        mutableStateOf(initialSelectedEntry)
    }

    EntriesScreen(
        infoName = info?.name ?: "",
        data = data,
        onBack = onBack,
        onEntrySelected = { selectedEntryForDetails = it }
    )

    selectedEntryForDetails?.let { record ->
        val details = recordDetailsText(record)
        LaunchedEffect(record) {
            if (Log.isLoggable(ENTRY_DETAILS_LOG_TAG, Log.DEBUG)) {
                Log.d(ENTRY_DETAILS_LOG_TAG, details)
            }
        }
        AlertDialog(
            onDismissRequest = { selectedEntryForDetails = null },
            title = { Text(stringResource(R.string.data_view_entry_details_title)) },
            text = { Text(details, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(
                    onClick = { selectedEntryForDetails = null },
                    modifier = Modifier.testTag("entries_details_close")
                ) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }
}

@Composable
private fun EntriesScreen(
    infoName: String,
    data: List<Record>?,
    onBack: () -> Unit,
    onEntrySelected: (Record) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val copiedMessage = stringResource(R.string.data_view_entry_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 11.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("entries_back_button")
            ) {
                Text(stringResource(R.string.data_view_back))
            }
            Text(
                text = stringResource(R.string.data_view_entries_for, infoName),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (data == null) {
            LoadingScreen()
            return
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(data.size) { index ->
                val record = data[index]
                val timestamp = recordTimestampLabel(record)
                val valuePreview = recordPrimaryValueLabel(record)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entries_item_row_$index")
                        .clickable {
                            clipboardManager.setText(AnnotatedString(recordDetailsText(record)))
                            scope.launch {
                                snackbarHostState.showSnackbar(message = copiedMessage)
                            }
                            onEntrySelected(record)
                        }
                        .padding(vertical = 6.dp)
                    ,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = record::class.simpleName ?: record::class.qualifiedName ?: "Record",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (!timestamp.isNullOrBlank()) {
                            Text(
                                text = timestamp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!valuePreview.isNullOrBlank()) {
                        Text(
                            text = valuePreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }
}

fun LazyListScope.viewConfigurationSection(
    healthDataModel: HealthDataModel,
    isEditing: MutableState<Boolean>,
    chartSettings: ChartSettings,
    onChartSettingsChanged: (ChartSettings) -> Unit,
    timeWindowOptions: List<TimeWindow>,
    selectedSelections: List<RecordSelection>,
    onSelectedSelectionsChanged: (List<RecordSelection>) -> Unit,
    onResetEditStateToSaved: () -> Unit,
    onShowAddMetricDialog: () -> Unit,
    onShowDeleteViewConfirmation: () -> Unit,
    onReplaceMetric: (String) -> Unit
) {
    val selectedDisplay = selectedSelections.map { selection ->
        val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
        val label = cls?.let { PermissionsViewModel.RECORD_NAMES[it] }
            ?: cls?.simpleName
            ?: selection.fqn
        selection to label
    }
    fun metricDefaults(): MetricChartSettings = chartSettings.toMetricChartSettings()

    item {
        ToggleSection(
            labelText = stringResource(R.string.data_view_configuration),
            visibleState = isEditing,
            onToggle = { expanded ->
                if (!expanded) onResetEditStateToSaved()
            },
            headerTestTag = "data_view_configuration_header",
            content = {}
        )
    }
    if (!isEditing.value) return

    item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_chart_type),
            value = chartSettings.chartType
        ) { onChartSettingsChanged(chartSettings.copy(chartType = it)) }
    }
    item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_time_window),
            value = chartSettings.timeWindow,
            options = timeWindowOptions
        ) { onChartSettingsChanged(chartSettings.copy(timeWindow = it)) }
    }
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.data_view_label_show_data_points))
            Checkbox(
                checked = chartSettings.showDataPoints,
                modifier = Modifier.testTag("data_view_show_data_points_checkbox"),
                onCheckedChange = {
                    onChartSettingsChanged(chartSettings.copy(showDataPoints = it))
                }
            )
        }
    }
    item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_background),
            value = chartSettings.backgroundStyle
        ) { onChartSettingsChanged(chartSettings.copy(backgroundStyle = it)) }
        Spacer(Modifier.height(8.dp))
    }

    if (selectedDisplay.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.data_view_no_selected_metrics),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    items(selectedDisplay.size, key = { selectedDisplay[it].first.fqn }) { index ->
        val (selection, label) = selectedDisplay[index]
        val settings = selection.metricSettings ?: metricDefaults()
        val fqn = selection.fqn
        if (index > 0) {
            Spacer(Modifier.height(10.dp))
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReplaceMetric(fqn) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                    )
                    IconButton(
                        onClick = {
                            if (selectedSelections.size <= 1) {
                                onShowDeleteViewConfirmation()
                            } else {
                                onSelectedSelectionsChanged(
                                    selectedSelections.filterNot { it.fqn == fqn }
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.data_view_remove_metric),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                EnumCycleRow(
                    label = stringResource(R.string.data_view_label_aggregation),
                    value = settings.aggregation
                ) { newValue ->
                    onSelectedSelectionsChanged(
                        selectedSelections.map {
                            if (it.fqn == selection.fqn) {
                                it.copy(metricSettings = settings.copy(aggregation = newValue))
                            } else it
                        }
                    )
                }
                EnumCycleRow(
                    label = stringResource(R.string.data_view_label_bucket_size),
                    value = settings.bucketSize
                ) { newValue ->
                    onSelectedSelectionsChanged(
                        selectedSelections.map {
                            if (it.fqn == selection.fqn) {
                                it.copy(metricSettings = settings.copy(bucketSize = newValue))
                            } else it
                        }
                    )
                }
                EnumCycleRow(
                    label = stringResource(R.string.data_view_label_y_axis),
                    value = settings.yAxisMode
                ) { newValue ->
                    onSelectedSelectionsChanged(
                        selectedSelections.map {
                            if (it.fqn == selection.fqn) {
                                it.copy(metricSettings = settings.copy(yAxisMode = newValue))
                            } else it
                        }
                    )
                }
                EnumCycleRow(
                    label = stringResource(R.string.data_view_label_smoothing),
                    value = settings.smoothing
                ) { newValue ->
                    onSelectedSelectionsChanged(
                        selectedSelections.map {
                            if (it.fqn == selection.fqn) {
                                it.copy(metricSettings = settings.copy(smoothing = newValue))
                            } else it
                        }
                    )
                }
                EnumCycleRow(
                    label = stringResource(R.string.data_view_label_units),
                    value = settings.unitPreference
                ) { newValue ->
                    onSelectedSelectionsChanged(
                        selectedSelections.map {
                            if (it.fqn == selection.fqn) {
                                it.copy(metricSettings = settings.copy(unitPreference = newValue))
                            } else it
                        }
                    )
                }
            }
        }
    }

    item {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowAddMetricDialog() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.data_view_add_metric),
                modifier = Modifier.fillMaxWidth(0.85f)
            )
            IconButton(onClick = { onShowAddMetricDialog() }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.data_view_add_metric),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

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
    val isDarkTheme = isSystemInDarkTheme()
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
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(allDates.first().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
        Text(allDates.last().format(labelFormatter), style = MaterialTheme.typography.labelSmall)
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
    Column(modifier = Modifier.padding(top = 6.dp)) {
        seriesList.forEachIndexed { index, series ->
            Text(
                stringResource(
                    R.string.data_view_peak_format,
                    "\u25A0 ${series.label}",
                    formatAxisValue(series.peakValueInWindow),
                    series.unit?.let { " $it" } ?: ""
                ),
                color = seriesColors[index % seriesColors.size].copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Spacer(Modifier.height(14.dp))
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

internal fun ChartSettings.toMetricChartSettings(): MetricChartSettings {
    return MetricChartSettings(
        aggregation = aggregation,
        timeWindow = timeWindow,
        bucketSize = bucketSize,
        yAxisMode = yAxisMode,
        smoothing = smoothing,
        unitPreference = unitPreference
    )
}

internal fun RecordSelection.withDefaultSettings(chartSettings: ChartSettings): RecordSelection {
    return if (metricSettings != null) this else copy(metricSettings = chartSettings.toMetricChartSettings())
}

@Composable
private inline fun <reified T : Enum<T>> EnumCycleRow(
    label: String,
    value: T,
    options: List<T> = enumValues<T>().toList(),
    crossinline onValueChanged: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedValue = if (value in options) value else options.first()
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
                Text(selectedValue.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercaseChar() })
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

internal fun SectionHeading(title: String) {
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
    onToggle: ((Boolean) -> Unit)? = null,
    collapsible: Boolean = true,
    showArrow: Boolean = true,
    headerTestTag: String? = null,
    content: @Composable () -> Unit
) {
    val arrowRotation = remember { Animatable(if (visibleState.value) 180f else 0f) }
    var targetRotation by remember { mutableFloatStateOf(if (visibleState.value) 180f else 0f) }
    var lastVisibleState by remember { mutableStateOf(visibleState.value) }
    LaunchedEffect(visibleState.value) {
        if (visibleState.value != lastVisibleState) {
            targetRotation += 180f
            if (targetRotation > 360f) {
                targetRotation -= 360f
                arrowRotation.snapTo((arrowRotation.value - 360f).coerceAtLeast(0f))
            }
            lastVisibleState = visibleState.value
        }
    }
    LaunchedEffect(targetRotation) {
        arrowRotation.animateTo(
            targetValue = targetRotation,
            animationSpec = tween(durationMillis = 180)
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = (
                if (headerTestTag != null) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = collapsible,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (collapsible) {
                                visibleState.value = !visibleState.value
                                onToggle?.invoke(visibleState.value)
                            }
                        }
                        .padding(vertical = 4.dp)
                        .testTag(headerTestTag)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = collapsible,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (collapsible) {
                                visibleState.value = !visibleState.value
                                onToggle?.invoke(visibleState.value)
                            }
                        }
                        .padding(vertical = 4.dp)
                }
            ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(labelText, style = MaterialTheme.typography.titleMedium)
            if (showArrow) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer { rotationZ = arrowRotation.value },
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
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
