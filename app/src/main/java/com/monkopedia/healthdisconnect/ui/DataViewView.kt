package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.Record
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.isConfigValid
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    val data by healthDataModel.collectData(view!!).collectAsState(initial = null)
    val isShowingEntries = rememberSaveable(view!!.id) { mutableStateOf(view!!.alwaysShowEntries) }
    val isEditing =
        rememberSaveable(view!!.id) { mutableStateOf(!info.isConfigValid || !view.isConfigValid) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp)
    ) {
        Text(
            text = info!!.name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(16.dp))

        ToggleSection("Entries (${data?.size ?: 0})", isShowingEntries) {
            if (data == null) {
                LoadingScreen()
            } else {
                val list = data!!
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(list.size) { index ->
                        val r: Record = list[index]
                        Text(r::class.simpleName ?: r::class.qualifiedName ?: "Record")
                        Divider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleSection("View configuration", isEditing) {
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
                Text("Always show entries")
                Checkbox(checked = isShowingEntries.value, onCheckedChange = {
                    isShowingEntries.value =
                        it
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("Select record types:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
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
                    Divider()
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val newView = DataView(
                        id = view!!.id,
                        type = view!!.type,
                        records = selected.value.distinct().map { RecordSelection(it) },
                        alwaysShowEntries = isShowingEntries.value
                    )
                    scope.launch { viewModel.updateView(newView) }
                    isEditing.value = false
                }) {
                    Text("Save")
                }
                TextButton(onClick = { isEditing.value = false }) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun ToggleSection(
    labelText: String,
    visibleState: MutableState<Boolean>,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { visibleState.value = !visibleState.value }) {
            Text(labelText, style = MaterialTheme.typography.titleMedium)
        }
        if (visibleState.value) {
            content()
        }
    }
}
