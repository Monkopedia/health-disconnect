package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.isConfigValid
import kotlinx.coroutines.flow.map

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
    val isShowingEntries = rememberSaveable { mutableStateOf(view!!.alwaysShowEntries) }
    val isEditing = rememberSaveable { mutableStateOf(!info.isConfigValid || !view.isConfigValid) }
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
        ToggleSection("Entries ($count)", isShowingEntries) {
        }
        ToggleSection("View configuration", isEditing) {
        }
    }
}

@Composable
fun ToggleSection(
    labelText: String,
    visibleState: MutableState<Boolean>,
    content: @Composable () -> Unit
) {
}
