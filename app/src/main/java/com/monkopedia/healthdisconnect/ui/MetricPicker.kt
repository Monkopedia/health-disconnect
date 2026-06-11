package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.HealthDataModel.RecordSelectionOption
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import kotlin.reflect.KClass

/** A metric the user picked that has no recent data, awaiting their confirmation to add anyway. */
class PendingMetricChoice(val label: String, val commit: () -> Unit)

private fun recordTypeName(cls: KClass<out Record>): String =
    PermissionsViewModel.recordLabel(cls)

/**
 * Two-level metric picker shared by Create View and the add/replace-metric dialogs.
 *
 * Level 1 lists record types that have at least one selectable metric. Tapping a type with
 * more than one metric drills into level 2 (its sub-metrics) with a back row; a single-metric
 * type is picked immediately. This keeps all three entry points consistent and avoids a flat
 * list of every nutrient.
 */
@Composable
fun TwoLevelMetricPicker(
    typesWithData: List<KClass<out Record>>,
    optionsFor: (KClass<out Record>) -> List<RecordSelectionOption>,
    onPick: (RecordSelectionOption) -> Unit,
    modifier: Modifier = Modifier,
    isSelectable: (RecordSelectionOption) -> Boolean = { true },
    emptyContent: @Composable () -> Unit = {}
) {
    var drilledType by remember { mutableStateOf<KClass<out Record>?>(null) }
    val rowModifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)

    LazyColumn(modifier) {
        val current = drilledType
        if (current == null) {
            val types = typesWithData
                .filter { type -> optionsFor(type).any(isSelectable) }
                .sortedBy(::recordTypeName)
            if (types.isEmpty()) {
                item { emptyContent() }
            } else {
                items(types) { type ->
                    val options = optionsFor(type).filter(isSelectable)
                    Row(
                        modifier = rowModifier.clickable {
                            if (options.size <= 1) {
                                options.firstOrNull()?.let(onPick)
                            } else {
                                drilledType = type
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = recordTypeName(type), modifier = Modifier.weight(1f))
                        if (options.size > 1) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        } else {
            val typeName = recordTypeName(current)
            item {
                Row(
                    modifier = rowModifier.clickable { drilledType = null },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = typeName, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
            val options = optionsFor(current).filter(isSelectable).sortedBy { it.label }
            items(options) { option ->
                Row(modifier = rowModifier.clickable { onPick(option) }) {
                    Text(text = option.label.removePrefix("$typeName "))
                }
                HorizontalDivider()
            }
        }
    }
}

/** Warns that the chosen metric has no recent data, but lets the user add it anyway. */
@Composable
fun MetricNoDataWarningDialog(
    pending: PendingMetricChoice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_view_no_recent_data_title)) },
        text = { Text(stringResource(R.string.data_view_metric_no_recent_data, pending.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.data_view_add_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.data_view_cancel))
            }
        }
    )
}
