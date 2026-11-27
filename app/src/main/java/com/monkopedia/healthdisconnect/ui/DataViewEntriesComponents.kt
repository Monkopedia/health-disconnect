package com.monkopedia.healthdisconnect.ui

import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.DefaultHealthRecordMeasurementExtractor
import com.monkopedia.healthdisconnect.EntriesExportMode
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.HealthRecordMeasurementExtractor
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.buildAggregatedEntriesCsv
import com.monkopedia.healthdisconnect.buildRawEntriesCsv
import com.monkopedia.healthdisconnect.formatValueWithUnit
import com.monkopedia.healthdisconnect.recordDetailsText
import com.monkopedia.healthdisconnect.recordPrimaryValueLabel
import com.monkopedia.healthdisconnect.recordTimestampLabel
import com.monkopedia.healthdisconnect.shareEntriesCsv
import com.monkopedia.healthdisconnect.writeEntriesCsvToCache
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.UnitPreference
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

fun entriesSection(
    scope: LazyListScope,
    recordCount: HealthDataModel.RecordCount?,
    onOpenEntries: () -> Unit
) {
    scope.item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenEntries() }
                .testTag("entries_row")
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val count = recordCount?.count ?: 0
            val countText = if (recordCount?.atLeast == true) {
                stringResource(R.string.data_view_entries_count_at_least, count)
            } else {
                stringResource(R.string.data_view_entries_count, count)
            }
            Text(
                text = countText,
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

/**
 * View [viewId]'s entries: its newest [HealthDataModel.MAX_LISTED_RECORDS] per record type, or with
 * [range] set, only that range's (issue #139), read from Health Connect for that range alone so an
 * old bucket lists its entries too. A range's list names the range in its header and has no export,
 * which covers the view's whole window.
 */
@Composable
fun EntriesRouteScreen(
    viewId: Int,
    onBack: () -> Unit,
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
    initialSelectedEntry: Record? = null,
    range: ChartRange? = null
) {
    val context = LocalContext.current
    // Resolved at composition (not via context.getString in the export coroutine) per Compose's
    // LocalContextGetResourceValueCall lint check.
    val exportFailedMessage = stringResource(R.string.data_view_export_failed)
    val actionScope = rememberCoroutineScope()
    val infos by viewModel.dataViews.collectAsState(initial = null)
    val info = infos?.dataViews?.get(viewId)
    val view by viewModel.dataView(viewId).collectAsState(initial = null)
    if (infos != null && info == null) {
        RouteUnavailableScreen(R.string.route_view_deleted, onBack)
        return
    }
    if (view == null) {
        LoadingScreen()
        return
    }
    val loaded by remember(view, range) {
        range?.let { healthDataModel.collectEntriesInRange(view!!, it) }
            ?: healthDataModel.collectData(view!!).map {
                HealthDataModel.LoadedEntries(it, HealthDataModel.ChartLoadIssues())
            }
    }.collectAsState(initial = null)
    val data = loaded?.records
    var selectedEntryForDetails by remember(viewId, initialSelectedEntry) {
        mutableStateOf(initialSelectedEntry)
    }
    var showExportModeDialog by rememberSaveable(viewId) { mutableStateOf(false) }
    var pendingWarningMode by rememberSaveable(viewId) { mutableStateOf<EntriesExportMode?>(null) }
    var isExporting by rememberSaveable(viewId) { mutableStateOf(false) }
    var exportErrorMessage by rememberSaveable(viewId) { mutableStateOf<String?>(null) }
    var exportIssues by remember(viewId) { mutableStateOf<HealthDataModel.ChartLoadIssues?>(null) }
    val measurementExtractor = remember { DefaultHealthRecordMeasurementExtractor() }
    val filteredData = remember(data, view) {
        data?.filter { recordHasSelectedMetricValue(view!!, it, measurementExtractor) }
    }

    val now = LocalClock.current.instant()
    EntriesScreen(
        infoName = info?.name ?: "",
        rangeLabel = range?.let {
            val zoneId = ZoneId.systemDefault()
            formatBucketLabel(it.start, it.bucketSize, withYear = it.start.atZone(zoneId).year != now.atZone(zoneId).year)
        },
        issues = loaded?.issues ?: HealthDataModel.ChartLoadIssues(),
        data = filteredData,
        onBack = onBack,
        onShareRequested = { showExportModeDialog = true }.takeIf { range == null },
        isExporting = isExporting,
        valuePreviewForRecord = { record ->
            formatRecordValuePreview(
                view = view!!,
                record = record,
                measurementExtractor = measurementExtractor
            )
        },
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

    if (showExportModeDialog) {
        ExportModeDialog(
            onDismiss = { showExportModeDialog = false },
            onExportModeSelected = { mode ->
                showExportModeDialog = false
                pendingWarningMode = mode
            }
        )
    }

    pendingWarningMode?.let { mode ->
        DataLeavingAppWarningDialog(
            title = stringResource(R.string.data_view_export_warning_title),
            message = stringResource(R.string.data_view_export_warning_message),
            confirmLabel = stringResource(R.string.data_view_export_continue),
            dismissLabel = stringResource(R.string.data_view_cancel),
            onDismiss = { pendingWarningMode = null },
            onConfirm = {
                pendingWarningMode = null
                actionScope.launch {
                    isExporting = true
                    try {
                        val currentView = view!!
                        val csvText = when (mode) {
                            EntriesExportMode.AGGREGATED -> {
                                val exported = healthDataModel.loadAggregatedSeriesForExport(currentView)
                                exportIssues = exported.issues.takeUnless { it.isComplete }
                                buildAggregatedEntriesCsv(currentView, exported.series, exported.issues.truncatedSince)
                            }

                            EntriesExportMode.RAW -> {
                                val records = healthDataModel.loadRawDataForExport(currentView)
                                buildRawEntriesCsv(records)
                            }
                        }
                        val file = withContext(Dispatchers.IO) {
                            writeEntriesCsvToCache(
                                context = context,
                                viewName = info?.name ?: "entries",
                                mode = mode,
                                csvText = csvText
                            )
                        }
                        shareEntriesCsv(
                            context = context,
                            viewName = info?.name ?: "entries",
                            file = file
                        )
                    } catch (exception: Exception) {
                        Log.e(ENTRY_DETAILS_LOG_TAG, "Failed to export entries CSV", exception)
                        exportErrorMessage = exportFailedMessage
                    } finally {
                        isExporting = false
                    }
                }
            }
        )
    }

    exportIssues?.let { issues ->
        ExportIncompleteDialog(issues, onDismiss = { exportIssues = null })
    }

    exportErrorMessage?.let {
        AlertDialog(
            onDismissRequest = { exportErrorMessage = null },
            title = { Text(stringResource(R.string.data_view_export_error_title)) },
            text = { Text(it) },
            confirmButton = {
                TextButton(onClick = { exportErrorMessage = null }) {
                    Text(stringResource(R.string.data_view_close))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntriesScreen(
    infoName: String,
    /** The range listed, e.g. `Jan 11 – 17`, or null for the view's newest entries. */
    rangeLabel: String?,
    issues: HealthDataModel.ChartLoadIssues,
    data: List<Record>?,
    onBack: () -> Unit,
    /** Null hides the export. */
    onShareRequested: (() -> Unit)?,
    isExporting: Boolean,
    valuePreviewForRecord: (Record) -> String?,
    onEntrySelected: (Record) -> Unit
) {
    val clipboard = LocalClipboard.current
    val copiedMessage = stringResource(R.string.data_view_entry_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.data_view_entries_for, infoName),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        rangeLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("entries_range_label")
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("entries_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.data_view_back)
                        )
                    }
                },
                actions = {
                    if (onShareRequested != null) {
                        IconButton(
                            onClick = onShareRequested,
                            enabled = !isExporting,
                            modifier = Modifier.testTag("entries_share_button")
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = stringResource(R.string.data_view_export_share)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LoadingScreen()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 11.dp),
                contentPadding = PaddingValues(top = 19.dp, bottom = 13.dp)
            ) {
                if (!issues.isComplete) {
                    item { EntriesLoadIssueNotices(issues) }
                }
                if (data.isEmpty() && rangeLabel != null) {
                    item {
                        Text(
                            text = stringResource(R.string.data_view_entries_range_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("entries_range_empty")
                        )
                    }
                }
                items(data.size) { index ->
                    val record = data[index]
                    val timestamp = recordTimestampLabel(record)
                    val valuePreview = valuePreviewForRecord(record)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entries_item_row_$index")
                            .clickable {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipData.newPlainText(null, recordDetailsText(record))
                                            .toClipEntry()
                                    )
                                    snackbarHostState.showSnackbar(message = copiedMessage)
                                }
                                onEntrySelected(record)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
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
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = valuePreview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ExportModeDialog(
    onDismiss: () -> Unit,
    onExportModeSelected: (EntriesExportMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_view_export_title)) },
        text = {
            Column {
                Text(stringResource(R.string.data_view_export_mode_message))
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onExportModeSelected(EntriesExportMode.AGGREGATED) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entries_export_aggregated")
                ) {
                    Text(stringResource(R.string.data_view_export_aggregated))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onExportModeSelected(EntriesExportMode.RAW) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("entries_export_raw")
                ) {
                    Text(stringResource(R.string.data_view_export_raw))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.data_view_cancel))
            }
        }
    )
}

private fun formatRecordValuePreview(
    view: DataView,
    record: Record,
    measurementExtractor: HealthRecordMeasurementExtractor
): String? {
    val selection = matchingSelectionForRecord(view, record)
    val unitPreference = selection?.metricSettings?.unitPreference ?: view.chartSettings.unitPreference
    val measurement = measurementExtractor.extractMeasurement(record, unitPreference, selection?.metricKey)
    return if (measurement != null) {
        formatValueWithUnit(measurement.value, measurement.unitLabel)
    } else {
        recordPrimaryValueLabel(record)
    }
}

private fun matchingSelectionForRecord(view: DataView, record: Record): RecordSelection? {
    return view.records.firstOrNull { selection ->
        val selectedClass = PermissionsViewModel.classForFqn(selection.fqn)
        selectedClass?.java?.isAssignableFrom(record.javaClass) == true
    }
}

/**
 * True when [record] yields a value for at least one of the view's selected metrics.
 * Used to keep the entries list specific to what the view actually charts — e.g. a
 * "Nutrition Caffeine" view should not list sodium-only records.
 */
private fun recordHasSelectedMetricValue(
    view: DataView,
    record: Record,
    measurementExtractor: HealthRecordMeasurementExtractor
): Boolean {
    return view.records.any { selection ->
        val selectedClass = PermissionsViewModel.classForFqn(selection.fqn)
        selectedClass?.java?.isAssignableFrom(record.javaClass) == true &&
            measurementExtractor.extractMeasurement(
                record,
                UnitPreference.METRIC,
                selection.metricKey
            ) != null
    }
}

/**
 * Says why a range's entries may be fewer than Health Connect holds: a failed read, or a record type
 * that stopped at [HealthDataModel.MAX_LISTED_RECORDS], newest first. That can stop mid-day, so the
 * notice gives the time as well as the date.
 */
@Composable
private fun EntriesLoadIssueNotices(issues: HealthDataModel.ChartLoadIssues) {
    Column(Modifier.padding(bottom = 8.dp).testTag("entries_load_issues")) {
        issues.readFailure?.let { failure ->
            Text(
                stringResource(
                    when (failure) {
                        HealthDataModel.ReadFailure.RATE_LIMITED -> R.string.data_view_entries_rate_limited
                        HealthDataModel.ReadFailure.OTHER -> R.string.data_view_entries_read_failed
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        issues.truncatedSince?.let { since ->
            val at = since.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
            Text(
                stringResource(R.string.data_view_entries_range_truncated, at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Tells the user an aggregated export is missing data — a series cut short by the raw-record cap,
 * or a failed read — instead of letting it silently start later than their time window (#112).
 */
@Composable
internal fun ExportIncompleteDialog(issues: HealthDataModel.ChartLoadIssues, onDismiss: () -> Unit) {
    val lines = buildList {
        when (issues.readFailure) {
            HealthDataModel.ReadFailure.RATE_LIMITED -> add(stringResource(R.string.data_view_export_rate_limited))
            HealthDataModel.ReadFailure.OTHER -> add(stringResource(R.string.data_view_export_read_failed))
            null -> Unit
        }
        issues.truncatedSince?.let { since ->
            val date = since.atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            add(stringResource(R.string.data_view_export_truncated, date))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_view_export_incomplete_title)) },
        text = { Text(lines.joinToString("\n\n")) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.data_view_close))
            }
        }
    )
}
