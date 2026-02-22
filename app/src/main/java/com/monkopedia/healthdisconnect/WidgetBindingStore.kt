package com.monkopedia.healthdisconnect

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val widgetBindingsJson = stringPreferencesKey("widget_bindings_json")

private val Context.widgetBindingsDataStore by preferencesDataStore("widget_bindings")

private val widgetBindingsJsonCodec = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class WidgetBindingsState(
    val widgetToView: Map<Int, Int> = emptyMap(),
    val pendingRequests: List<PendingWidgetRequest> = emptyList()
)

@Serializable
data class PendingWidgetRequest(
    val viewId: Int,
    val updateWindowName: String? = null
)

private fun Preferences.widgetBindingsStateOrDefault(): WidgetBindingsState {
    val raw = this[widgetBindingsJson] ?: return WidgetBindingsState()
    return runCatching {
        widgetBindingsJsonCodec.decodeFromString(WidgetBindingsState.serializer(), raw)
    }.getOrElse { WidgetBindingsState() }
}

internal fun Preferences.toWidgetBindingsState(): Map<Int, Int> {
    return widgetBindingsStateOrDefault().widgetToView
}

private fun encodeWidgetBindingsState(state: WidgetBindingsState): String {
    return widgetBindingsJsonCodec.encodeToString(
        WidgetBindingsState.serializer(),
        state
    )
}

fun Context.widgetBindingsFlow(): Flow<Map<Int, Int>> {
    return widgetBindingsDataStore.data.map { prefs ->
        prefs.toWidgetBindingsState()
    }
}

suspend fun Context.widgetBindingsSnapshot(): Map<Int, Int> {
    return widgetBindingsFlow().first()
}

suspend fun Context.bindWidgetToView(appWidgetId: Int, viewId: Int) {
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val updated = state.widgetToView.toMutableMap().apply {
            this[appWidgetId] = viewId
        }
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(
            state.copy(widgetToView = updated)
        )
        logWidgetFlow(
            "bindWidgetToView appWidgetId=$appWidgetId viewId=$viewId totalBindings=${updated.size}"
        )
    }
}

suspend fun Context.unbindWidget(appWidgetId: Int) {
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val updated = state.widgetToView.toMutableMap().apply {
            remove(appWidgetId)
        }
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(
            state.copy(widgetToView = updated)
        )
        logWidgetFlow(
            "unbindWidget appWidgetId=$appWidgetId totalBindings=${updated.size}"
        )
    }
}

suspend fun Context.unbindWidgets(appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val updated = state.widgetToView.toMutableMap()
        appWidgetIds.forEach { updated.remove(it) }
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(
            state.copy(widgetToView = updated)
        )
        logWidgetFlow(
            "unbindWidgets count=${appWidgetIds.size} totalBindings=${updated.size}"
        )
    }
}

suspend fun Context.widgetViewId(appWidgetId: Int): Int? {
    return widgetBindingsSnapshot()[appWidgetId]
}

suspend fun Context.widgetIdsForView(viewId: Int): List<Int> {
    return widgetBindingsSnapshot()
        .entries
        .filter { it.value == viewId }
        .map { it.key }
}

suspend fun Context.hasWidgetForView(viewId: Int): Boolean {
    return widgetBindingsSnapshot().values.any { it == viewId }
}

suspend fun Context.enqueuePendingWidgetRequest(
    viewId: Int,
    updateWindowName: String?
) {
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val updated = state.pendingRequests + PendingWidgetRequest(
            viewId = viewId,
            updateWindowName = updateWindowName
        )
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(
            state.copy(pendingRequests = updated)
        )
        logWidgetFlow(
            "enqueuePendingWidgetRequest viewId=$viewId updateWindow=$updateWindowName pendingCount=${updated.size}"
        )
    }
}

suspend fun Context.consumePendingWidgetRequest(): PendingWidgetRequest? {
    var consumed: PendingWidgetRequest? = null
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val first = state.pendingRequests.firstOrNull()
        consumed = first
        if (first != null) {
            prefs[widgetBindingsJson] = encodeWidgetBindingsState(
                state.copy(pendingRequests = state.pendingRequests.drop(1))
            )
            logWidgetFlow(
                "consumePendingWidgetRequest viewId=${first.viewId} updateWindow=${first.updateWindowName} remaining=${state.pendingRequests.size - 1}"
            )
        } else {
            logWidgetFlow("consumePendingWidgetRequest noneAvailable")
        }
    }
    return consumed
}

suspend fun Context.clearPendingWidgetRequests() {
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        if (state.pendingRequests.isNotEmpty()) {
            prefs[widgetBindingsJson] = encodeWidgetBindingsState(
                state.copy(pendingRequests = emptyList())
            )
            logWidgetFlow(
                "clearPendingWidgetRequests cleared=${state.pendingRequests.size}"
            )
        }
    }
}

suspend fun Context.pendingWidgetRequestCount(): Int {
    return widgetBindingsDataStore.data.first().widgetBindingsStateOrDefault().pendingRequests.size
}

suspend fun Context.consumeMatchingPendingWidgetRequest(
    viewId: Int,
    updateWindowName: String?
): Boolean {
    var removed = false
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val index = state.pendingRequests.indexOfFirst { request ->
            request.viewId == viewId && request.updateWindowName == updateWindowName
        }
        if (index >= 0) {
            val updated = state.pendingRequests.toMutableList().apply {
                removeAt(index)
            }
            prefs[widgetBindingsJson] = encodeWidgetBindingsState(
                state.copy(pendingRequests = updated)
            )
            removed = true
            logWidgetFlow(
                "consumeMatchingPendingWidgetRequest matched viewId=$viewId updateWindow=$updateWindowName remaining=${updated.size}"
            )
        } else {
            logWidgetFlow(
                "consumeMatchingPendingWidgetRequest noMatch viewId=$viewId updateWindow=$updateWindowName pendingCount=${state.pendingRequests.size}"
            )
        }
    }
    return removed
}
