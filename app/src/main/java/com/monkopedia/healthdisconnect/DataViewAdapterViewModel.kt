package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.datastore.dataStore
import androidx.health.connect.client.records.Record
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.datastore.DataViewListSerializer
import com.monkopedia.healthdisconnect.datastore.DataViewSerializer
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
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

class DataViewAdapterViewModel(app: Application, private val savedStateHandle: SavedStateHandle) :
    AndroidViewModel(app) {

    private val context = getApplication<Application>()
    private val json = Json

    // Room database
    private val db by lazy { com.monkopedia.healthdisconnect.room.AppDatabase.getInstance(context) }
    private val infoDao by lazy { db.dataViewInfoDao() }
    private val viewDao by lazy { db.dataViewDao() }

    // Expose DataViewInfoList as Flow to match existing consumers
    val dataViews: Flow<DataViewInfoList?> = infoDao.allOrdered().map { list ->
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
                val hasData = (infoDao.count() > 0) || (infoDao.viewCount() > 0)
                if (!hasData) {
                    val dsInfo = context.dataViewInfoDataStore.data.first()
                    val dsViews = context.dataViewDataStore.data.first()
                    dsInfo.ordering.forEachIndexed { index, id ->
                        val info = dsInfo.dataViews[id]
                        if (info != null) {
                            infoDao.insert(
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
                            viewDao.insert(
                                com.monkopedia.healthdisconnect.room.DataViewEntity(
                                    id = view.id,
                                    type = view.type.name,
                                    recordsJson = recJson,
                                    alwaysShowEntries = view.alwaysShowEntries
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore migration errors; start fresh
            }
        }
    }

    suspend fun createView(cls: KClass<out Record>) {
        val nextOrder = (infoDao.maxOrdering() ?: 0) + 1
        val newId = nextOrder
        val name = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: "Record"
        val recordsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.monkopedia.healthdisconnect.model.RecordSelection.serializer()), listOf(com.monkopedia.healthdisconnect.model.RecordSelection(cls)))
        viewDao.insert(DataViewEntity(newId, com.monkopedia.healthdisconnect.model.ViewType.CHART.name, recordsJson, false))
        infoDao.insert(DataViewInfoEntity(newId, name, nextOrder))
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
        viewDao.insert(
            DataViewEntity(
                id = view.id,
                type = view.type.name,
                recordsJson = recordsJson,
                alwaysShowEntries = view.alwaysShowEntries
            )
        )
    }

    private fun createDataView(id: Int): Flow<DataView> =
        viewDao.dataView(id).map { entity ->
            val records = Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    com.monkopedia.healthdisconnect.model.RecordSelection.serializer()
                ),
                entity.recordsJson
            )
            DataView(
                id = entity.id,
                type = com.monkopedia.healthdisconnect.model.ViewType.valueOf(entity.type),
                records = records,
                alwaysShowEntries = entity.alwaysShowEntries
            )
        }

    // Keep DataStore accessors for migration only
    companion object {
        val Context.dataViewInfoDataStore by dataStore("dataInfoStore", DataViewListSerializer)
        val Context.dataViewDataStore by dataStore("dataStore", DataViewSerializer)
    }
}
