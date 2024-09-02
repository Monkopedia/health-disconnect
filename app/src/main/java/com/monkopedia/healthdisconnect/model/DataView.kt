package com.monkopedia.healthdisconnect.model

import kotlinx.serialization.Serializable

@Serializable
data class DataView(
    val id: Int = 0,
    val type: ViewType
)

@Serializable
enum class ViewType {
    CHART
}

@Serializable
data class DataViewList(
    val views: Map<Int, DataView>
)
