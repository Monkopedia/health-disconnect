package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.records.Record
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.monkopedia.healthdisconnect.datastore.DataViewListSerializer
import com.monkopedia.healthdisconnect.datastore.DataViewSerializer
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewDao
import com.monkopedia.healthdisconnect.room.DataViewInfoDao
import kotlin.reflect.KClass
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.monkopedia.healthdisconnect.room.DataViewEntity
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import kotlin.coroutines.cancellation.CancellationException

class DataViewAdapterViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle,
    private val appDatabase: AppDatabase = AppDatabase.getInstance(app),
    private val dataViewDao: DataViewDao = appDatabase.dataViewDao(),
    private val dataViewInfoDao: DataViewInfoDao = appDatabase.dataViewInfoDao(),
    private val runLegacyMigrationOnInit: Boolean = true
) : AndroidViewModel(app) {

    private val context = getApplication<Application>()
    // Durable store — see StorageJson: reads must tolerate rows written by a different build.
    private val json = StorageJson

    // Expose DataViewInfoList as Flow to match existing consumers
    val dataViews: Flow<DataViewInfoList?> = dataViewInfoDao.allOrdered().map { list ->
        val map = list.associate { it.id to com.monkopedia.healthdisconnect.model.DataViewInfo(it.id, it.name) }
        val ordering = list.map { it.id }
        DataViewInfoList(map, ordering)
    }

    private val flows = MutableStateFlow(mapOf<Int, Flow<DataView>>())

    init {
        // Persist last view snapshot in SavedStateHandle as JSON to keep behavior
        viewModelScope.launch {
            dataViews.collect { list ->
                savedStateHandle["lastView"] = list?.let { json.encodeToString(it) }
            }
        }
        if (runLegacyMigrationOnInit) {
            viewModelScope.launch {
                migrateLegacyDataStoreIfNeeded()
                migrateLegacyViewNamesIfNeeded()
            }
        }
    }

    internal suspend fun migrateLegacyDataStoreIfNeededForTest() {
        migrateLegacyDataStoreIfNeeded()
    }

    internal suspend fun migrateLegacyViewNamesIfNeededForTest() {
        migrateLegacyViewNamesIfNeeded()
    }

    /**
     * One-time rename of legacy auto-named views to their metric label. Pre-picker, a view
     * was named after its record type (e.g. "Nutrition") while targeting the default metric;
     * new views are named after the metric (e.g. "Nutrition Energy"). Rename only views that
     * still carry the auto-generated type name and have a single selection — never a view the
     * user renamed. Name-only; selections are untouched. Idempotent via a completion flag.
     */
    private suspend fun migrateLegacyViewNamesIfNeeded() {
        val done = context.migrationStateDataStore.data.first()[legacyViewNameMigrationKey] ?: false
        if (done) {
            return
        }
        try {
            val extractor = DefaultHealthRecordMeasurementExtractor()
            dataViewInfoDao.allOrderedSnapshot().forEach { info ->
                val entity = dataViewDao.getById(info.id) ?: return@forEach
                val selection = decodeDataViewEntity(entity, json).records.singleOrNull()
                    ?: return@forEach
                val cls = PermissionsViewModel.classForFqn(selection.fqn) ?: return@forEach
                val typeName = PermissionsViewModel.RECORD_NAMES[cls] ?: return@forEach
                // Only views that still carry the auto-generated type name.
                if (info.name != typeName) return@forEach
                val metricLabel = extractor.metricLabel(cls, selection.metricKey) ?: return@forEach
                if (metricLabel != info.name) {
                    dataViewInfoDao.updateName(info.id, metricLabel)
                }
            }
            context.migrationStateDataStore.edit { prefs ->
                prefs[legacyViewNameMigrationKey] = true
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            // Same reason as the DataStore migration below: this path decodes saved views
            // (decodeDataViewEntity above), so the exception message can embed the view's JSON.
            // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
            Log.w(
                TAG,
                "Legacy view-name migration failed (${exception.errorLabel()}); " +
                    "will retry on next launch"
            )
        }
    }

    private suspend fun migrateLegacyDataStoreIfNeeded() {
        val migrationComplete = context.migrationStateDataStore.data.first()[legacyMigrationCompleteKey]
            ?: false
        if (migrationComplete) {
            return
        }
        try {
            appDatabase.withTransaction {
                val infoCount = dataViewInfoDao.count()
                val viewCount = dataViewInfoDao.viewCount()
                if (infoCount == 0 && viewCount == 0) {
                    migrateLegacyDataStoreIntoRoom()
                } else {
                    val infoIds = dataViewInfoDao.allOrderedSnapshot().map { it.id }.toSet()
                    val viewIds = dataViewDao.allIdsSnapshot().toSet()
                    if (infoIds != viewIds) {
                        dataViewInfoDao.deleteAll()
                        dataViewDao.deleteAll()
                        migrateLegacyDataStoreIntoRoom()
                    }
                }
            }
            context.migrationStateDataStore.edit { prefs ->
                prefs[legacyMigrationCompleteKey] = true
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            // Deliberately NOT passing the throwable: on this path it is a CorruptionException
            // wrapping a SerializationException whose message embeds the entire legacy DataStore
            // blob — every saved view and every health record type the user tracks. Log.w(tag, msg,
            // tr) emits getStackTraceString, which includes the `Caused by:` message, so passing it
            // would put that whole corpus in logcat. See errorLabel in StorageJson.kt.
            Log.w(
                TAG,
                "Migration from DataStore to Room failed (${exception.errorLabel()}); " +
                    "migration will retry on next launch"
            )
        }
    }

    private suspend fun migrateLegacyDataStoreIntoRoom() {
        val legacyInfo = context.dataViewInfoDataStore.data.first()
        val legacyViews = context.dataViewDataStore.data.first()
        val orderedIds = buildList {
            addAll(legacyInfo.ordering.filter { id -> legacyViews.views.containsKey(id) })
            addAll(legacyViews.views.keys.filter { id -> id !in legacyInfo.ordering }.sorted())
        }
        orderedIds.forEachIndexed { index, id ->
            val view = legacyViews.views[id] ?: return@forEachIndexed
            val infoName = legacyInfo.dataViews[id]?.name
            val name = infoName?.takeIf { it.isNotBlank() } ?: fallbackViewName(view, id)
            dataViewInfoDao.insert(
                DataViewInfoEntity(
                    id = id,
                    name = name,
                    ordering = index + 1
                )
            )
            dataViewDao.insert(encodeDataViewEntity(view, json))
        }
    }

    private fun fallbackViewName(view: DataView, fallbackId: Int): String {
        val firstFqn = view.records.firstOrNull()?.fqn
        val firstClass = PermissionsViewModel.classForFqn(firstFqn)
        return PermissionsViewModel.RECORD_NAMES[firstClass]
            ?: firstClass?.simpleName
            ?: "View $fallbackId"
    }

    suspend fun createView(cls: KClass<out Record>) {
        val name = PermissionsViewModel.recordLabel(cls)
        createView(com.monkopedia.healthdisconnect.model.RecordSelection(cls), name)
    }

    suspend fun createView(
        selection: com.monkopedia.healthdisconnect.model.RecordSelection,
        name: String
    ) {
        val recordsJson = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.monkopedia.healthdisconnect.model.RecordSelection.serializer()), listOf(selection))
        val settingsJson = json.encodeToString(ChartSettings.serializer(), ChartSettings())
        // The id must be read and written in one transaction. Reading MAX(...) outside it makes
        // this a read-modify-write race: CreateViewView launches one createView per tap with no
        // debounce, so overlapping calls all read the same maximum, all mint the same id, and
        // because both DAOs insert with OnConflictStrategy.REPLACE (SQLite DELETE + INSERT) the
        // later inserts silently destroy the earlier saved views. That race is the reachable
        // defect behind issue #82.
        appDatabase.withTransaction {
            val maxOrdering = dataViewInfoDao.maxOrdering() ?: 0
            val maxId = dataViewInfoDao.maxId() ?: 0
            val nextOrder = maxOrdering + 1
            // Mint the id from the id space, not from ordering. Nothing guarantees id == ordering
            // in general: migrateLegacyDataStoreIntoRoom preserves legacy ids while renumbering
            // ordering to index + 1, so MAX(ordering) can in principle sit below MAX(id). (That
            // divergence is defensive rather than observed — the legacy DataStore writer had no
            // delete path, so legacy ids were always contiguous 1..n and the migration produced
            // ordering == id. No shipped build can have written a legacy store that diverges.)
            // Deriving the id from ordering when it does diverge mints an id that already exists,
            // and the REPLACE insert then destroys the existing saved view.
            val newId = maxOf(maxId, maxOrdering) + 1
            dataViewDao.insert(
                DataViewEntity(
                    id = newId,
                    type = com.monkopedia.healthdisconnect.model.ViewType.CHART.name,
                    recordsJson = recordsJson,
                    settingsJson = settingsJson
                )
            )
            dataViewInfoDao.insert(DataViewInfoEntity(newId, name, nextOrder))
        }
    }

    fun dataView(id: Int): Flow<DataView> = flows.updateAndGet {
        it.takeIf { id in it } ?: (it + (id to createDataView(id)))
    }[id]!!

    suspend fun updateView(view: DataView) {
        dataViewDao.insert(encodeDataViewEntity(view, json))
        triggerWidgetRefreshForView(view.id)
    }

    suspend fun renameView(id: Int, name: String) {
        if (name.isBlank()) return
        dataViewInfoDao.updateName(id, name.trim())
        triggerWidgetRefreshForView(id)
    }

    suspend fun deleteView(id: Int) {
        appDatabase.withTransaction {
            dataViewDao.deleteById(id)
            dataViewInfoDao.deleteById(id)
        }
        flows.value = flows.value - id
    }

    private fun createDataView(id: Int): Flow<DataView> =
        dataViewDao.dataView(id).map { entity ->
            decodeDataViewEntity(entity, json)
        }

    private suspend fun triggerWidgetRefreshForView(viewId: Int) {
        val widgetIds = context.widgetIdsForView(viewId)
        if (widgetIds.isEmpty()) return
        HealthDataWidgetScheduler.scheduleForView(context, viewId)
        HealthDataWidgetScheduler.schedulePostUpdateRefresh(
            context = context,
            appWidgetIds = widgetIds.toIntArray(),
            delayMillis = 0L
        )
        logWidgetFlow(
            "DataViewAdapterViewModel.triggerWidgetRefreshForView viewId=$viewId widgets=${widgetIds.joinToString(",")}"
        )
    }

    // Keep DataStore accessors for migration only
    companion object {
        val Context.dataViewInfoDataStore by dataStore("dataInfoStore", DataViewListSerializer)
        val Context.dataViewDataStore by dataStore("dataStore", DataViewSerializer)
        val Context.migrationStateDataStore by preferencesDataStore("migration_state")
        internal val legacyMigrationCompleteKey = booleanPreferencesKey("legacy_migration_complete")
        internal val legacyViewNameMigrationKey = booleanPreferencesKey("legacy_view_name_migration_complete")
        private const val TAG = "DataViewAdapterViewModel"
    }
}
