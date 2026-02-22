package com.monkopedia.healthdisconnect

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import java.lang.reflect.Field
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataWidgetSchedulerTest {
    private lateinit var app: Application
    private lateinit var appDb: AppDatabase
    private lateinit var instanceField: Field
    private var originalDatabase: AppDatabase? = null

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        appDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        originalDatabase = instanceField.get(null) as AppDatabase?
        instanceField.set(null, appDb)
        app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.allPendingJobs.forEach { scheduler.cancel(it.id) }
    }

    @After
    fun tearDown() = runBlocking {
        app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.allPendingJobs.forEach { scheduler.cancel(it.id) }
        appDb.clearAllTables()
        instanceField.set(null, originalDatabase)
        appDb.close()
    }

    @Test
    fun scheduleForWidget_usesConfiguredViewWindow() = runBlocking {
        val viewId = 5
        val widgetId = 41
        appDb.dataViewInfoDao().insert(DataViewInfoEntity(id = viewId, name = "Weight", ordering = 1))
        val view = DataView(
            id = viewId,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            chartSettings = ChartSettings(widgetUpdateWindow = WidgetUpdateWindow.HOURS_6)
        )
        appDb.dataViewDao().insert(encodeDataViewEntity(view))
        app.bindWidgetToView(widgetId, viewId)

        HealthDataWidgetScheduler.scheduleForWidget(app, widgetId)

        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = HealthDataWidgetContract.JOB_ID_BASE + widgetId
        val jobInfo = scheduler.allPendingJobs.firstOrNull { it.id == jobId }
        assertNotNull(jobInfo)
        assertEquals(
            WidgetUpdateWindow.HOURS_6.intervalMillis(),
            jobInfo!!.intervalMillis
        )
        assertEquals(widgetId, jobInfo.extras.getInt(HealthDataWidgetContract.EXTRA_WIDGET_ID))
    }

    @Test
    fun cancelWidgetJob_removesPendingJob() = runBlocking {
        val viewId = 9
        val widgetId = 73
        appDb.dataViewInfoDao().insert(DataViewInfoEntity(id = viewId, name = "Steps", ordering = 1))
        val view = DataView(
            id = viewId,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            chartSettings = ChartSettings(widgetUpdateWindow = WidgetUpdateWindow.HOURS_12)
        )
        appDb.dataViewDao().insert(encodeDataViewEntity(view))
        app.bindWidgetToView(widgetId, viewId)
        HealthDataWidgetScheduler.scheduleForWidget(app, widgetId)

        HealthDataWidgetScheduler.cancelWidgetJob(app, widgetId)

        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = HealthDataWidgetContract.JOB_ID_BASE + widgetId
        val exists = scheduler.allPendingJobs.any { it.id == jobId }
        assertEquals(false, exists)
    }
}
