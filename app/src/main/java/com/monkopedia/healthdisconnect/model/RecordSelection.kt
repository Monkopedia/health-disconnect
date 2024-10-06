package com.monkopedia.healthdisconnect.model

import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data class RecordSelection(val fqn: String) {
    constructor(fqn: KClass<out Record>) : this(fqn.qualifiedName ?: error("Class missing fqn"))
}
