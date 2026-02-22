package com.monkopedia.healthdisconnect

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HealthDataWidgetUpdateJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val appWidgetId = params.extras.getInt(HealthDataWidgetContract.EXTRA_WIDGET_ID, -1)
        if (appWidgetId < 0) {
            jobFinished(params, false)
            return false
        }
        serviceScope.launch {
            try {
                HealthDataWidgetUpdater.updateWidget(
                    context = this@HealthDataWidgetUpdateJobService,
                    appWidgetId = appWidgetId
                )
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
