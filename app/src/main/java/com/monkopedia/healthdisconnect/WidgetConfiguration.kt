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
    val db = AppDatabase.getInstance(context)
    val viewEntity = db.dataViewDao().getById(viewId) ?: return false
    if (updateWindowOverride != null) {
        val view = decodeDataViewEntity(viewEntity)
        if (view.chartSettings.widgetUpdateWindow != updateWindowOverride) {
            val updatedView = view.copy(
                chartSettings = view.chartSettings.copy(
                    widgetUpdateWindow = updateWindowOverride
                )
            )
            db.dataViewDao().insert(encodeDataViewEntity(updatedView))
        }
    }
    context.bindWidgetToView(appWidgetId, viewId)
    HealthDataWidgetScheduler.scheduleForWidget(context, appWidgetId)
    if (refreshImmediately) {
        HealthDataWidgetUpdater.updateWidget(context, appWidgetId)
    }
    return true
}
