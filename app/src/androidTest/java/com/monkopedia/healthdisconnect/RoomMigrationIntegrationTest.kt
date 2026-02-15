package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.health.connect.client.records.WeightRecord
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewDataStore
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewInfoDataStore
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val legacyInfoFile = "dataInfoStore.preferences_pb"
private const val legacyDataFile = "dataStore.preferences_pb"

@RunWith(AndroidJUnit4::class)
class RoomMigrationIntegrationTest {
    @Test
    fun dataStoreMigrationCopiesLegacyViewsToRoom() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetLegacyAndRoomData(app)
        seedLegacyDataStores(app)

        val adapterViewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val migrationResult = withTimeout(2_000) {
            adapterViewModel.dataViews.filterNotNull().first { it.ordering.isNotEmpty() }
        }

        assertEquals(listOf(7), migrationResult.ordering)
        assertEquals("Migrated Weight View", migrationResult.dataViews[7]?.name)
        assertEquals(listOf("Migrated Weight View"), migrationResult.dataViews.values.map { it.name })
        val migratedView = withTimeout(2_000) {
            adapterViewModel.dataView(7).filterNotNull().first()
        }
        assertEquals(ViewType.CHART, migratedView.type)
        assertEquals(WeightRecord::class.qualifiedName, migratedView.records.single().fqn)
        assertEquals(ChartSettings(), migratedView.chartSettings)
    }

    @Test
    fun malformedLegacyDataLeavesRoomEmptyAndNoCrash() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetLegacyAndRoomData(app)
        val datastoreDir = File(app.filesDir, "datastore")
        datastoreDir.mkdirs()
        File(datastoreDir, legacyInfoFile).writeText("{invalid")
        File(datastoreDir, legacyDataFile).writeText("{invalid")

        val adapterViewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val migrationResult = withTimeout(2_000) {
            adapterViewModel.dataViews.filterNotNull().first { it.ordering.isEmpty() }
        }
        assertEquals(emptyList<Int>(), migrationResult.ordering)
    }

    private fun seedLegacyDataStores(app: Application) {
        runBlocking {
            app.dataViewInfoDataStore.updateData {
                DataViewInfoList(
                    dataViews = mapOf(
                        7 to DataViewInfo(
                            id = 7,
                            name = "Migrated Weight View"
                        )
                    ),
                    ordering = listOf(7)
                )
            }
            app.dataViewDataStore.updateData {
                com.monkopedia.healthdisconnect.model.DataViewList(
                    views = mapOf(
                        7 to DataView(
                            id = 7,
                            type = ViewType.CHART,
                            records = listOf(RecordSelection(WeightRecord::class)),
                            chartSettings = ChartSettings()
                        )
                    )
                )
            }
        }
    }

    private fun resetLegacyAndRoomData(app: Application) {
        val datastoreDir = File(app.filesDir, "datastore")
        File(datastoreDir, legacyInfoFile).delete()
        File(datastoreDir, legacyDataFile).delete()
        runBlocking {
            AppDatabase.getInstance(app).clearAllTables()
        }
    }
}
