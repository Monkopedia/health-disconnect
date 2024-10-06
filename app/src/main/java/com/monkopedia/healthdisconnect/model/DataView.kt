package com.monkopedia.healthdisconnect.model

import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data class DataView(
    val id: Int = 0,
    val type: ViewType,
    val records: List<RecordSelection> = emptyList(),
    val alwaysShowEntries: Boolean = false
) {

    constructor(id: Int, fqn: KClass<out Record>) : this(
        id,
        ViewType.CHART,
        listOf(RecordSelection(fqn))
    )
}

@Serializable
enum class ViewType {
    CHART
}

@Serializable
data class DataViewList(val views: Map<Int, DataView>)
