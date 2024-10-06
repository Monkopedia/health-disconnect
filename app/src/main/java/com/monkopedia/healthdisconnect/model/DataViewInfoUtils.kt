package com.monkopedia.healthdisconnect.model

val DataViewInfo?.isConfigValid: Boolean
    get() {
        return this != null && name.isNotBlank()
    }

val DataView?.isConfigValid: Boolean
    get() {
        return this != null && records.isNotEmpty() && records.all { it.isValidConfig }
    }

val RecordSelection.isValidConfig: Boolean
    get() {
        return false
    }
