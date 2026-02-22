package com.monkopedia.healthdisconnect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.EntriesExportMode
import com.monkopedia.healthdisconnect.GraphShareTheme
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.graphShareContentHeight
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.renderGraphBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GraphShareThemeDialog(
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
    var previewBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }
    var previewLoading by remember {
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
                    containerColor = AlertDialogDefaults.containerColor
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GraphShareActionsBottomSheet(
    onDismiss: () -> Unit,
    onShareGraph: () -> Unit,
    onShareEntries: () -> Unit,
    onAddWidget: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = stringResource(R.string.graph_share_actions_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onShareGraph,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("data_view_share_sheet_graph")
            ) {
                Text(stringResource(R.string.graph_share_action_graph))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onShareEntries,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("data_view_share_sheet_entries")
            ) {
                Text(stringResource(R.string.graph_share_action_entries))
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onAddWidget,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("data_view_share_sheet_widget")
            ) {
                Text(stringResource(R.string.graph_share_action_widget))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun GraphEntriesExportModeDialog(
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
                        .testTag("graph_entries_export_aggregated")
                ) {
                    Text(stringResource(R.string.data_view_export_aggregated))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onExportModeSelected(EntriesExportMode.RAW) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("graph_entries_export_raw")
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
