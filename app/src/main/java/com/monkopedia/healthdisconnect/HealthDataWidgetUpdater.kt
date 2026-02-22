package com.monkopedia.healthdisconnect

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.room.AppDatabase
import java.util.Locale

object HealthDataWidgetUpdater {
    suspend fun updateAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, HealthDataWidgetProvider::class.java)
        )
        logWidgetFlow("HealthDataWidgetUpdater.updateAllWidgets count=${ids.size}")
        ids.forEach { appWidgetId ->
            updateWidget(context, appWidgetId, manager)
        }
    }

    suspend fun updateWidget(
        context: Context,
        appWidgetId: Int,
        manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    ) {
        val viewId = context.widgetViewId(appWidgetId)
        if (viewId == null) {
            val hasViews = AppDatabase.getInstance(context).dataViewInfoDao().count() > 0
            logWidgetFlow(
                "HealthDataWidgetUpdater.updateWidget unbound appWidgetId=$appWidgetId hasViews=$hasViews"
            )
            manager.updateAppWidget(
                appWidgetId,
                placeholderViews(
                    context = context,
                    title = context.getString(R.string.app_name),
                    message = context.getString(
                        if (hasViews) {
                            R.string.widget_needs_configuration
                        } else {
                            R.string.widget_no_views_available
                        }
                    ),
                    clickIntent = if (hasViews) {
                        configureWidgetPendingIntent(context, appWidgetId)
                    } else {
                        openAppPendingIntent(context)
                    }
                )
            )
            return
        }

        val db = AppDatabase.getInstance(context)
        val info = db.dataViewInfoDao().getById(viewId)
        val entity = db.dataViewDao().getById(viewId)
        if (info == null || entity == null) {
            logWidgetFlow(
                "HealthDataWidgetUpdater.updateWidget missingViewData appWidgetId=$appWidgetId boundViewId=$viewId infoNull=${info == null} entityNull=${entity == null}"
            )
            manager.updateAppWidget(
                appWidgetId,
                placeholderViews(
                    context = context,
                    title = context.getString(R.string.app_name),
                    message = context.getString(R.string.widget_linked_view_missing),
                    clickIntent = openAppPendingIntent(context)
                )
            )
            return
        }

        val view = decodeDataViewEntity(entity)
        val app = context.applicationContext as Application
        val healthDataModel = HealthDataModel(app, autoRefreshMetrics = false)
        val seriesList = runCatching {
            healthDataModel.loadAggregatedSeriesForExport(view)
        }.getOrElse { exception ->
            logWidgetFlowError(
                "HealthDataWidgetUpdater.updateWidget aggregationFailed appWidgetId=$appWidgetId viewId=${view.id}",
                exception
            )
            emptyList()
        }
        if (seriesList.isEmpty()) {
            logWidgetFlow(
                "HealthDataWidgetUpdater.updateWidget emptySeries appWidgetId=$appWidgetId viewId=${view.id}"
            )
            manager.updateAppWidget(
                appWidgetId,
                placeholderViews(
                    context = context,
                    title = info.name,
                    message = context.getString(R.string.widget_no_graph_data),
                    clickIntent = deepLinkPendingIntent(context, view.id)
                )
            )
            return
        }
        logWidgetFlow(
            "HealthDataWidgetUpdater.updateWidget renderGraph appWidgetId=$appWidgetId viewId=${view.id} seriesCount=${seriesList.size}"
        )

        val graphBitmap = renderWidgetGraphBitmap(
            title = info.name,
            seriesList = seriesList,
            settings = view.chartSettings,
            theme = GraphShareTheme.LIGHT
        )
        val summaryText = buildWidgetSummaryText(context, seriesList)
        val remoteViews = RemoteViews(context.packageName, R.layout.health_graph_widget).apply {
            setTextViewText(R.id.widget_title, info.name)
            setViewVisibility(R.id.widget_empty, View.GONE)
            setViewVisibility(R.id.widget_graph, View.VISIBLE)
            setImageViewBitmap(R.id.widget_graph, graphBitmap)
            setTextViewText(
                R.id.widget_summary,
                if (summaryText.isBlank()) {
                    context.getString(R.string.widget_no_labels_enabled)
                } else {
                    summaryText
                }
            )
            setOnClickPendingIntent(
                R.id.widget_container,
                deepLinkPendingIntent(context, view.id)
            )
        }
        manager.updateAppWidget(appWidgetId, remoteViews)
        logWidgetFlow(
            "HealthDataWidgetUpdater.updateWidget success appWidgetId=$appWidgetId viewId=${view.id}"
        )
    }

    internal fun buildWidgetSummaryText(
        context: Context,
        seriesList: List<HealthDataModel.MetricSeries>
    ): String {
        return buildList {
            seriesList.forEach { series ->
                if (series.showMaxLabel) {
                    add(
                        context.getString(
                            R.string.widget_max_format,
                            series.label,
                            formatAxisValue(series.peakValueInWindow),
                            series.unit?.let { " $it" } ?: ""
                        )
                    )
                }
                if (series.showMinLabel) {
                    add(
                        context.getString(
                            R.string.widget_min_format,
                            series.label,
                            formatAxisValue(series.minValueInWindow),
                            series.unit?.let { " $it" } ?: ""
                        )
                    )
                }
            }
        }.joinToString(separator = "\n")
    }

    private fun placeholderViews(
        context: Context,
        title: String,
        message: String,
        clickIntent: PendingIntent
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.health_graph_widget).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_empty, message)
            setTextViewText(R.id.widget_summary, "")
            setViewVisibility(R.id.widget_empty, View.VISIBLE)
            setViewVisibility(R.id.widget_graph, View.GONE)
            setOnClickPendingIntent(R.id.widget_container, clickIntent)
        }
    }

    private fun deepLinkPendingIntent(context: Context, viewId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(HealthDataWidgetContract.EXTRA_WIDGET_VIEW_ID, viewId)
        }
        return PendingIntent.getActivity(
            context,
            deepLinkRequestCode(viewId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            -1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun configureWidgetPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, HealthDataWidgetConfigureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deepLinkRequestCode(viewId: Int): Int {
        return Math.abs(("view:$viewId").hashCode())
    }
}
