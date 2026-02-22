package com.monkopedia.healthdisconnect.ui

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import com.monkopedia.healthdisconnect.formatAxisValue
import com.monkopedia.healthdisconnect.recordDetailsText
import com.monkopedia.healthdisconnect.recordPrimaryValueLabel
import com.monkopedia.healthdisconnect.recordTimestampLabel
import com.monkopedia.healthdisconnect.shareEntriesCsv
import com.monkopedia.healthdisconnect.unitSuffix
import com.monkopedia.healthdisconnect.writeEntriesCsvToCache
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.UnitPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

fun entriesSection(
    scope: LazyListScope,
    recordCount: Int?,
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
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
    initialSelectedEntry: Record? = null
) {
    val context = LocalContext.current
    val actionScope = rememberCoroutineScope()
    val infoFlow = remember(viewModel, viewId) {
        viewModel.dataViews.map { it?.dataViews?.get(viewId) }
    }
    val info by infoFlow.collectAsState(initial = null)
    val view by viewModel.dataView(viewId).collectAsState(initial = null)
    if (view == null) {
        LoadingScreen()
        return
    }
    val data by healthDataModel.collectData(view!!).collectAsState(initial = null)
    var selectedEntryForDetails by remember(viewId, initialSelectedEntry) {
        mutableStateOf(initialSelectedEntry)
    }
    var showExportModeDialog by rememberSaveable(viewId) { mutableStateOf(false) }
    var pendingWarningMode by rememberSaveable(viewId) { mutableStateOf<EntriesExportMode?>(null) }
    var isExporting by rememberSaveable(viewId) { mutableStateOf(false) }
    var exportErrorMessage by rememberSaveable(viewId) { mutableStateOf<String?>(null) }
    val measurementExtractor = remember { DefaultHealthRecordMeasurementExtractor() }

    EntriesScreen(
        infoName = info?.name ?: "",
        data = data,
        onBack = onBack,
        onShareRequested = { showExportModeDialog = true },
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
                                val series = healthDataModel.loadAggregatedSeriesForExport(currentView)
                                buildAggregatedEntriesCsv(currentView, series)
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
                        exportErrorMessage = context.getString(R.string.data_view_export_failed)
                    } finally {
                        isExporting = false
                    }
                }
            }
        )
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
    data: List<Record>?,
    onBack: () -> Unit,
    onShareRequested: () -> Unit,
    isExporting: Boolean,
    valuePreviewForRecord: (Record) -> String?,
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
        CenterAlignedTopAppBar(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    text = stringResource(R.string.data_view_entries_for, infoName),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        Spacer(Modifier.height(6.dp))
        if (data == null) {
            LoadingScreen()
            return
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(data.size) { index ->
                val record = data[index]
                val timestamp = recordTimestampLabel(record)
                val valuePreview = valuePreviewForRecord(record)
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
        SnackbarHost(hostState = snackbarHostState)
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
    val unitPreference = unitPreferenceForRecord(view, record)
    val measurement = measurementExtractor.extractMeasurement(record, unitPreference)
    return if (measurement != null) {
        "${formatAxisValue(measurement.value)}${unitSuffix(measurement.unitLabel)}"
    } else {
        recordPrimaryValueLabel(record)
    }
}

private fun unitPreferenceForRecord(view: DataView, record: Record): UnitPreference {
    val matchingSelection = view.records.firstOrNull { selection ->
        val selectedClass = PermissionsViewModel.CLASSES.firstOrNull {
            it.qualifiedName == selection.fqn
        }
        selectedClass?.java?.isAssignableFrom(record.javaClass) == true
    }
    return matchingSelection?.metricSettings?.unitPreference ?: view.chartSettings.unitPreference
}
