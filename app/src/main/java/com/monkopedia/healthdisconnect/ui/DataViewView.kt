package com.monkopedia.healthdisconnect.ui

import android.util.Log
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.GraphSharePreferences
import com.monkopedia.healthdisconnect.GraphShareTheme
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.graphShareDataStore
import com.monkopedia.healthdisconnect.incrementGraphShareDialogCount
import com.monkopedia.healthdisconnect.graphShareContentHeight
import com.monkopedia.healthdisconnect.renderGraphBitmap
import com.monkopedia.healthdisconnect.shareGraphImage
import com.monkopedia.healthdisconnect.toGraphSharePreferences
import com.monkopedia.healthdisconnect.updateGraphSharePreferences
import com.monkopedia.healthdisconnect.writeGraphSharePng
import org.koin.androidx.compose.koinViewModel
import com.monkopedia.healthdisconnect.recordDetailsText
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.model.isConfigValid
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

internal const val ENTRY_DETAILS_LOG_TAG = "HealthDisconnectEntry"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataViewView(
    viewModel: DataViewAdapterViewModel,
    page: Int,
    healthDataModel: HealthDataModel = koinViewModel(),
    permissionsViewModel: PermissionsViewModel = koinViewModel(),
    headerPageOffset: Float = 0f,
    showHeader: Boolean = true,
    onOpenEntriesRequested: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val info by viewModel.dataViews.map { it?.dataViews?.get(it.ordering[page]) }
        .collectAsState(initial = null)
    if (info == null) return
    val view by viewModel.dataView(info!!.id).collectAsState(initial = null)
    if (view == null) return
    val graphSharePreferences by context.graphShareDataStore.data
        .map { prefs -> prefs.toGraphSharePreferences() }
        .collectAsState(initial = GraphSharePreferences())
    val grantedPermissions by permissionsViewModel.grantedPermissions.collectAsState(initial = emptySet())
    val hasHistoryPermission = grantedPermissions.contains(PermissionsViewModel.HISTORY_PERMISSION)
    val timeWindowOptions = if (hasHistoryPermission) {
        TimeWindow.entries
    } else {
        listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30)
    }
    var refreshTick by rememberSaveable(view!!.id) { mutableStateOf(0) }
    var isRefreshing by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var lastRefreshedMillis by rememberSaveable(view!!.id) { mutableStateOf<Long?>(null) }
    var chartSettings by remember(view!!.id, view!!.chartSettings) {
        mutableStateOf(view!!.chartSettings)
    }
    var pendingTimeWindowRestore by rememberSaveable(view!!.id) { mutableStateOf<TimeWindow?>(null) }
    var autoClampedTimeWindow by rememberSaveable(view!!.id) { mutableStateOf<TimeWindow?>(null) }
    var selectedSelections by remember(view!!.id, view!!.records, view!!.chartSettings) {
        mutableStateOf(
            view!!.records.map { selection ->
                selection.withDefaultSettings(view!!.chartSettings)
            }
        )
    }
    val recordCountFlow = remember(
        view!!.id,
        view!!.chartSettings,
        view!!.records,
        refreshTick
    ) { healthDataModel.collectRecordCount(view!!, refreshTick) }
    val metricSeriesFlow = remember(
        view!!.id,
        view!!.chartSettings,
        view!!.records,
        refreshTick
    ) { healthDataModel.collectAggregatedSeries(view!!, refreshTick) }
    val recordCount by recordCountFlow.collectAsState(initial = null)
    val metricSeriesList by metricSeriesFlow.collectAsState(initial = null)
    val isShowingChart = rememberSaveable(view!!.id) { mutableStateOf(true) }
    val isEditing =
        rememberSaveable(view!!.id) { mutableStateOf(!info.isConfigValid || !view.isConfigValid) }
    var selectedEntryForDetails by remember(view!!.id) { mutableStateOf<Record?>(null) }
    var showAddMetricDialog by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var showDeleteViewConfirmation by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var replaceMetricTargetFqn by rememberSaveable(view!!.id) { mutableStateOf<String?>(null) }
    var showGraphThemeDialog by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var showGraphWarningDialog by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var selectedGraphShareTheme by rememberSaveable(view!!.id) {
        mutableStateOf(graphSharePreferences.selectedTheme)
    }
    var graphShareDoNotShowAgainChecked by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var isGraphShareInProgress by rememberSaveable(view!!.id) { mutableStateOf(false) }
    var graphShareError by rememberSaveable(view!!.id) { mutableStateOf<String?>(null) }
    val actionScope = rememberCoroutineScope()
    val refreshLabelFormatter = remember { DateTimeFormatter.ofPattern("h:mm:ss a") }
    var headerWidthPx by remember(view!!.id) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val fallbackHeaderWidthPx = with(density) { 160.dp.toPx() }
    val headerTravel = ((if (headerWidthPx > 0f) headerWidthPx else fallbackHeaderWidthPx) * 0.9f) + (screenWidthPx * 0.35f)
    val headerOffsetAbs = abs(headerPageOffset).coerceIn(0f, 1f)
    fun resetEditStateToSaved() {
        chartSettings = view!!.chartSettings
        selectedSelections = view!!.records.map { selection ->
            selection.withDefaultSettings(view!!.chartSettings)
        }
    }
    fun metricDefaults(): MetricChartSettings = chartSettings.toMetricChartSettings()
    fun copiedMetricSettingsForNewSelection(): MetricChartSettings {
        return selectedSelections.firstOrNull()?.metricSettings ?: metricDefaults()
    }
    fun normalizedSelections(
        selections: List<RecordSelection>,
        settings: ChartSettings
    ): List<RecordSelection> {
        return selections
            .distinctBy { it.fqn }
            .map { it.withDefaultSettings(settings) }
    }
    val hasUnsavedChanges = remember(
        view!!.chartSettings,
        view!!.records,
        chartSettings,
        selectedSelections
    ) {
        val savedSelections = normalizedSelections(view!!.records, view!!.chartSettings)
        val draftSelections = normalizedSelections(selectedSelections, chartSettings)
        view!!.chartSettings != chartSettings || savedSelections != draftSelections
    }
    fun saveChanges() {
        val newView = view!!.copy(
            records = normalizedSelections(selectedSelections, chartSettings),
            chartSettings = chartSettings
        )
        actionScope.launch { viewModel.updateView(newView) }
        isEditing.value = false
    }
    val hasGraphShareData = metricSeriesList?.any { it.points.isNotEmpty() } == true
    val showGraphShareDoNotShowAgain = graphSharePreferences.dialogShownCount >= 2
    val shouldSkipGraphShareWarning =
        graphSharePreferences.hideDialog && graphSharePreferences.dialogShownCount >= 3
    fun launchGraphShare(
        selectedTheme: GraphShareTheme,
        hideDialogAfterShare: Boolean
    ) {
        val currentSeries = metricSeriesList ?: return
        if (currentSeries.isEmpty()) return
        actionScope.launch {
            isGraphShareInProgress = true
            try {
                context.updateGraphSharePreferences(
                    selectedTheme = selectedTheme,
                    hideDialog = hideDialogAfterShare
                )
                val imageFile = withContext(Dispatchers.IO) {
                    writeGraphSharePng(
                        context = context,
                        title = info!!.name,
                        seriesList = currentSeries,
                        settings = view!!.chartSettings,
                        theme = selectedTheme
                    )
                }
                shareGraphImage(
                    context = context,
                    imageFile = imageFile,
                    title = info!!.name
                )
            } catch (exception: Exception) {
                Log.e(ENTRY_DETAILS_LOG_TAG, "Failed to share graph image", exception)
                graphShareError = context.getString(R.string.graph_share_failed_message)
            } finally {
                isGraphShareInProgress = false
            }
        }
    }

    LaunchedEffect(graphSharePreferences.selectedTheme, showGraphThemeDialog, showGraphWarningDialog) {
        if (!showGraphThemeDialog && !showGraphWarningDialog) {
            selectedGraphShareTheme = graphSharePreferences.selectedTheme
        }
    }

    LaunchedEffect(recordCount, metricSeriesList) {
        if ((recordCount != null || metricSeriesList != null) && lastRefreshedMillis == null) {
            lastRefreshedMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(
        hasHistoryPermission,
        chartSettings.timeWindow,
        view!!.chartSettings.timeWindow,
        view!!.id
    ) {
        if (!hasHistoryPermission) {
            if (chartSettings.timeWindow !in timeWindowOptions) {
                if (pendingTimeWindowRestore == null) {
                    pendingTimeWindowRestore = chartSettings.timeWindow
                }
                autoClampedTimeWindow = TimeWindow.DAYS_30
                chartSettings = chartSettings.copy(timeWindow = TimeWindow.DAYS_30)
            } else if (
                autoClampedTimeWindow != null &&
                chartSettings.timeWindow != autoClampedTimeWindow
            ) {
                pendingTimeWindowRestore = null
                autoClampedTimeWindow = null
            }
            return@LaunchedEffect
        }

        val restoreWindow = pendingTimeWindowRestore
        if (
            restoreWindow != null &&
            autoClampedTimeWindow != null &&
            chartSettings.timeWindow == autoClampedTimeWindow &&
            view!!.chartSettings.timeWindow == restoreWindow &&
            restoreWindow in timeWindowOptions
        ) {
            chartSettings = chartSettings.copy(timeWindow = restoreWindow)
        }
        pendingTimeWindowRestore = null
        autoClampedTimeWindow = null
    }

    val refreshScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                refreshScope.launch {
                    isRefreshing = true
                    try {
                        healthDataModel.refreshMetricsWithData()
                        refreshTick += 1
                    } finally {
                        lastRefreshedMillis = System.currentTimeMillis()
                        isRefreshing = false
                    }
                }
            }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 11.dp,
                    end = 11.dp,
                    top = 17.dp,
                    bottom = if (hasUnsavedChanges) 96.dp else 13.dp
                )
            ) {
                item {
                    Text(
                        text = info!!.name,
                        style = if (configuration.screenWidthDp < 600) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerWidthPx = it.width.toFloat() }
                            .graphicsLayer {
                                translationX = headerPageOffset * headerTravel
                                alpha = if (showHeader) (1f - (0.35f * headerOffsetAbs)) else 0f
                                val headerScale = 1f - (0.08f * headerOffsetAbs)
                                scaleX = headerScale
                                scaleY = headerScale
                            },
                        textAlign = TextAlign.Center
                    )
                    val refreshedAtText = lastRefreshedMillis?.let { refreshedAt ->
                        Instant.ofEpochMilli(refreshedAt)
                            .atZone(ZoneId.systemDefault())
                            .toLocalTime()
                            .format(refreshLabelFormatter)
                    }
                    if (refreshedAtText != null) {
                        Text(
                            text = stringResource(R.string.data_view_last_refreshed, refreshedAtText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = (-2).dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(11.dp))
                }

                if (view!!.type == ViewType.CHART) {
                    item {
                        val dayCount = metricSeriesList?.flatMap { it.points }?.map { it.date }?.distinct()?.size ?: 0
                        ToggleSection(
                            labelText = stringResource(R.string.data_view_graph_days, dayCount),
                            visibleState = isShowingChart,
                            collapsible = false,
                            showArrow = false
                        ) {
                            if (metricSeriesList == null) {
                                GraphStatePlaceholder(
                                    isLoading = true,
                                    message = stringResource(R.string.loading_message),
                                    reserveLegendRows = view!!.records.size.coerceIn(1, HealthDataModel.MAX_CHART_SERIES)
                                )
                            } else if (metricSeriesList!!.isEmpty()) {
                                val hasAnyEntries = (recordCount ?: 0) > 0
                                GraphStatePlaceholder(
                                    isLoading = false,
                                    message = stringResource(
                                        if (hasAnyEntries) {
                                            R.string.data_view_no_graphable_with_hint
                                        } else {
                                            R.string.data_view_no_graphable
                                        }
                                    ),
                                    reserveLegendRows = view!!.records.size.coerceIn(1, HealthDataModel.MAX_CHART_SERIES)
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
                                MetricOverTimeChart(
                                    seriesList = metricSeriesList!!,
                                    settings = view!!.chartSettings,
                                    onShareClick = if (hasGraphShareData) {
                                        {
                                            if (isGraphShareInProgress) return@MetricOverTimeChart
                                            selectedGraphShareTheme = graphSharePreferences.selectedTheme
                                            graphShareDoNotShowAgainChecked = false
                                            showGraphThemeDialog = true
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                entriesSection(
                    scope = this,
                    recordCount = recordCount,
                    onOpenEntries = { onOpenEntriesRequested(view!!.id) }
                )

                viewConfigurationSection(
                    scope = this,
                    healthDataModel = healthDataModel,
                    isEditing = isEditing,
                    chartSettings = chartSettings,
                    onChartSettingsChanged = { chartSettings = it },
                    timeWindowOptions = timeWindowOptions,
                    selectedSelections = selectedSelections,
                    onSelectedSelectionsChanged = { selectedSelections = it },
                    onResetEditStateToSaved = { resetEditStateToSaved() },
                    onShowAddMetricDialog = { showAddMetricDialog = true },
                    onShowDeleteViewConfirmation = { showDeleteViewConfirmation = true },
                    onReplaceMetric = { replaceMetricTargetFqn = it }
                )
            }
        }
        AnimatedVisibility(
            visible = hasUnsavedChanges,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("data_view_discard_button"),
                    onClick = {
                        resetEditStateToSaved()
                        isEditing.value = false
                    }
                ) {
                    Text(stringResource(R.string.data_view_discard))
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("data_view_save_button"),
                    onClick = { saveChanges() }
                ) {
                    Text(stringResource(R.string.data_view_save))
                }
            }
        }
    }

    if (showAddMetricDialog) {
        val available = healthDataModel.collectMetricsWithData().collectAsState(initial = null).value
        val allForDialog = (available ?: PermissionsViewModel.CLASSES).toList().sortedBy {
            PermissionsViewModel.RECORD_NAMES[it] ?: (it.simpleName ?: it.qualifiedName ?: "")
        }
        val existingSelections = selectedSelections.map { it.fqn }.toSet()
        val unselected = allForDialog.filter { cls ->
            val fqn = cls.qualifiedName ?: return@filter false
            !existingSelections.contains(fqn)
        }
        AlertDialog(
            onDismissRequest = { showAddMetricDialog = false },
            title = { Text(stringResource(R.string.data_view_select_metric_to_add)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (unselected.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.data_view_no_more_metrics_to_add),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(unselected.size) { index ->
                            val cls = unselected[index]
                            val fqn = cls.qualifiedName ?: return@items
                            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: (cls.simpleName ?: fqn)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newSelection = RecordSelection(
                                            fqn = fqn,
                                            metricSettings = copiedMetricSettingsForNewSelection()
                                        )
                                        selectedSelections = (selectedSelections + newSelection)
                                            .distinctBy { it.fqn }
                                        showAddMetricDialog = false
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(label)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMetricDialog = false }) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }

    val metricToReplace = replaceMetricTargetFqn
    if (metricToReplace != null) {
        val available = healthDataModel.collectMetricsWithData().collectAsState(initial = null).value
        val allForDialog = (available ?: PermissionsViewModel.CLASSES).toList().sortedBy {
            PermissionsViewModel.RECORD_NAMES[it] ?: (it.simpleName ?: it.qualifiedName ?: "")
        }
        val unselected = allForDialog.filter { cls ->
            val fqn = cls.qualifiedName ?: return@filter false
            fqn == metricToReplace || selectedSelections.none { it.fqn == fqn }
        }
        AlertDialog(
            onDismissRequest = { replaceMetricTargetFqn = null },
            title = { Text(stringResource(R.string.data_view_select_replacement_metric)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(unselected.size) { index ->
                        val cls = unselected[index]
                        val replacementFqn = cls.qualifiedName ?: return@items
                        val label = PermissionsViewModel.RECORD_NAMES[cls] ?: (cls.simpleName ?: replacementFqn)
                        Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                    selectedSelections = selectedSelections.map { selection ->
                                        if (selection.fqn == metricToReplace) {
                                            selection.copy(fqn = replacementFqn)
                                        } else {
                                            selection
                                        }
                                    }
                                    replaceMetricTargetFqn = null
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(label)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { replaceMetricTargetFqn = null }) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }

    if (showDeleteViewConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteViewConfirmation = false },
            title = { Text(stringResource(R.string.data_view_delete_view_title)) },
            text = { Text(stringResource(R.string.data_view_delete_view_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actionScope.launch { viewModel.deleteView(view!!.id) }
                        showDeleteViewConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.data_view_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteViewConfirmation = false }) {
                    Text(stringResource(R.string.data_view_cancel))
                }
            }
        )
    }

    if (showGraphThemeDialog) {
        GraphShareThemeDialog(
            title = info!!.name,
            seriesList = metricSeriesList.orEmpty(),
            settings = view!!.chartSettings,
            selectedTheme = selectedGraphShareTheme,
            onThemeSelected = { selectedGraphShareTheme = it },
            onDismiss = { showGraphThemeDialog = false },
            onContinue = {
                showGraphThemeDialog = false
                if (shouldSkipGraphShareWarning) {
                    launchGraphShare(
                        selectedTheme = selectedGraphShareTheme,
                        hideDialogAfterShare = true
                    )
                } else {
                    showGraphWarningDialog = true
                    graphShareDoNotShowAgainChecked = false
                    actionScope.launch {
                        context.incrementGraphShareDialogCount()
                    }
                }
            }
        )
    }

    if (showGraphWarningDialog) {
        DataLeavingAppWarningDialog(
            title = stringResource(R.string.graph_share_warning_title),
            message = stringResource(R.string.graph_share_warning_message),
            confirmLabel = stringResource(R.string.graph_share_confirm),
            dismissLabel = stringResource(R.string.graph_share_cancel),
            showDoNotShowAgainOption = showGraphShareDoNotShowAgain,
            doNotShowAgainLabel = stringResource(R.string.graph_share_do_not_show_again),
            doNotShowAgainChecked = graphShareDoNotShowAgainChecked,
            onDoNotShowAgainChanged = { checked ->
                graphShareDoNotShowAgainChecked = checked
            },
            onDismiss = { showGraphWarningDialog = false },
            onConfirm = {
                showGraphWarningDialog = false
                launchGraphShare(
                    selectedTheme = selectedGraphShareTheme,
                    hideDialogAfterShare = showGraphShareDoNotShowAgain &&
                        graphShareDoNotShowAgainChecked
                )
            }
        )
    }

    graphShareError?.let { message ->
        AlertDialog(
            onDismissRequest = { graphShareError = null },
            title = { Text(stringResource(R.string.graph_share_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { graphShareError = null }) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }

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
private fun GraphShareThemeDialog(
    title: String,
    seriesList: List<HealthDataModel.MetricSeries>,
    settings: ChartSettings,
    selectedTheme: GraphShareTheme,
    onThemeSelected: (GraphShareTheme) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    val previewWidth = 960
    val previewHeight = 620
    var previewBitmap by remember(title, seriesList, settings, selectedTheme) {
        mutableStateOf<Bitmap?>(null)
    }
    var previewLoading by remember(title, seriesList, settings, selectedTheme) {
        mutableStateOf(true)
    }
    LaunchedEffect(title, seriesList, settings, selectedTheme) {
        previewLoading = true
        previewBitmap = withContext(Dispatchers.Default) {
            runCatching {
                val fullBitmap = renderGraphBitmap(
                    title = title,
                    seriesList = seriesList,
                    settings = settings,
                    theme = selectedTheme,
                    width = previewWidth,
                    height = previewHeight
                )
                val wrappedHeight = graphShareContentHeight(
                    width = previewWidth,
                    height = previewHeight,
                    seriesCount = seriesList.size,
                    bottomPaddingPx = 12f
                )
                if (wrappedHeight < fullBitmap.height) {
                    val croppedBitmap = Bitmap.createBitmap(
                        fullBitmap,
                        0,
                        0,
                        fullBitmap.width,
                        wrappedHeight
                    )
                    fullBitmap.recycle()
                    croppedBitmap
                } else {
                    fullBitmap
                }
            }.getOrNull()
        }
        previewLoading = false
    }
    val previewAspectRatio = previewBitmap?.let { bitmap ->
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } ?: (previewWidth.toFloat() / previewHeight.toFloat())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graph_share_theme_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.graph_share_theme_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                val tabThemes = listOf(GraphShareTheme.LIGHT, GraphShareTheme.DARK)
                val selectedIndex = tabThemes.indexOf(selectedTheme).coerceAtLeast(0)
                TabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabThemes.forEachIndexed { index, theme ->
                        Tab(
                            selected = selectedIndex == index,
                            onClick = { onThemeSelected(theme) },
                            text = {
                                Text(
                                    stringResource(
                                        if (theme == GraphShareTheme.LIGHT) {
                                            R.string.graph_share_theme_light
                                        } else {
                                            R.string.graph_share_theme_dark
                                        }
                                    )
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewAspectRatio)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    when {
                        previewBitmap != null -> {
                            Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        previewLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                strokeWidth = 2.dp
                            )
                        }

                        else -> {
                            Text(
                                text = stringResource(R.string.graph_share_preview_failed),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.graph_share_theme_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.graph_share_cancel))
            }
        }
    )
}
