package com.monkopedia.healthdisconnect.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.recordDetailsText
import com.monkopedia.healthdisconnect.recordPrimaryValueLabel
import com.monkopedia.healthdisconnect.recordTimestampLabel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
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
                        .padding(vertical = 6.dp),
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
