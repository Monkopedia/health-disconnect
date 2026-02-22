package com.monkopedia.healthdisconnect

import android.content.Context
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import com.monkopedia.healthdisconnect.room.AppDatabase

suspend fun configureWidgetForView(
    context: Context,
    appWidgetId: Int,
    viewId: Int,
    updateWindowOverride: WidgetUpdateWindow? = null,
    refreshImmediately: Boolean = true
): Boolean {
    logWidgetFlow(
        "configureWidgetForView start appWidgetId=$appWidgetId viewId=$viewId updateWindowOverride=${updateWindowOverride?.name} refreshImmediately=$refreshImmediately"
    )
    val db = AppDatabase.getInstance(context)
    val viewEntity = db.dataViewDao().getById(viewId)
    if (viewEntity == null) {
        logWidgetFlow("configureWidgetForView missingView viewId=$viewId")
        return false
    }
    if (updateWindowOverride != null) {
        val view = decodeDataViewEntity(viewEntity)
        if (view.chartSettings.widgetUpdateWindow != updateWindowOverride) {
            val updatedView = view.copy(
                chartSettings = view.chartSettings.copy(
                    widgetUpdateWindow = updateWindowOverride
                )
            )
            db.dataViewDao().insert(encodeDataViewEntity(updatedView))
            logWidgetFlow(
                "configureWidgetForView updatedWindow viewId=$viewId newWindow=${updateWindowOverride.name}"
            )
        } else {
            logWidgetFlow(
                "configureWidgetForView windowAlreadySet viewId=$viewId window=${updateWindowOverride.name}"
            )
        }
    }
    context.bindWidgetToView(appWidgetId, viewId)
    HealthDataWidgetScheduler.scheduleForWidget(context, appWidgetId)
    if (refreshImmediately) {
        HealthDataWidgetUpdater.updateWidget(context, appWidgetId)
        logWidgetFlow("configureWidgetForView refreshed appWidgetId=$appWidgetId")
    } else {
        logWidgetFlow("configureWidgetForView skippedRefresh appWidgetId=$appWidgetId")
    }
    logWidgetFlow("configureWidgetForView success appWidgetId=$appWidgetId viewId=$viewId")
    return true
}
