package com.monkopedia.healthdisconnect.model

import kotlinx.serialization.Serializable

@Serializable
data class DataViewInfoList(
    val dataViews: Map<Int, DataViewInfo>
)

@Serializable
data class DataViewInfo(val id: Int, val name: String)
