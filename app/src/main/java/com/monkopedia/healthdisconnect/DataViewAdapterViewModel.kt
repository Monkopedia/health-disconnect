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
    private val json = Json

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
            Log.w(
                TAG,
                "Legacy view-name migration failed; will retry on next launch",
                exception
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
            Log.w(
                TAG,
                "Migration from DataStore to Room failed; migration will retry on next launch",
                exception
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
        val maxOrdering = dataViewInfoDao.maxOrdering() ?: 0
        val nextOrder = maxOrdering + 1
        val newId = nextOrder
        val recordsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.monkopedia.healthdisconnect.model.RecordSelection.serializer()), listOf(selection))
        val settingsJson = Json.encodeToString(ChartSettings.serializer(), ChartSettings())
        appDatabase.withTransaction {
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
