package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import java.lang.reflect.Field
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataViewAdapterViewModelTest {

    private lateinit var app: Application
    private lateinit var appDb: AppDatabase
    private lateinit var instanceField: Field
    private var originalDatabase: AppDatabase? = null

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
    }

    @After
    fun tearDown() {
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

    private suspend fun awaitViews(
        viewModel: DataViewAdapterViewModel,
        predicate: (DataViewInfoList) -> Boolean
    ): DataViewInfoList {
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
        return DataViewAdapterViewModel(app, SavedStateHandle())
    }
}
