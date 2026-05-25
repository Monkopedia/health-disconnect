package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.monkopedia.healthdisconnect.HealthDataModel.RecordSelectionOption
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.model.MetricChartSettings
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
    var pendingMetricChoice by remember { mutableStateOf<PendingMetricChoice?>(null) }
    var headerWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val fallbackHeaderWidthPx = with(density) { 160.dp.toPx() }
    val headerTravel = ((if (headerWidthPx > 0f) headerWidthPx else fallbackHeaderWidthPx) * 0.9f) + (screenWidthPx * 0.35f)
    val headerOffsetAbs = abs(headerPageOffset).coerceIn(0f, 1f)

    fun startCreate(option: RecordSelectionOption) {
        scope.launch { viewModel.createView(option.selection, option.label) }
    }

    fun chooseForCreate(option: RecordSelectionOption) {
        val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == option.selection.fqn }
        if (cls == null) {
            startCreate(option)
            return
        }
        scope.launch {
            if (healthDataModel.hasRecentMetricData(cls, option.selection.metricKey)) {
                viewModel.createView(option.selection, option.label)
            } else {
                pendingMetricChoice = PendingMetricChoice(option.label) { startCreate(option) }
            }
        }
    }

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
        Text(
            stringResource(R.string.create_view_select_base_metric),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (itemsWithData == null) {
            LoadingScreen()
        } else {
            TwoLevelMetricPicker(
                typesWithData = itemsWithData,
                optionsFor = { cls ->
                    healthDataModel.recordSelectionOptions(
                        recordClass = cls,
                        metricSettings = MetricChartSettings()
                    )
                },
                onPick = { option -> chooseForCreate(option) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                emptyContent = {
                    Text(
                        text = stringResource(R.string.create_view_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            )
        }
    }

    pendingMetricChoice?.let { pending ->
        MetricNoDataWarningDialog(
            pending = pending,
            onConfirm = {
                pending.commit()
                pendingMetricChoice = null
            },
            onDismiss = { pendingMetricChoice = null }
        )
    }
}
