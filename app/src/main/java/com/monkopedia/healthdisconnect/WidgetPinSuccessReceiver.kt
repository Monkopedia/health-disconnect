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
            logWidgetFlow("WidgetPinSuccessReceiver ignored action=${intent.action}")
            return
        }
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val viewId = intent.getIntExtra(HealthDataWidgetContract.EXTRA_PRESELECT_VIEW_ID, -1)
        logWidgetFlow(
            "WidgetPinSuccessReceiver received appWidgetId=$appWidgetId viewId=$viewId"
        )
        val rawWindow = intent.getStringExtra(HealthDataWidgetContract.EXTRA_WIDGET_UPDATE_WINDOW)
        val windowOverride = rawWindow?.let { raw ->
            runCatching { WidgetUpdateWindow.valueOf(raw) }.getOrNull()
        }
        logWidgetFlow(
            "WidgetPinSuccessReceiver parsedWindow window=${windowOverride?.name}"
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || viewId < 0) {
            logWidgetFlow(
                "WidgetPinSuccessReceiver invalidExtras appWidgetId=$appWidgetId viewId=$viewId"
            )
            discardPendingRequestForFailedCallback(context, viewId, rawWindow)
            return
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
                logWidgetFlow(
                    "WidgetPinSuccessReceiver configured=$configured appWidgetId=$appWidgetId viewId=$viewId"
                )
                if (configured) {
                    context.consumeMatchingPendingWidgetRequest(
                        viewId = viewId,
                        updateWindowName = windowOverride?.name
                    )
                }
            } catch (exception: Exception) {
                logWidgetFlowError(
                    "WidgetPinSuccessReceiver failed appWidgetId=$appWidgetId viewId=$viewId",
                    exception
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * A pin-success callback is only ever fired for a request this app queued, so by the time it
     * arrives its queue entry is owed a resolution — success or not. Returning early on unusable
     * extras used to skip that, stranding the entry: `HealthDataWidgetProvider.onUpdate` pops the
     * *head* of the queue for any unbound widget id, so the orphan would later be applied to some
     * widget the user never chose it for (issue #85).
     *
     * Retire the entry unconditionally. Prefer the exact entry this callback describes; if the
     * view id itself is the unusable part there is nothing to match on, so drop the head — the
     * request that produced this callback is the oldest outstanding one. Dropping too much only
     * ever costs a widget the "needs configuration" placeholder, which the user can resolve from
     * the configure activity; dropping too little persists a wrong binding.
     */
    private fun discardPendingRequestForFailedCallback(
        context: Context,
        viewId: Int,
        updateWindowName: String?
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val matched = viewId >= 0 && context.consumeMatchingPendingWidgetRequest(
                    viewId = viewId,
                    updateWindowName = updateWindowName
                )
                if (!matched) {
                    val dropped = context.consumePendingWidgetRequest()
                    logWidgetFlow(
                        "WidgetPinSuccessReceiver discardedQueueHead viewId=${dropped?.viewId} updateWindow=${dropped?.updateWindowName}"
                    )
                } else {
                    logWidgetFlow(
                        "WidgetPinSuccessReceiver discardedMatchingPending viewId=$viewId updateWindow=$updateWindowName"
                    )
                }
            } catch (exception: Exception) {
                logWidgetFlowError(
                    "WidgetPinSuccessReceiver failed discarding pending request viewId=$viewId",
                    exception
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
