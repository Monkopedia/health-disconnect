package com.monkopedia.healthdisconnect

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WidgetAppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, HealthDataWidgetProvider::class.java)
        )
        logWidgetFlow(
            "WidgetAppUpdateReceiver.onReceive packageReplaced ids=${appWidgetIds.joinToString(",")}"
        )
        if (appWidgetIds.isEmpty()) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    HealthDataWidgetScheduler.scheduleForWidget(context, appWidgetId)
                    HealthDataWidgetUpdater.updateWidget(context, appWidgetId, appWidgetManager)
                }
                HealthDataWidgetScheduler.schedulePostUpdateRefresh(context, appWidgetIds)
                logWidgetFlow(
                    "WidgetAppUpdateReceiver.onReceive refreshedAndScheduled ids=${appWidgetIds.joinToString(",")}"
                )
            } catch (exception: Exception) {
                logWidgetFlowError("WidgetAppUpdateReceiver.onReceive failed", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
