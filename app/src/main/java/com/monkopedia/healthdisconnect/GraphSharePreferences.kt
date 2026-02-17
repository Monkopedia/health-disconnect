package com.monkopedia.healthdisconnect

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

enum class GraphShareTheme {
    LIGHT,
    DARK
}

data class GraphSharePreferences(
    val dialogShownCount: Int = 0,
    val hideDialog: Boolean = false,
    val selectedTheme: GraphShareTheme = GraphShareTheme.DARK
)

val Context.graphShareDataStore by preferencesDataStore("graph_share")

internal fun Preferences.toGraphSharePreferences(): GraphSharePreferences {
    return GraphSharePreferences(
        dialogShownCount = this[GRAPH_SHARE_DIALOG_COUNT] ?: 0,
        hideDialog = this[GRAPH_SHARE_HIDE_DIALOG] ?: false,
        selectedTheme = this[GRAPH_SHARE_THEME]
            ?.let { raw -> runCatching { GraphShareTheme.valueOf(raw) }.getOrNull() }
            ?: GraphShareTheme.DARK
    )
}

suspend fun Context.incrementGraphShareDialogCount() {
    graphShareDataStore.edit { prefs ->
        val current = prefs[GRAPH_SHARE_DIALOG_COUNT] ?: 0
        prefs[GRAPH_SHARE_DIALOG_COUNT] = current + 1
    }
}

suspend fun Context.updateGraphSharePreferences(
    selectedTheme: GraphShareTheme,
    hideDialog: Boolean
) {
    graphShareDataStore.edit { prefs ->
        prefs[GRAPH_SHARE_THEME] = selectedTheme.name
        prefs[GRAPH_SHARE_HIDE_DIALOG] = hideDialog
    }
}

private val GRAPH_SHARE_DIALOG_COUNT = intPreferencesKey("graph_share_dialog_count")
private val GRAPH_SHARE_HIDE_DIALOG = booleanPreferencesKey("graph_share_hide_dialog")
private val GRAPH_SHARE_THEME = stringPreferencesKey("graph_share_theme")
