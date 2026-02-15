package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun CreateViewView(
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
    headerPageOffset: Float = 0f,
    showHeader: Boolean = true
) {
    val itemsWithData = healthDataModel.collectMetricsWithData().collectAsState(initial = null).value
    val scope = rememberCoroutineScope()
    var headerWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val fallbackHeaderWidthPx = with(density) { 160.dp.toPx() }
    val headerTravel = ((if (headerWidthPx > 0f) headerWidthPx else fallbackHeaderWidthPx) * 0.9f) + (screenWidthPx * 0.35f)
    val headerOffsetAbs = abs(headerPageOffset).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 11.dp, vertical = 13.dp)
    ) {
        Text(
            text = stringResource(R.string.create_view_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .onSizeChanged { headerWidthPx = it.width.toFloat() }
                .graphicsLayer {
                    translationX = headerPageOffset * headerTravel
                    alpha = if (showHeader) (1f - (0.35f * headerOffsetAbs)) else 0f
                    val headerScale = 1f - (0.08f * headerOffsetAbs)
                    scaleX = headerScale
                    scaleY = headerScale
                }
        )
        Spacer(Modifier.height(11.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text(
                    stringResource(R.string.create_view_select_base_metric),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (itemsWithData == null) {
                item {
                    LoadingScreen()
                }
            } else {
                items(itemsWithData) { metricClass ->
                    TextButton(
                        onClick = {
                        scope.launch {
                            // TODO: Dialog or loading state
                            viewModel.createView(metricClass)
                        }
                    },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            PermissionsViewModel.RECORD_NAMES[metricClass]
                                ?: error("Missing name mapping"),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}
