package com.monkopedia.healthdisconnect.model

import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data class RecordSelection(
    val fqn: String,
    val metricSettings: MetricChartSettings? = null,
    val metricKey: String? = null
) {
    constructor(fqn: KClass<out Record>) : this(
        fqn.qualifiedName ?: error("Class missing fqn"),
        MetricChartSettings(),
        null
    )

    fun selectionKey(): String {
        return "$fqn#${metricKey.orEmpty()}"
    }
}
