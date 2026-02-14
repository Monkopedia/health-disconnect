package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import kotlinx.coroutines.launch

@Composable
fun CreateViewView(
    viewModel: DataViewAdapterViewModel = viewModel(),
    healthDataModel: HealthDataModel = viewModel()
) {
    val itemsWithData = healthDataModel.metricsWithData.collectAsState(null).value
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.padding(16.dp).padding(top = 16.dp)) {
        item {
            Text(
                stringResource(R.string.create_view_select_base_metric),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        if (itemsWithData == null) {
            item {
                LoadingScreen()
            }
        } else {
            items(itemsWithData.size) { index ->
                TextButton({
                    scope.launch {
                        // TODO: Dialog or loading state
                        viewModel.createView(itemsWithData[index])
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        PermissionsViewModel.RECORD_NAMES[itemsWithData[index]]
                            ?: error("Missing name mapping"),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
