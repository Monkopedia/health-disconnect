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
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import org.koin.androidx.compose.koinViewModel
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
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
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

fun viewConfigurationSection(
    scope: LazyListScope,
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
    onReplaceMetric: (String) -> Unit,
    showWidgetUpdateWindowControl: Boolean = false
) {
    val selectedDisplay = selectedSelections.map { selection ->
        val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
        val label = cls?.let { PermissionsViewModel.RECORD_NAMES[it] }
            ?: cls?.simpleName
            ?: selection.fqn
        selection to label
    }
    fun metricDefaults(): MetricChartSettings = chartSettings.toMetricChartSettings()

    scope.item {
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

    scope.item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_chart_type),
            value = chartSettings.chartType
        ) { onChartSettingsChanged(chartSettings.copy(chartType = it)) }
    }
    scope.item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_time_window),
            value = chartSettings.timeWindow,
            options = timeWindowOptions
        ) { onChartSettingsChanged(chartSettings.copy(timeWindow = it)) }
    }
    scope.item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onChartSettingsChanged(
                        chartSettings.copy(showDataPoints = !chartSettings.showDataPoints)
                    )
                }
                .testTag("data_view_show_data_points_row"),
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
    scope.item {
        EnumCycleRow(
            label = stringResource(R.string.data_view_label_background),
            value = chartSettings.backgroundStyle
        ) { onChartSettingsChanged(chartSettings.copy(backgroundStyle = it)) }
        Spacer(Modifier.height(8.dp))
    }
    if (showWidgetUpdateWindowControl) {
        scope.item {
            EnumCycleRow(
                label = stringResource(R.string.widget_update_window_label),
                value = chartSettings.widgetUpdateWindow,
                options = WidgetUpdateWindow.values().toList(),
                testTag = "data_view_widget_update_window_value"
            ) { newValue ->
                onChartSettingsChanged(chartSettings.copy(widgetUpdateWindow = newValue))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (selectedDisplay.isEmpty()) {
        scope.item {
            Text(
                text = stringResource(R.string.data_view_no_selected_metrics),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    scope.items(selectedDisplay.size, key = { selectedDisplay[it].first.fqn }) { index ->
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectedSelectionsChanged(
                                selectedSelections.map {
                                    if (it.fqn == selection.fqn) {
                                        val currentSettings = it.metricSettings ?: metricDefaults()
                                        it.copy(
                                            metricSettings = currentSettings.copy(
                                                showMaxLabel = !settings.showMaxLabel
                                            )
                                        )
                                    } else it
                                }
                            )
                        }
                        .testTag("data_view_metric_show_max_row_$index"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.data_view_label_show_max))
                    Checkbox(
                        checked = settings.showMaxLabel,
                        modifier = Modifier.testTag("data_view_metric_show_max_checkbox_$index"),
                        onCheckedChange = { checked ->
                            onSelectedSelectionsChanged(
                                selectedSelections.map {
                                    if (it.fqn == selection.fqn) {
                                        val currentSettings = it.metricSettings ?: metricDefaults()
                                        it.copy(metricSettings = currentSettings.copy(showMaxLabel = checked))
                                    } else it
                                }
                            )
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectedSelectionsChanged(
                                selectedSelections.map {
                                    if (it.fqn == selection.fqn) {
                                        val currentSettings = it.metricSettings ?: metricDefaults()
                                        it.copy(
                                            metricSettings = currentSettings.copy(
                                                showMinLabel = !settings.showMinLabel
                                            )
                                        )
                                    } else it
                                }
                            )
                        }
                        .testTag("data_view_metric_show_min_row_$index"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.data_view_label_show_min))
                    Checkbox(
                        checked = settings.showMinLabel,
                        modifier = Modifier.testTag("data_view_metric_show_min_checkbox_$index"),
                        onCheckedChange = { checked ->
                            onSelectedSelectionsChanged(
                                selectedSelections.map {
                                    if (it.fqn == selection.fqn) {
                                        val currentSettings = it.metricSettings ?: metricDefaults()
                                        it.copy(metricSettings = currentSettings.copy(showMinLabel = checked))
                                    } else it
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    scope.item {
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

internal fun ChartSettings.toMetricChartSettings(): MetricChartSettings {
    return MetricChartSettings(
        aggregation = aggregation,
        timeWindow = timeWindow,
        bucketSize = bucketSize,
        yAxisMode = yAxisMode,
        smoothing = smoothing,
        unitPreference = unitPreference,
        showMaxLabel = true,
        showMinLabel = false
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
    testTag: String? = null,
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
            TextButton(
                onClick = { expanded = true },
                modifier = if (testTag != null) {
                    Modifier.testTag(testTag)
                } else {
                    Modifier
                }
            ) {
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
    var isArrowAnimating by remember { mutableStateOf(false) }
    var queuedToggleParity by remember { mutableStateOf(false) }

    fun requestToggle() {
        if (!collapsible) return
        if (isArrowAnimating) {
            queuedToggleParity = !queuedToggleParity
            return
        }
        visibleState.value = !visibleState.value
        onToggle?.invoke(visibleState.value)
    }

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
        isArrowAnimating = true
        arrowRotation.animateTo(
            targetValue = targetRotation,
            animationSpec = tween(durationMillis = 180)
        )
        isArrowAnimating = false
        if (queuedToggleParity) {
            queuedToggleParity = false
            visibleState.value = !visibleState.value
            onToggle?.invoke(visibleState.value)
        }
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
                            requestToggle()
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
                            requestToggle()
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
