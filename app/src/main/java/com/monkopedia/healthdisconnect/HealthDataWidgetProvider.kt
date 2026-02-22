package com.monkopedia.healthdisconnect

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
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
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    HealthDataWidgetScheduler.scheduleForWidget(context, appWidgetId)
                    HealthDataWidgetUpdater.updateWidget(context, appWidgetId, appWidgetManager)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                context.unbindWidgets(appWidgetIds)
                HealthDataWidgetScheduler.cancelWidgetJobs(context, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                HealthDataWidgetScheduler.scheduleAll(context)
                HealthDataWidgetUpdater.updateAllWidgets(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
