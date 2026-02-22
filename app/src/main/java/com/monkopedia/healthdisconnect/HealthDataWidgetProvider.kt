package com.monkopedia.healthdisconnect

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HealthDataWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        logWidgetFlow("HealthDataWidgetProvider.onUpdate ids=${appWidgetIds.joinToString(",")}")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    val existingViewId = context.widgetViewId(appWidgetId)
                    if (existingViewId == null) {
                        logWidgetFlow("HealthDataWidgetProvider.onUpdate unbound appWidgetId=$appWidgetId")
                        val pending = context.consumePendingWidgetRequest()
                        if (pending != null) {
                            logWidgetFlow(
                                "HealthDataWidgetProvider.onUpdate applyingPending appWidgetId=$appWidgetId pendingViewId=${pending.viewId} pendingWindow=${pending.updateWindowName}"
                            )
                            configureWidgetForView(
                                context = context,
                                appWidgetId = appWidgetId,
                                viewId = pending.viewId,
                                updateWindowOverride = pending.updateWindowName?.let { raw ->
                                    runCatching { WidgetUpdateWindow.valueOf(raw) }.getOrNull()
                                }
                            )
                        } else {
                            logWidgetFlow(
                                "HealthDataWidgetProvider.onUpdate noPending appWidgetId=$appWidgetId"
                            )
                        }
                    } else {
                        logWidgetFlow(
                            "HealthDataWidgetProvider.onUpdate alreadyBound appWidgetId=$appWidgetId viewId=$existingViewId"
                        )
                    }
                    HealthDataWidgetScheduler.scheduleForWidget(context, appWidgetId)
                    HealthDataWidgetUpdater.updateWidget(context, appWidgetId, appWidgetManager)
                }
            } catch (exception: Exception) {
                logWidgetFlowError("HealthDataWidgetProvider.onUpdate failed", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        if (appWidgetIds.isEmpty()) return
        logWidgetFlow("HealthDataWidgetProvider.onDeleted ids=${appWidgetIds.joinToString(",")}")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                context.unbindWidgets(appWidgetIds)
                HealthDataWidgetScheduler.cancelWidgetJobs(context, appWidgetIds)
            } catch (exception: Exception) {
                logWidgetFlowError("HealthDataWidgetProvider.onDeleted failed", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        logWidgetFlow("HealthDataWidgetProvider.onEnabled")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                HealthDataWidgetScheduler.scheduleAll(context)
                HealthDataWidgetUpdater.updateAllWidgets(context)
            } catch (exception: Exception) {
                logWidgetFlowError("HealthDataWidgetProvider.onEnabled failed", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
