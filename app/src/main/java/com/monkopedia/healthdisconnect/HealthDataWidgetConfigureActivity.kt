package com.monkopedia.healthdisconnect

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import kotlinx.coroutines.launch

class HealthDataWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        val preselectedViewId = intent?.extras?.getInt(
            HealthDataWidgetContract.EXTRA_PRESELECT_VIEW_ID,
            -1
        )?.takeIf { it >= 0 }
        val autoConfigure = intent?.extras?.getBoolean(
            HealthDataWidgetContract.EXTRA_WIDGET_AUTO_CONFIG,
            false
        ) == true
        val updateWindowOverride = intent?.extras?.getString(
            HealthDataWidgetContract.EXTRA_WIDGET_UPDATE_WINDOW
        )?.let { raw ->
            runCatching { WidgetUpdateWindow.valueOf(raw) }.getOrNull()
        }
        if (autoConfigure && preselectedViewId != null) {
            lifecycleScope.launch {
                val configured = configureWidgetForView(
                    context = this@HealthDataWidgetConfigureActivity,
                    appWidgetId = appWidgetId,
                    viewId = preselectedViewId,
                    updateWindowOverride = updateWindowOverride
                )
                if (configured) {
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    )
                    finish()
                } else {
                    renderWidgetConfigUi(preselectedViewId)
                }
            }
            return
        }
        renderWidgetConfigUi(preselectedViewId)
    }

    private fun renderWidgetConfigUi(preselectedViewId: Int?) {
        val db = AppDatabase.getInstance(this)
        val infoDao = db.dataViewInfoDao()
        setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                WidgetConfigScreen(
                    infos = infoDao.allOrdered().collectAsState(initial = emptyList()).value,
                    preselectedViewId = preselectedViewId,
                    onCancel = { finish() },
                    onConfirm = { viewId, window ->
                        val configured = configureWidgetForView(
                            context = this@HealthDataWidgetConfigureActivity,
                            appWidgetId = appWidgetId,
                            viewId = viewId,
                            updateWindowOverride = window
                        )
                        if (configured) {
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            )
                            finish()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    infos: List<DataViewInfoEntity>,
    preselectedViewId: Int?,
    onCancel: () -> Unit,
    onConfirm: suspend (viewId: Int, window: WidgetUpdateWindow) -> Unit
) {
    var selectedViewId by rememberSaveable { mutableStateOf(preselectedViewId) }
    var selectedWindow by rememberSaveable { mutableStateOf(WidgetUpdateWindow.HOURS_12) }
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var windowMenuExpanded by remember { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()

    LaunchedEffect(infos, preselectedViewId) {
        if (infos.isEmpty()) return@LaunchedEffect
        if (selectedViewId == null || infos.none { it.id == selectedViewId }) {
            selectedViewId = preselectedViewId?.takeIf { id ->
                infos.any { it.id == id }
            } ?: infos.first().id
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.widget_config_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        if (infos.isEmpty()) {
            Text(
                text = stringResource(R.string.widget_no_views_available),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.data_view_close))
            }
            return
        }

        Text(
            text = stringResource(R.string.widget_config_select_view),
            style = MaterialTheme.typography.labelMedium
        )
        TextButton(
            onClick = { viewMenuExpanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_config_view_selector")
        ) {
            val selectedViewName = infos.firstOrNull { it.id == selectedViewId }?.name
                ?: infos.first().name
            Text(selectedViewName)
        }
        DropdownMenu(
            expanded = viewMenuExpanded,
            onDismissRequest = { viewMenuExpanded = false }
        ) {
            infos.forEach { info ->
                DropdownMenuItem(
                    text = { Text(info.name) },
                    onClick = {
                        selectedViewId = info.id
                        viewMenuExpanded = false
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.widget_update_window_label),
            style = MaterialTheme.typography.labelMedium
        )
        TextButton(
            onClick = { windowMenuExpanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_config_window_selector")
        ) {
            Text(
                selectedWindow.toLabel(
                    label3h = stringResource(R.string.widget_update_window_3h),
                    label6h = stringResource(R.string.widget_update_window_6h),
                    label12h = stringResource(R.string.widget_update_window_12h),
                    label24h = stringResource(R.string.widget_update_window_24h)
                )
            )
        }
        DropdownMenu(
            expanded = windowMenuExpanded,
            onDismissRequest = { windowMenuExpanded = false }
        ) {
            WidgetUpdateWindow.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.toLabel(
                                label3h = stringResource(R.string.widget_update_window_3h),
                                label6h = stringResource(R.string.widget_update_window_6h),
                                label12h = stringResource(R.string.widget_update_window_12h),
                                label24h = stringResource(R.string.widget_update_window_24h)
                            )
                        )
                    },
                    onClick = {
                        selectedWindow = option
                        windowMenuExpanded = false
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val viewId = selectedViewId ?: return@Button
                actionScope.launch {
                    onConfirm(viewId, selectedWindow)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_config_confirm_button")
        ) {
            Text(stringResource(R.string.widget_config_add))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.data_view_cancel))
        }
    }
}

private fun WidgetUpdateWindow.toLabel(): String {
    return toLabel("Every 3 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours")
}

private fun WidgetUpdateWindow.toLabel(
    label3h: String,
    label6h: String,
    label12h: String,
    label24h: String
): String {
    return when (this) {
        WidgetUpdateWindow.HOURS_3 -> label3h
        WidgetUpdateWindow.HOURS_6 -> label6h
        WidgetUpdateWindow.HOURS_12 -> label12h
        WidgetUpdateWindow.HOURS_24 -> label24h
    }
}
