package com.monkopedia.healthdisconnect

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import com.monkopedia.healthdisconnect.consumeMatchingPendingWidgetRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WidgetPinSuccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HealthDataWidgetContract.ACTION_WIDGET_PIN_SUCCESS) {
            return
        }
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val viewId = intent.getIntExtra(HealthDataWidgetContract.EXTRA_PRESELECT_VIEW_ID, -1)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || viewId < 0) {
            return
        }
        val windowOverride = intent.getStringExtra(
            HealthDataWidgetContract.EXTRA_WIDGET_UPDATE_WINDOW
        )?.let { raw ->
            runCatching { WidgetUpdateWindow.valueOf(raw) }.getOrNull()
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val configured = configureWidgetForView(
                    context = context,
                    appWidgetId = appWidgetId,
                    viewId = viewId,
                    updateWindowOverride = windowOverride
                )
                if (configured) {
                    context.consumeMatchingPendingWidgetRequest(
                        viewId = viewId,
                        updateWindowName = windowOverride?.name
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
