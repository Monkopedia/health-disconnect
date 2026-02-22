package com.monkopedia.healthdisconnect

import android.util.Log

private const val WIDGET_FLOW_TAG = "HealthWidgetFlow"

internal fun logWidgetFlow(message: String) {
    Log.i(WIDGET_FLOW_TAG, message)
}

internal fun logWidgetFlowError(message: String, throwable: Throwable? = null) {
    if (throwable == null) {
        Log.e(WIDGET_FLOW_TAG, message)
    } else {
        Log.e(WIDGET_FLOW_TAG, message, throwable)
    }
}
