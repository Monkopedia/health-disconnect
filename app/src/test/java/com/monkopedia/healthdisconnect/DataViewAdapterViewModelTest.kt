package com.monkopedia.healthdisconnect

import android.app.Application
import android.os.Looper
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewDataStore
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewInfoDataStore
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewDao
import com.monkopedia.healthdisconnect.room.DataViewEntity
import com.monkopedia.healthdisconnect.room.DataViewInfoDao
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import java.lang.reflect.Field
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class DataViewAdapterViewModelTest {

    private lateinit var app: Application
    private lateinit var appDb: AppDatabase
    private lateinit var instanceField: Field
    private var originalDatabase: AppDatabase? = null
    private lateinit var infoDao: DataViewInfoDao
    private lateinit var viewDao: DataViewDao

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        appDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        originalDatabase = instanceField.get(null) as AppDatabase?
        instanceField.set(null, appDb)

        infoDao = appDb.dataViewInfoDao()
        viewDao = appDb.dataViewDao()

        runBlocking {
            app.dataViewInfoDataStore.updateData { DataViewInfoList(emptyMap(), emptyList()) }
            app.dataViewDataStore.updateData { DataViewList(emptyMap()) }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.dataViewInfoDataStore.updateData { DataViewInfoList(emptyMap(), emptyList()) }
            app.dataViewDataStore.updateData { DataViewList(emptyMap()) }
            appDb.clearAllTables()
        }
        instanceField.set(null, originalDatabase)
        appDb.close()
    }

    @Test
    fun `createView adds info and view rows`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)

        val list = awaitViews(viewModel) { it.dataViews.size == 1 }
        assertEquals(1, list.ordering.size)
        assertEquals("Weight", list.dataViews[1]?.name)
    }

    @Test
    fun `renameView updates info name`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)
        awaitViews(viewModel) { it.dataViews.size == 1 }

        viewModel.renameView(1, "Resting Weight")
        val renamed = awaitViews(viewModel) { it.dataViews[1]?.name == "Resting Weight" }
        assertEquals("Resting Weight", renamed.dataViews[1]?.name)
    }

    @Test
    fun `deleteView removes info and view rows`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)
        awaitViews(viewModel) { it.dataViews.size == 1 }

        viewModel.deleteView(1)
        val updated = awaitViews(viewModel) { it.dataViews.isEmpty() }
        assertEquals(0, updated.dataViews.size)
        assertEquals(emptyList<Int>(), updated.ordering)
    }

    @Test
    fun `updateView persists record selection and chart settings`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)
        awaitViews(viewModel) { it.dataViews.size == 1 }

        val distanceView = appDataView(distanceRecordSelection())
        viewModel.updateView(
            distanceView
        )

        val updated = withTimeout(5_000) {
            viewModel.dataView(1).first { it.records.first().fqn == DistanceRecord::class.qualifiedName }
        }
        assertEquals(1, updated.records.size)
        assertEquals(DistanceRecord::class.qualifiedName, updated.records.single().fqn)
        assertEquals(TimeWindow.YEAR_1, updated.chartSettings.timeWindow)
    }

    @Test
    fun `corrupt settings json falls back to defaults`() = runBlocking {
        val id = 7
        val invalidSettings = "{not-valid-json}"
        val recordSelection = RecordSelection(WeightRecord::class)
        val recordsJson = Json.encodeToString(ListSerializer(RecordSelection.serializer()), listOf(recordSelection))

        infoDao.insert(DataViewInfoEntity(id, "Weight", 1))
        viewDao.insert(
            DataViewEntity(
                id = id,
                type = ViewType.CHART.name,
                recordsJson = recordsJson,
                settingsJson = invalidSettings
            )
        )

        val viewModel = dataViewAdapterViewModel()
        val view = viewModel.dataView(id).first { it.id == id }

        assertEquals(ChartSettings(), view.chartSettings)
        assertEquals(1, view.records.size)
        assertEquals(recordSelection.fqn, view.records.single().fqn)
    }

    @Ignore("SKIPPED: migration via datastore requires instrumentation timing guarantees")
    @Test
    fun `data store migration creates room rows when empty`() = runBlocking {
        val id = 11
        val migratedInfo = DataViewInfo(id, "Migrated Weight")
        val migratedView = DataView(
            id = id,
            type = ViewType.CHART,
            records = listOf(RecordSelection(WeightRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.YEAR_1)
        )

        app.dataViewInfoDataStore.updateData {
            DataViewInfoList(dataViews = mapOf(id to migratedInfo), ordering = listOf(id))
        }
        app.dataViewDataStore.updateData { DataViewList(views = mapOf(id to migratedView)) }

        val viewModel = dataViewAdapterViewModel()
        val list = awaitViews(viewModel) { it.ordering.isNotEmpty() }

        val view = viewModel.dataView(id).first { it.id == id }

        assertEquals("Migrated Weight", list.dataViews[id]?.name)
        assertEquals(1, list.ordering.size)
        assertEquals(1, view.records.size)
        assertEquals(WeightRecord::class.qualifiedName, view.records.single().fqn)
        assertEquals(ChartSettings(timeWindow = TimeWindow.YEAR_1), view.chartSettings)
    }

    private suspend fun awaitViews(
        viewModel: DataViewAdapterViewModel,
        predicate: (DataViewInfoList) -> Boolean
    ): DataViewInfoList {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ShadowLooper.shadowMainLooper().runUntilEmpty()
        return withTimeout(5_000) {
            viewModel.dataViews.filterNotNull().first(predicate)
        }
    }

    private fun appDataView(selection: RecordSelection): com.monkopedia.healthdisconnect.model.DataView {
        return com.monkopedia.healthdisconnect.model.DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.YEAR_1)
        )
    }

    private fun distanceRecordSelection(): RecordSelection {
        return RecordSelection(DistanceRecord::class)
    }

    private fun dataViewAdapterViewModel(): DataViewAdapterViewModel {
        return DataViewAdapterViewModel(
            app = app,
            savedStateHandle = SavedStateHandle(),
            dataViewDao = viewDao,
            dataViewInfoDao = infoDao
        )
    }
}
