package com.monkopedia.healthdisconnect

import kotlinx.serialization.json.Json

/**
 * The [Json] configuration for every DURABLE store — saved views (Room), the legacy DataStore blob,
 * and anything else whose payload was written by a *previous build of this app* and must survive
 * being read by a different one.
 *
 * Both settings exist to make schema drift lossless rather than catastrophic:
 *
 *  - [Json.Default] has `ignoreUnknownKeys = false`, so a field added in v1.3 makes a v1.3-written
 *    row throw on decode in *any* build that lacks it — and the codec's `runCatching` turns that
 *    throw into an empty view. One added property silently discards every metric in the view.
 *  - `coerceInputValues` covers the case `ignoreUnknownKeys` does not: an enum *constant* added
 *    later (a new [com.monkopedia.healthdisconnect.model.ChartType], say) is not an unknown key,
 *    it is an unknown value, and it throws just the same. Coercion falls back to the property's
 *    declared default. Every enum-typed property in `ChartSettings`/`MetricChartSettings` has one,
 *    which is what makes this safe: the user loses one setting, not the whole view.
 *
 * This is not hypothetical here. Issue #56 shipped exactly this failure mode by a different route
 * (persisted class names invalidated by R8) and cost two releases plus [LegacyFqnRecovery]. The
 * throwaway widget-binding state in `WidgetBindingStore` has always been decoded leniently; the
 * durable view state was not, which was the inconsistency issue #63 records.
 *
 * Encoding is unaffected — these options only relax *reading*, so what this build writes is
 * unchanged.
 */
internal val StorageJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
