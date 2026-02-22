package com.monkopedia.healthdisconnect

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.health.connect.client.HealthConnectClient
import com.monkopedia.healthdisconnect.room.AppDatabase
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object HealthDataWidgetUpdater {
    internal data class WidgetSizeInfo(
        val widthDp: Int,
        val heightDp: Int,
        val widthPx: Int,
        val heightPx: Int
    )

    internal data class WidgetLayoutProfile(
        val showSummary: Boolean,
        val summaryMaxLines: Int,
        val titleTextSizeSp: Float,
        val summaryTextSizeSp: Float
    )

    internal data class WidgetGraphRenderSize(
        val widthPx: Int,
        val heightPx: Int
    )

    internal data class WidgetGraphLabels(
        val maxLabel: String?,
        val minLabel: String?,
        val startDateLabel: String?,
        val endDateLabel: String?
    )

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
        val sizeInfo = resolveWidgetSizeInfo(context, manager, appWidgetId)
        val layoutProfile = widgetLayoutProfile(sizeInfo.widthDp, sizeInfo.heightDp)
        logWidgetFlow(
            "HealthDataWidgetUpdater.updateWidget size appWidgetId=$appWidgetId widthDp=${sizeInfo.widthDp} heightDp=${sizeInfo.heightDp} widthPx=${sizeInfo.widthPx} heightPx=${sizeInfo.heightPx} showSummary=${layoutProfile.showSummary} maxLines=${layoutProfile.summaryMaxLines}"
        )
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
                    },
                    layoutProfile = layoutProfile
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
                    clickIntent = openAppPendingIntent(context),
                    layoutProfile = layoutProfile
                )
            )
            return
        }

        val view = decodeDataViewEntity(entity)
        val missingPermissions = missingReadPermissionsForView(context, view)
        if (missingPermissions.isNotEmpty()) {
            logWidgetFlow(
                "HealthDataWidgetUpdater.updateWidget missingPermissions appWidgetId=$appWidgetId viewId=${view.id} missing=${missingPermissions.joinToString(",")}"
            )
            manager.updateAppWidget(
                appWidgetId,
                placeholderViews(
                    context = context,
                    title = info.name,
                    message = context.getString(R.string.widget_permissions_required),
                    clickIntent = openAppPendingIntent(context),
                    layoutProfile = layoutProfile
                )
            )
            // Keep retrying periodically so widgets recover quickly once permissions are re-granted.
            HealthDataWidgetScheduler.schedulePostUpdateRefresh(
                context = context,
                appWidgetIds = intArrayOf(appWidgetId),
                delayMillis = 90_000L
            )
            return
        }
        val app = context.applicationContext as Application
        val healthDataModel = HealthDataModel(app, autoRefreshMetrics = false)
        val seriesList = try {
            healthDataModel.loadAggregatedSeriesForWidget(view)
        } catch (exception: HealthDataPermissionDeniedException) {
            logWidgetFlow(
                "HealthDataWidgetUpdater.updateWidget permissionDeniedFromRead appWidgetId=$appWidgetId viewId=${view.id} denied=${exception.deniedRecordTypes.joinToString(",")}"
            )
            manager.updateAppWidget(
                appWidgetId,
                placeholderViews(
                    context = context,
                    title = info.name,
                    message = context.getString(R.string.widget_permissions_required),
                    clickIntent = openAppPendingIntent(context),
                    layoutProfile = layoutProfile
                )
            )
            HealthDataWidgetScheduler.schedulePostUpdateRefresh(
                context = context,
                appWidgetIds = intArrayOf(appWidgetId),
                delayMillis = 90_000L
            )
            return
        } catch (exception: Exception) {
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
                    clickIntent = deepLinkPendingIntent(context, view.id),
                    layoutProfile = layoutProfile
                )
            )
            return
        }
        logWidgetFlow(
            "HealthDataWidgetUpdater.updateWidget renderGraph appWidgetId=$appWidgetId viewId=${view.id} seriesCount=${seriesList.size}"
        )

        val graphRenderSize = estimateGraphRenderSizePx(
            sizeInfo = sizeInfo,
            layoutProfile = layoutProfile,
            displayMetrics = context.resources.displayMetrics
        )
        logWidgetFlow(
            "HealthDataWidgetUpdater.updateWidget graphRenderSize appWidgetId=$appWidgetId widthPx=${graphRenderSize.widthPx} heightPx=${graphRenderSize.heightPx}"
        )
        val graphBitmap = renderWidgetGraphBitmap(
            title = info.name,
            seriesList = seriesList,
            settings = view.chartSettings,
            theme = GraphShareTheme.DARK,
            width = graphRenderSize.widthPx,
            height = graphRenderSize.heightPx,
            showCornerLabels = false
        )
        val summaryText = buildWidgetSummaryText(context, seriesList)
        val graphLabels = buildWidgetGraphLabels(seriesList)
        val hasTopLabels = !graphLabels.maxLabel.isNullOrBlank() || !graphLabels.minLabel.isNullOrBlank()
        val hasBottomLabels =
            !graphLabels.startDateLabel.isNullOrBlank() || !graphLabels.endDateLabel.isNullOrBlank()
        val remoteViews = RemoteViews(context.packageName, R.layout.health_graph_widget).apply {
            setTextViewText(R.id.widget_title, info.name)
            setTextViewTextSize(
                R.id.widget_title,
                TypedValue.COMPLEX_UNIT_SP,
                layoutProfile.titleTextSizeSp
            )
            setViewVisibility(R.id.widget_empty, View.GONE)
            setViewVisibility(R.id.widget_graph_container, View.VISIBLE)
            setViewVisibility(R.id.widget_graph, View.VISIBLE)
            setViewVisibility(
                R.id.widget_graph_labels_top_row,
                if (hasTopLabels) View.VISIBLE else View.GONE
            )
            setViewVisibility(
                R.id.widget_graph_labels_bottom_row,
                if (hasBottomLabels) View.VISIBLE else View.GONE
            )
            setImageViewBitmap(R.id.widget_graph, graphBitmap)
            applyWidgetGraphLabel(
                id = R.id.widget_graph_label_top_start,
                text = graphLabels.maxLabel
            )
            applyWidgetGraphLabel(
                id = R.id.widget_graph_label_top_end,
                text = graphLabels.minLabel
            )
            applyWidgetGraphLabel(
                id = R.id.widget_graph_label_bottom_start,
                text = graphLabels.startDateLabel
            )
            applyWidgetGraphLabel(
                id = R.id.widget_graph_label_bottom_end,
                text = graphLabels.endDateLabel
            )
            setTextViewTextSize(
                R.id.widget_summary,
                TypedValue.COMPLEX_UNIT_SP,
                layoutProfile.summaryTextSizeSp
            )
            setInt(R.id.widget_summary, "setMaxLines", layoutProfile.summaryMaxLines)
            setViewVisibility(
                R.id.widget_summary,
                if (layoutProfile.showSummary) View.VISIBLE else View.GONE
            )
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

    internal fun buildWidgetGraphLabels(
        seriesList: List<HealthDataModel.MetricSeries>
    ): WidgetGraphLabels {
        val allDates = seriesList
            .flatMap { it.points }
            .map { it.date }
            .distinct()
            .sorted()
        val dateFormatter = if (allDates.firstOrNull()?.year != allDates.lastOrNull()?.year) {
            DateTimeFormatter.ofPattern("MMM yy")
        } else {
            DateTimeFormatter.ofPattern("MMM d")
        }
        val firstDateLabel = allDates.firstOrNull()?.format(dateFormatter)
        val lastDateLabel = allDates.lastOrNull()?.format(dateFormatter)
        if (seriesList.size != 1) {
            return WidgetGraphLabels(
                maxLabel = null,
                minLabel = null,
                startDateLabel = firstDateLabel,
                endDateLabel = lastDateLabel
            )
        }
        val series = seriesList.first()
        val unit = unitSuffix(series.unit)
        return WidgetGraphLabels(
            maxLabel = if (series.showMaxLabel) {
                "Max ${formatAxisValue(series.peakValueInWindow)}$unit"
            } else {
                null
            },
            minLabel = if (series.showMinLabel) {
                "Min ${formatAxisValue(series.minValueInWindow)}$unit"
            } else {
                null
            },
            startDateLabel = firstDateLabel,
            endDateLabel = lastDateLabel
        )
    }

    private fun placeholderViews(
        context: Context,
        title: String,
        message: String,
        clickIntent: PendingIntent,
        layoutProfile: WidgetLayoutProfile
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.health_graph_widget).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewTextSize(
                R.id.widget_title,
                TypedValue.COMPLEX_UNIT_SP,
                layoutProfile.titleTextSizeSp
            )
            setTextViewText(R.id.widget_empty, message)
            setTextViewText(R.id.widget_summary, "")
            setTextViewTextSize(
                R.id.widget_summary,
                TypedValue.COMPLEX_UNIT_SP,
                layoutProfile.summaryTextSizeSp
            )
            setInt(R.id.widget_summary, "setMaxLines", layoutProfile.summaryMaxLines)
            setViewVisibility(R.id.widget_empty, View.VISIBLE)
            setViewVisibility(R.id.widget_graph_labels_top_row, View.GONE)
            setViewVisibility(R.id.widget_graph_container, View.GONE)
            setViewVisibility(R.id.widget_graph_labels_bottom_row, View.GONE)
            setViewVisibility(R.id.widget_graph, View.GONE)
            setViewVisibility(R.id.widget_graph_label_top_start, View.GONE)
            setViewVisibility(R.id.widget_graph_label_top_end, View.GONE)
            setViewVisibility(R.id.widget_graph_label_bottom_start, View.GONE)
            setViewVisibility(R.id.widget_graph_label_bottom_end, View.GONE)
            setViewVisibility(R.id.widget_summary, View.GONE)
            setOnClickPendingIntent(R.id.widget_container, clickIntent)
        }
    }

    private fun RemoteViews.applyWidgetGraphLabel(id: Int, text: String?) {
        if (text.isNullOrBlank()) {
            setViewVisibility(id, View.GONE)
            setTextViewText(id, "")
        } else {
            setTextViewText(id, text)
            setViewVisibility(id, View.VISIBLE)
        }
    }

    internal fun widgetLayoutProfile(widthDp: Int, heightDp: Int): WidgetLayoutProfile {
        val clampedWidth = widthDp.coerceAtLeast(120)
        val clampedHeight = heightDp.coerceAtLeast(80)
        return when {
            clampedHeight <= 110 -> WidgetLayoutProfile(
                showSummary = false,
                summaryMaxLines = 0,
                titleTextSizeSp = 14f,
                summaryTextSizeSp = 11.5f
            )

            clampedHeight <= 145 -> WidgetLayoutProfile(
                showSummary = true,
                summaryMaxLines = 1,
                titleTextSizeSp = if (clampedWidth >= 260) 15f else 14f,
                summaryTextSizeSp = 12f
            )

            clampedHeight <= 200 -> WidgetLayoutProfile(
                showSummary = true,
                summaryMaxLines = 2,
                titleTextSizeSp = if (clampedWidth >= 300) 15.5f else 15f,
                summaryTextSizeSp = 12.5f
            )

            else -> WidgetLayoutProfile(
                showSummary = true,
                summaryMaxLines = 3,
                titleTextSizeSp = if (clampedWidth >= 320) 16f else 15.5f,
                summaryTextSizeSp = 13f
            )
        }
    }

    internal fun estimateGraphRenderSizePx(
        sizeInfo: WidgetSizeInfo,
        layoutProfile: WidgetLayoutProfile,
        displayMetrics: DisplayMetrics
    ): WidgetGraphRenderSize {
        fun dp(dp: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            displayMetrics
        )
        fun sp(sp: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            displayMetrics
        )

        val verticalPadding = dp(8f) * 2f
        val graphTopMargin = dp(4f)
        val titleLineHeight = sp(layoutProfile.titleTextSizeSp) * 1.18f
        val summaryBlockHeight = if (layoutProfile.showSummary) {
            val summaryMarginTop = dp(4f)
            val summaryLineHeight = sp(layoutProfile.summaryTextSizeSp) * 1.18f
            summaryMarginTop + (summaryLineHeight * layoutProfile.summaryMaxLines.coerceAtLeast(1))
        } else {
            0f
        }
        val graphHeightPx = (
            sizeInfo.heightPx -
                verticalPadding -
                graphTopMargin -
                titleLineHeight -
                summaryBlockHeight
            ).roundToInt().coerceAtLeast(dp(42f).roundToInt())
        val graphWidthPx = (sizeInfo.widthPx - dp(2f)).roundToInt().coerceAtLeast(dp(120f).roundToInt())
        val renderScale = when {
            graphWidthPx <= dp(220f).roundToInt() -> 1.08f
            graphWidthPx <= dp(320f).roundToInt() -> 1.04f
            else -> 1.0f
        }
        return WidgetGraphRenderSize(
            widthPx = (graphWidthPx * renderScale).roundToInt().coerceIn(180, 3200),
            heightPx = (graphHeightPx * renderScale).roundToInt().coerceIn(72, 1800)
        )
    }

    private fun resolveWidgetSizeInfo(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int
    ): WidgetSizeInfo {
        val options = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            250
        ).coerceAtLeast(120)
        val heightDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            120
        ).coerceAtLeast(80)
        val density = context.resources.displayMetrics.density
        return WidgetSizeInfo(
            widthDp = widthDp,
            heightDp = heightDp,
            widthPx = (widthDp * density).roundToInt().coerceAtLeast(120),
            heightPx = (heightDp * density).roundToInt().coerceAtLeast(80)
        )
    }

    private suspend fun missingReadPermissionsForView(
        context: Context,
        view: com.monkopedia.healthdisconnect.model.DataView
    ): Set<String> {
        val sdkStatus = HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata")
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return emptySet()
        }
        val classByFqn = PermissionsViewModel.CLASSES.associateBy { it.qualifiedName }
        val requiredPermissions = (view.records.mapNotNull { selection ->
            classByFqn[selection.fqn]?.let { cls ->
                PermissionsViewModel.READ_PERMISSIONS_BY_CLASS[cls]
            }
        }.toSet() + PermissionsViewModel.BACKGROUND_PERMISSION)
        if (requiredPermissions.isEmpty()) {
            return emptySet()
        }
        return runCatching {
            val granted = HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
            requiredPermissions - granted
        }.onFailure { exception ->
            logWidgetFlowError("HealthDataWidgetUpdater.missingReadPermissionsForView failed", exception)
        }.getOrElse {
            emptySet()
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
