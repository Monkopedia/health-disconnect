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
    val widgetToView: Map<Int, Int> = emptyMap()
)

internal fun Preferences.toWidgetBindingsState(): Map<Int, Int> {
    val raw = this[widgetBindingsJson] ?: return emptyMap()
    return runCatching {
        widgetBindingsJsonCodec
            .decodeFromString(WidgetBindingsState.serializer(), raw)
            .widgetToView
    }.getOrElse { emptyMap() }
}

private fun encodeWidgetBindingsState(widgetToView: Map<Int, Int>): String {
    return widgetBindingsJsonCodec.encodeToString(
        WidgetBindingsState.serializer(),
        WidgetBindingsState(widgetToView = widgetToView)
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
        val current = prefs.toWidgetBindingsState().toMutableMap()
        current[appWidgetId] = viewId
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(current)
    }
}

suspend fun Context.unbindWidget(appWidgetId: Int) {
    widgetBindingsDataStore.edit { prefs ->
        val current = prefs.toWidgetBindingsState().toMutableMap()
        current.remove(appWidgetId)
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(current)
    }
}

suspend fun Context.unbindWidgets(appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    widgetBindingsDataStore.edit { prefs ->
        val current = prefs.toWidgetBindingsState().toMutableMap()
        appWidgetIds.forEach { current.remove(it) }
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(current)
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
