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

// Internal rather than private so tests can seed the store with a payload written by an older build
// — the one case that cannot be reached through the functions below. Nothing outside this file
// reads or writes the raw JSON in production.
internal val widgetBindingsJson = stringPreferencesKey("widget_bindings_json")

internal val Context.widgetBindingsDataStore by preferencesDataStore("widget_bindings")

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
    val updateWindowName: String? = null,
    /**
     * Wall-clock time the request was queued, or `null` for an entry written by a build that
     * predates this field. See [isLiveAt] for why `null` means "already expired".
     */
    val enqueuedAtMillis: Long? = null
)

/**
 * How long a queued pin request stays consumable.
 *
 * `res/xml/health_graph_widget_info.xml` sets `android:updatePeriodMillis="0"`, so the system
 * schedules no periodic updates: `onUpdate` fires on widget creation, reboot and package replace.
 * The legitimate path — accept the pin, widget is created, `onUpdate` consumes the entry —
 * therefore completes in seconds, and five minutes is about two orders of magnitude of headroom.
 *
 * Lengthening this buys legitimate pins nothing and only widens the window in which an unrelated
 * widget dropped from the launcher's picker can pick up a stale entry.
 */
internal const val PENDING_WIDGET_REQUEST_TTL_MILLIS = 5L * 60L * 1000L

/**
 * Whether this request may still be consumed at [nowMillis].
 *
 * A missing [PendingWidgetRequest.enqueuedAtMillis] means the entry was persisted before this field
 * existed, so it is by definition older than any build carrying the TTL — exactly the stale
 * population the TTL exists to drop. Treating it as fresh would preserve every entry already on
 * disk and invert the fix, so absent decodes as expired.
 *
 * A timestamp in the future (the user moved the clock back, or an NTP correction did) is expired for
 * the same reason: otherwise it would outlive the TTL by however far the clock jumped.
 */
internal fun PendingWidgetRequest.isLiveAt(nowMillis: Long): Boolean {
    val enqueuedAt = enqueuedAtMillis ?: return false
    return nowMillis - enqueuedAt in 0 until PENDING_WIDGET_REQUEST_TTL_MILLIS
}

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
    updateWindowName: String?,
    nowMillis: Long = System.currentTimeMillis()
) {
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val updated = state.pendingRequests + PendingWidgetRequest(
            viewId = viewId,
            updateWindowName = updateWindowName,
            enqueuedAtMillis = nowMillis
        )
        prefs[widgetBindingsJson] = encodeWidgetBindingsState(
            state.copy(pendingRequests = updated)
        )
        logWidgetFlow(
            "enqueuePendingWidgetRequest viewId=$viewId updateWindow=$updateWindowName pendingCount=${updated.size}"
        )
    }
}

/**
 * Pops the oldest still-live queued request, dropping any that have expired.
 *
 * Expiry happens here rather than on a timer: nothing sweeps this queue, and a sweeper is more
 * machinery than a queue that is only ever read on `onUpdate` needs.
 */
suspend fun Context.consumePendingWidgetRequest(
    nowMillis: Long = System.currentTimeMillis()
): PendingWidgetRequest? {
    var consumed: PendingWidgetRequest? = null
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val live = state.pendingRequests.filter { it.isLiveAt(nowMillis) }
        val expired = state.pendingRequests.size - live.size
        val first = live.firstOrNull()
        consumed = first
        val remaining = if (first != null) live.drop(1) else live
        if (remaining.size != state.pendingRequests.size) {
            prefs[widgetBindingsJson] = encodeWidgetBindingsState(
                state.copy(pendingRequests = remaining)
            )
        }
        if (first != null) {
            logWidgetFlow(
                "consumePendingWidgetRequest viewId=${first.viewId} updateWindow=${first.updateWindowName} expired=$expired remaining=${remaining.size}"
            )
        } else {
            logWidgetFlow("consumePendingWidgetRequest noneAvailable expired=$expired")
        }
    }
    return consumed
}

/**
 * The number of queued requests a consume call would actually be willing to hand out at [nowMillis].
 *
 * Expired entries are excluded: they are refused on consume, so counting them would report a queue
 * depth no caller can draw from. This is a read — it does not prune; the consume paths do that.
 */
suspend fun Context.pendingWidgetRequestCount(
    nowMillis: Long = System.currentTimeMillis()
): Int {
    return widgetBindingsDataStore.data.first()
        .widgetBindingsStateOrDefault()
        .pendingRequests
        .count { it.isLiveAt(nowMillis) }
}

/**
 * Removes the queued request matching [viewId]/[updateWindowName], if one is still live.
 *
 * An expired match is not a match: it is dropped along with every other expired entry and this
 * returns false.
 */
suspend fun Context.consumeMatchingPendingWidgetRequest(
    viewId: Int,
    updateWindowName: String?,
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    var removed = false
    widgetBindingsDataStore.edit { prefs ->
        val state = prefs.widgetBindingsStateOrDefault()
        val live = state.pendingRequests.filter { it.isLiveAt(nowMillis) }
        val expired = state.pendingRequests.size - live.size
        val index = live.indexOfFirst { request ->
            request.viewId == viewId && request.updateWindowName == updateWindowName
        }
        removed = index >= 0
        val remaining = if (index >= 0) {
            live.toMutableList().apply { removeAt(index) }
        } else {
            live
        }
        if (remaining.size != state.pendingRequests.size) {
            prefs[widgetBindingsJson] = encodeWidgetBindingsState(
                state.copy(pendingRequests = remaining)
            )
        }
        if (removed) {
            logWidgetFlow(
                "consumeMatchingPendingWidgetRequest matched viewId=$viewId updateWindow=$updateWindowName expired=$expired remaining=${remaining.size}"
            )
        } else {
            logWidgetFlow(
                "consumeMatchingPendingWidgetRequest noMatch viewId=$viewId updateWindow=$updateWindowName expired=$expired pendingCount=${remaining.size}"
            )
        }
    }
    return removed
}
