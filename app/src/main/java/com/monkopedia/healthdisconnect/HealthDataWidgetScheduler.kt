package com.monkopedia.healthdisconnect

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow

object HealthDataWidgetScheduler {
    fun cancelWidgetJob(context: Context, appWidgetId: Int) {
        val scheduler = context.jobScheduler() ?: return
        scheduler.cancel(jobIdFor(appWidgetId))
        scheduler.cancel(oneShotJobIdFor(appWidgetId))
    }

    fun cancelWidgetJobs(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { cancelWidgetJob(context, it) }
    }

    suspend fun scheduleForWidget(context: Context, appWidgetId: Int) {
        val viewId = context.widgetViewId(appWidgetId)
        if (viewId == null) {
            cancelWidgetJob(context, appWidgetId)
            return
        }
        val db = com.monkopedia.healthdisconnect.room.AppDatabase.getInstance(context)
        val entity = db.dataViewDao().getById(viewId)
        if (entity == null) {
            cancelWidgetJob(context, appWidgetId)
            return
        }
        val view = decodeDataViewEntity(entity)
        scheduleWidgetJob(
            context = context,
            appWidgetId = appWidgetId,
            interval = view.chartSettings.widgetUpdateWindow
        )
    }

    suspend fun scheduleForView(context: Context, viewId: Int) {
        val widgetIds = context.widgetIdsForView(viewId)
        widgetIds.forEach { appWidgetId ->
            scheduleForWidget(context, appWidgetId)
        }
    }

    suspend fun scheduleAll(context: Context) {
        context.widgetBindingsSnapshot().keys.forEach { appWidgetId ->
            scheduleForWidget(context, appWidgetId)
        }
    }

    fun schedulePostUpdateRefresh(
        context: Context,
        appWidgetIds: IntArray,
        delayMillis: Long = 20_000L
    ) {
        if (appWidgetIds.isEmpty()) return
        val scheduler = context.jobScheduler() ?: return
        val serviceComponent = ComponentName(context, HealthDataWidgetUpdateJobService::class.java)
        appWidgetIds.distinct().forEach { appWidgetId ->
            val extras = PersistableBundle().apply {
                putInt(HealthDataWidgetContract.EXTRA_WIDGET_ID, appWidgetId)
            }
            val jobId = oneShotJobIdFor(appWidgetId)
            scheduler.cancel(jobId)
            val latency = delayMillis.coerceAtLeast(0L)
            val jobInfo = JobInfo.Builder(jobId, serviceComponent)
                .setPersisted(false)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setMinimumLatency(latency)
                .setExtras(extras)
                .build()
            scheduler.schedule(jobInfo)
        }
    }

    private fun scheduleWidgetJob(
        context: Context,
        appWidgetId: Int,
        interval: WidgetUpdateWindow
    ) {
        val scheduler = context.jobScheduler() ?: return
        val extras = PersistableBundle().apply {
            putInt(HealthDataWidgetContract.EXTRA_WIDGET_ID, appWidgetId)
        }
        val serviceComponent = ComponentName(context, HealthDataWidgetUpdateJobService::class.java)
        val jobInfo = JobInfo.Builder(jobIdFor(appWidgetId), serviceComponent)
            .setPersisted(false)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPeriodic(interval.intervalMillis())
            .setExtras(extras)
            .build()
        scheduler.schedule(jobInfo)
    }

    private fun Context.jobScheduler(): JobScheduler? {
        return getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
    }

    private fun jobIdFor(appWidgetId: Int): Int {
        return HealthDataWidgetContract.JOB_ID_BASE + appWidgetId
    }

    private fun oneShotJobIdFor(appWidgetId: Int): Int {
        return jobIdFor(appWidgetId) xor 0x40000000
    }
}
