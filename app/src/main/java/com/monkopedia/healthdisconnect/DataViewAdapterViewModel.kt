package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.datastore.dataStore
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
import com.monkopedia.healthdisconnect.room.DataViewDao
import com.monkopedia.healthdisconnect.room.DataViewInfoDao
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
    private val dataViewDao: DataViewDao,
    private val dataViewInfoDao: DataViewInfoDao
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
        // One-time migration from DataStore to Room if DB is empty
        viewModelScope.launch {
            try {
                val hasData = (dataViewInfoDao.count() > 0) || (dataViewInfoDao.viewCount() > 0)
                if (!hasData) {
                    val dsInfo = context.dataViewInfoDataStore.data.first()
                    val dsViews = context.dataViewDataStore.data.first()
                    dsInfo.ordering.forEachIndexed { index, id ->
                        val info = dsInfo.dataViews[id]
                        if (info != null) {
                            dataViewInfoDao.insert(
                                com.monkopedia.healthdisconnect.room.DataViewInfoEntity(
                                    id = info.id,
                                    name = info.name,
                                    ordering = index + 1
                                )
                            )
                        }
                        val view = dsViews.views[id]
                        if (view != null) {
                            val recJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.monkopedia.healthdisconnect.model.RecordSelection.serializer()), view.records)
                            val settingsJson =
                                Json.encodeToString(ChartSettings.serializer(), view.chartSettings)
                            dataViewDao.insert(
                                com.monkopedia.healthdisconnect.room.DataViewEntity(
                                    id = view.id,
                                    type = view.type.name,
                                    recordsJson = recJson,
                                    settingsJson = settingsJson
                                )
                            )
                        }
                    }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(
                    TAG,
                    "Migration from DataStore to Room failed; starting with fresh Room state",
                    exception
                )
            }
        }
    }

    suspend fun createView(cls: KClass<out Record>) {
        val maxOrdering = dataViewInfoDao.maxOrdering() ?: 0
        val nextOrder = maxOrdering + 1
        val newId = nextOrder
        val name = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: "Record"
        val recordsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.monkopedia.healthdisconnect.model.RecordSelection.serializer()), listOf(com.monkopedia.healthdisconnect.model.RecordSelection(cls)))
        val settingsJson = Json.encodeToString(ChartSettings.serializer(), ChartSettings())
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

    fun dataView(id: Int): Flow<DataView> = flows.updateAndGet {
        it.takeIf { id in it } ?: (it + (id to createDataView(id)))
    }[id]!!

    suspend fun updateView(view: DataView) {
        val recordsJson = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.monkopedia.healthdisconnect.model.RecordSelection.serializer()
            ),
            view.records
        )
        val settingsJson = Json.encodeToString(ChartSettings.serializer(), view.chartSettings)
        dataViewDao.insert(
            DataViewEntity(
                id = view.id,
                type = view.type.name,
                recordsJson = recordsJson,
                settingsJson = settingsJson
            )
        )
    }

    suspend fun renameView(id: Int, name: String) {
        if (name.isBlank()) return
        dataViewInfoDao.updateName(id, name.trim())
    }

    suspend fun deleteView(id: Int) {
        dataViewDao.deleteById(id)
        dataViewInfoDao.deleteById(id)
        flows.value = flows.value - id
    }

    private fun createDataView(id: Int): Flow<DataView> =
        dataViewDao.dataView(id).map { entity ->
            val records = Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    com.monkopedia.healthdisconnect.model.RecordSelection.serializer()
                ),
                entity.recordsJson
            )
            val settings = try {
                Json.decodeFromString(ChartSettings.serializer(), entity.settingsJson)
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(
                    TAG,
                    "Failed to parse saved chart settings for data view ${entity.id}",
                    exception
                )
                ChartSettings()
            }
            DataView(
                id = entity.id,
                type = com.monkopedia.healthdisconnect.model.ViewType.valueOf(entity.type),
                records = records,
                chartSettings = settings
            )
        }

    // Keep DataStore accessors for migration only
    companion object {
        val Context.dataViewInfoDataStore by dataStore("dataInfoStore", DataViewListSerializer)
        val Context.dataViewDataStore by dataStore("dataStore", DataViewSerializer)
        private const val TAG = "DataViewAdapterViewModel"
    }
}
