package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.DistanceRecord
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewEntity
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import androidx.health.connect.client.records.WeightRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataViewAdapterViewModelTest {

    @Test
    fun createViewPersistsInfoAndView() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetPersistence(app)

        val viewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val createdClass = DistanceRecord::class
        viewModel.createView(createdClass)

        val list = withTimeout(2000) {
            viewModel.dataViews.filterNotNull().first { it.ordering.size == 1 }
        }
        assertEquals(listOf(1), list.ordering)
        assertEquals("Distance", list.dataViews[1]?.name)

        val dataView = withTimeout(2000) {
            viewModel.dataView(1).first { it.records.size == 1 }
        }
        assertEquals(1, dataView.id)
        assertEquals(createdClass.qualifiedName, dataView.records.single().fqn)
    }

    @Test
    fun updateViewWritesNewValues() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetPersistence(app)

        val viewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        viewModel.createView(PermissionsViewModel.CLASSES.first())
        val updated = withTimeout(2000) {
            viewModel.dataView(1).filter { it.records.isNotEmpty() }
                .first()
        }

        val nextSettings = updated.copy(
            alwaysShowEntries = true,
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
        viewModel.updateView(nextSettings)

        val stored = withTimeout(2000) {
            viewModel.dataView(1).filter { it.alwaysShowEntries }
                .first { it.chartSettings.timeWindow == TimeWindow.DAYS_30 }
        }
        assertEquals(ChartSettings(timeWindow = TimeWindow.DAYS_30), stored.chartSettings)
        assertEquals(true, stored.alwaysShowEntries)
    }

    @Test
    fun renameAndDeleteViewAffectsBothTables() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetPersistence(app)

        val viewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        viewModel.createView(PermissionsViewModel.CLASSES.first())
        viewModel.renameView(1, "Distance View")
        val renamed = withTimeout(2000) {
            viewModel.dataViews.filterNotNull().first { it.dataViews[1]?.name == "Distance View" }
        }
        assertEquals("Distance View", renamed.dataViews[1]?.name)

        viewModel.deleteView(1)
        val afterDelete = withTimeout(2000) {
            viewModel.dataViews.filterNotNull().first { it.ordering.isEmpty() }
        }
        assertEquals(0, afterDelete.ordering.size)

        val db = AppDatabase.getInstance(app)
        val dbInfoRows = db.dataViewInfoDao().allOrdered().first()
        val dbViewRows = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM data_views").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        assertEquals(0, dbInfoRows.size)
        assertEquals(0, dbViewRows)
    }

    @Test
    fun invalidSettingsJsonFallsBackToDefaults() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetPersistence(app)

        val db = AppDatabase.getInstance(app)
        db.dataViewInfoDao().insert(
            DataViewInfoEntity(
                id = 1,
                name = "Bad Settings View",
                ordering = 1
            )
        )
        db.dataViewDao().insert(
            DataViewEntity(
                id = 1,
                type = com.monkopedia.healthdisconnect.model.ViewType.CHART.name,
                recordsJson = "[{\"fqn\":\"${WeightRecord::class.qualifiedName}\"}]",
                settingsJson = "{not-a-valid-json}"
            )
        )

        val viewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val loaded = withTimeout(2000) {
            viewModel.dataView(1).first { it.chartSettings == ChartSettings() }
        }

        assertEquals(ChartSettings(), loaded.chartSettings)
    }

    private fun resetPersistence(app: Application) = runBlocking {
        val datastoreDir = File(app.filesDir, "datastore")
        datastoreDir.mkdirs()
        File(datastoreDir, "dataInfoStore.preferences_pb").delete()
        File(datastoreDir, "dataStore.preferences_pb").delete()
        AppDatabase.getInstance(app).clearAllTables()
    }
}
