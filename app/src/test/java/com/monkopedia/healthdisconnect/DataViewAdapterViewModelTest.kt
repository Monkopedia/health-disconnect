package com.monkopedia.healthdisconnect

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.os.Looper
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewDataStore
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.dataViewInfoDataStore
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel.Companion.migrationStateDataStore
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
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
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
            app.migrationStateDataStore.edit { it.clear() }
            app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
        }
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.allPendingJobs.forEach { scheduler.cancel(it.id) }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.dataViewInfoDataStore.updateData { DataViewInfoList(emptyMap(), emptyList()) }
            app.dataViewDataStore.updateData { DataViewList(emptyMap()) }
            app.migrationStateDataStore.edit { it.clear() }
            app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
            appDb.clearAllTables()
        }
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.allPendingJobs.forEach { scheduler.cancel(it.id) }
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
    fun `createView rolls back when info insert fails`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        appDb.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_data_view_info_insert
            BEFORE INSERT ON data_view_info
            WHEN NEW.id = 1
            BEGIN
                SELECT RAISE(FAIL, 'fail info insert');
            END
            """.trimIndent()
        )

        try {
            assertThrows(Exception::class.java) {
                runBlocking { viewModel.createView(WeightRecord::class) }
            }

            assertEquals(0, infoDao.count())
            assertEquals(0, countDataViews())
        } finally {
            appDb.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS fail_data_view_info_insert"
            )
        }
    }

    @Test
    fun `deleteView rolls back when info delete fails`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)
        assertEquals(1, infoDao.count())
        assertEquals(1, countDataViews())

        appDb.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_data_view_info_delete
            BEFORE DELETE ON data_view_info
            WHEN OLD.id = 1
            BEGIN
                SELECT RAISE(FAIL, 'fail info delete');
            END
            """.trimIndent()
        )

        try {
            assertThrows(Exception::class.java) {
                runBlocking { viewModel.deleteView(1) }
            }

            assertEquals(1, infoDao.count())
            assertEquals(1, countDataViews())
        } finally {
            appDb.openHelper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS fail_data_view_info_delete"
            )
        }
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
    fun `updateView schedules widget update jobs for bound widgets`() = runBlocking {
        val viewModel = dataViewAdapterViewModel()
        viewModel.createView(WeightRecord::class)
        awaitViews(viewModel) { it.dataViews.size == 1 }

        val widgetId = 73
        app.bindWidgetToView(widgetId, 1)

        viewModel.updateView(appDataView(distanceRecordSelection()))

        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val periodicJobId = HealthDataWidgetContract.JOB_ID_BASE + widgetId
        val oneShotJobId = periodicJobId xor 0x40000000

        assertTrue(
            scheduler.allPendingJobs.any { job ->
                job.id == periodicJobId &&
                    job.isPeriodic &&
                    job.extras.getInt(HealthDataWidgetContract.EXTRA_WIDGET_ID) == widgetId
            }
        )
        assertTrue(
            scheduler.allPendingJobs.any { job ->
                job.id == oneShotJobId &&
                    !job.isPeriodic &&
                    job.extras.getInt(HealthDataWidgetContract.EXTRA_WIDGET_ID) == widgetId
            }
        )
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

    @Test
    fun `legacy migration creates room rows and marks completion`() = runBlocking {
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
        app.migrationStateDataStore.edit { it.clear() }

        val viewModel = dataViewAdapterViewModel(runLegacyMigrationOnInit = false)
        viewModel.migrateLegacyDataStoreIfNeededForTest()
        val list = awaitViews(viewModel) { it.ordering.isNotEmpty() }

        val view = viewModel.dataView(id).first { it.id == id }

        assertEquals("Migrated Weight", list.dataViews[id]?.name)
        assertEquals(1, list.ordering.size)
        assertEquals(1, view.records.size)
        assertEquals(WeightRecord::class.qualifiedName, view.records.single().fqn)
        assertEquals(ChartSettings(timeWindow = TimeWindow.YEAR_1), view.chartSettings)
        assertTrue(isLegacyMigrationComplete())
    }

    @Test
    fun `legacy migration repairs mismatched room rows before completion`() = runBlocking {
        val id = 22
        val migratedInfo = DataViewInfo(id, "Migrated Distance")
        val migratedView = DataView(
            id = id,
            type = ViewType.CHART,
            records = listOf(RecordSelection(DistanceRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_90)
        )

        app.dataViewInfoDataStore.updateData {
            DataViewInfoList(dataViews = mapOf(id to migratedInfo), ordering = listOf(id))
        }
        app.dataViewDataStore.updateData { DataViewList(views = mapOf(id to migratedView)) }
        app.migrationStateDataStore.edit { it.clear() }

        infoDao.insert(DataViewInfoEntity(id = 999, name = "partial", ordering = 1))
        assertEquals(1, infoDao.count())
        assertEquals(0, countDataViews())

        val viewModel = dataViewAdapterViewModel(runLegacyMigrationOnInit = false)
        viewModel.migrateLegacyDataStoreIfNeededForTest()

        val repairedList = awaitViews(viewModel) { it.ordering == listOf(id) }
        val repairedView = viewModel.dataView(id).first { it.id == id }

        assertEquals("Migrated Distance", repairedList.dataViews[id]?.name)
        assertEquals(1, infoDao.count())
        assertEquals(1, countDataViews())
        assertEquals(DistanceRecord::class.qualifiedName, repairedView.records.single().fqn)
        assertEquals(TimeWindow.DAYS_90, repairedView.chartSettings.timeWindow)
        assertTrue(isLegacyMigrationComplete())
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

    private fun countDataViews(): Int {
        appDb.openHelper.readableDatabase.query("SELECT COUNT(*) FROM data_views").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun dataViewAdapterViewModel(): DataViewAdapterViewModel {
        return DataViewAdapterViewModel(
            app = app,
            savedStateHandle = SavedStateHandle(),
            appDatabase = appDb,
            dataViewDao = viewDao,
            dataViewInfoDao = infoDao
        )
    }

    private fun dataViewAdapterViewModel(runLegacyMigrationOnInit: Boolean): DataViewAdapterViewModel {
        return DataViewAdapterViewModel(
            app = app,
            savedStateHandle = SavedStateHandle(),
            appDatabase = appDb,
            dataViewDao = viewDao,
            dataViewInfoDao = infoDao,
            runLegacyMigrationOnInit = runLegacyMigrationOnInit
        )
    }

    private suspend fun isLegacyMigrationComplete(): Boolean {
        return app.migrationStateDataStore.data.first()[DataViewAdapterViewModel.legacyMigrationCompleteKey]
            ?: false
    }
}
