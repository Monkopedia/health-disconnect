package com.monkopedia.healthdisconnect

import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.DataViewEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The durability contract for the saved-view blob (issue #63).
 *
 * A saved view is this app's only durable user data, and it is read back by builds that are not the
 * one that wrote it — including *older* builds, since F-Droid lets a user install any prior APK.
 * Before this contract existed, a strict decode turned any schema drift into total loss: one added
 * field or one new enum constant threw, `runCatching` swallowed it, and every metric in the view
 * silently disappeared. That is the #56 symptom by a different route.
 *
 * These tests are deliberately written against the *shape* of future drift (an unknown key, an
 * unknown enum value) rather than against today's schema, so they keep meaning as the schema grows.
 *
 * Robolectric is required: every failure path here calls `android.util.Log`, which throws
 * "not mocked" under the plain JUnit runner since `unitTests.isReturnDefaultValues` is not set.
 */
@RunWith(RobolectricTestRunner::class)
class DataViewEntityCodecDurabilityTest {

    private val stepsFqn = "androidx.health.connect.client.records.StepsRecord"
    private val weightFqn = "androidx.health.connect.client.records.WeightRecord"

    // --- Unknown keys: a field added by a LATER build must not destroy the view ----------------

    @Test
    fun `unknown key at the top level of a selection keeps the selection`() {
        val entity = DataViewEntity(
            id = 1,
            recordsJson = """[{"fqn":"$stepsFqn","futureField":"added in v1.3"}]"""
        )
        val decoded = decodeDataViewEntity(entity)
        assertEquals(1, decoded.records.size)
        assertEquals(stepsFqn, decoded.records.single().fqn)
    }

    @Test
    fun `unknown key nested inside metricSettings keeps the selection and its settings`() {
        val entity = DataViewEntity(
            id = 2,
            recordsJson = """
                [{"fqn":"$stepsFqn","metricSettings":{"aggregation":"SUM","futureField":true}}]
            """.trimIndent()
        )
        val decoded = decodeDataViewEntity(entity)
        assertEquals(1, decoded.records.size)
        // The sibling value must survive, not just the element — a lenient decode that dropped
        // metricSettings wholesale would also pass a size check.
        assertEquals(
            AggregationMode.SUM,
            decoded.records.single().metricSettings?.aggregation
        )
    }

    @Test
    fun `unknown key in chart settings keeps the other settings`() {
        val entity = DataViewEntity(
            id = 3,
            recordsJson = """[{"fqn":"$stepsFqn"}]""",
            settingsJson = """{"chartType":"BARS","futureField":{"nested":1}}"""
        )
        val decoded = decodeDataViewEntity(entity)
        assertEquals(ChartType.BARS, decoded.chartSettings.chartType)
    }

    // --- Unknown enum VALUES: not an unknown key, and ignoreUnknownKeys does not cover them -----

    @Test
    fun `unknown enum value falls back to that property's default and keeps the rest`() {
        val entity = DataViewEntity(
            id = 4,
            recordsJson = """[{"fqn":"$stepsFqn"}]""",
            settingsJson = """{"chartType":"HOLOGRAM","bucketSize":"WEEK"}"""
        )
        val decoded = decodeDataViewEntity(entity)
        // The unreadable property coerces to its declared default...
        assertEquals(ChartType.LINE, decoded.chartSettings.chartType)
        // ...and, critically, the readable neighbour is NOT lost with it.
        assertEquals(BucketSize.WEEK, decoded.chartSettings.bucketSize)
    }

    @Test
    fun `unknown enum value inside a nested metric settings object is coerced`() {
        val entity = DataViewEntity(
            id = 5,
            recordsJson = """
                [{"fqn":"$stepsFqn","metricSettings":{"aggregation":"MEDIAN","timeWindow":"DAYS_7"}}]
            """.trimIndent()
        )
        val settings = decodeDataViewEntity(entity).records.single().metricSettings
        assertEquals(AggregationMode.AVERAGE, settings?.aggregation)
        assertEquals(TimeWindow.DAYS_7, settings?.timeWindow)
    }

    // --- Per-element isolation: one bad entry costs one metric, not the view --------------------

    @Test
    fun `one unreadable selection is dropped and the others survive`() {
        val entity = DataViewEntity(
            id = 6,
            // Middle element is missing the required `fqn`, so it cannot be decoded at all.
            recordsJson = """
                [{"fqn":"$stepsFqn"},{"metricKey":"orphan"},{"fqn":"$weightFqn"}]
            """.trimIndent()
        )
        val decoded = decodeDataViewEntity(entity)
        assertEquals(2, decoded.records.size)
        assertEquals(listOf(stepsFqn, weightFqn), decoded.records.map { it.fqn })
    }

    @Test
    fun `a selection whose type is wrong entirely is dropped without taking the view`() {
        val entity = DataViewEntity(
            id = 7,
            recordsJson = """["not an object",{"fqn":"$weightFqn"}]"""
        )
        val decoded = decodeDataViewEntity(entity)
        assertEquals(listOf(weightFqn), decoded.records.map { it.fqn })
    }

    // --- Malformed blobs degrade, never throw --------------------------------------------------

    @Test
    fun `garbage records blob yields no records rather than throwing`() {
        val entity = DataViewEntity(id = 8, recordsJson = "}{ not json at all")
        assertTrue(decodeDataViewEntity(entity).records.isEmpty())
    }

    @Test
    fun `empty records blob yields no records`() {
        assertTrue(decodeDataViewEntity(DataViewEntity(id = 9, recordsJson = "")).records.isEmpty())
    }

    @Test
    fun `garbage settings blob falls back to default settings`() {
        val entity = DataViewEntity(
            id = 10,
            recordsJson = """[{"fqn":"$stepsFqn"}]""",
            settingsJson = "not json"
        )
        assertEquals(ChartSettings(), decodeDataViewEntity(entity).chartSettings)
    }

    @Test
    fun `a records blob that is a JSON object rather than an array yields no records`() {
        val entity = DataViewEntity(id = 11, recordsJson = """{"fqn":"$stepsFqn"}""")
        assertTrue(decodeDataViewEntity(entity).records.isEmpty())
    }

    // --- Missing optional keys ------------------------------------------------------------------

    @Test
    fun `absent optional keys take their declared defaults`() {
        val entity = DataViewEntity(id = 12, recordsJson = """[{"fqn":"$stepsFqn"}]""")
        val selection = decodeDataViewEntity(entity).records.single()
        assertEquals(null, selection.metricSettings)
        assertEquals(null, selection.metricKey)
        assertEquals(ChartSettings(), decodeDataViewEntity(entity).chartSettings)
    }

    // --- Round trip -----------------------------------------------------------------------------

    @Test
    fun `encode then decode preserves every field`() {
        val view = DataView(
            id = 13,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = stepsFqn,
                    metricSettings = MetricChartSettings(
                        aggregation = AggregationMode.SUM,
                        timeWindow = TimeWindow.DAYS_30,
                        bucketSize = BucketSize.WEEK,
                        showMinLabel = true
                    ),
                    metricKey = "count"
                ),
                RecordSelection(fqn = weightFqn)
            ),
            chartSettings = ChartSettings(
                aggregation = AggregationMode.MIN_MAX_AVG,
                chartType = ChartType.BARS,
                showDataPoints = true,
                bucketSize = BucketSize.MONTH
            )
        )
        assertEquals(view, decodeDataViewEntity(encodeDataViewEntity(view)))
    }

    @Test
    fun `round trip survives a legacy obfuscated fqn by healing it once`() {
        // "i5.v1" is StepsRecord under the v1.2.0 obfuscation (issue #56). Decoding heals it, and
        // re-encoding must then persist the healed name — not the obfuscated one.
        val entity = DataViewEntity(id = 14, recordsJson = """[{"fqn":"i5.v1"}]""")
        val healed = decodeDataViewEntity(entity)
        assertEquals(stepsFqn, healed.records.single().fqn)
        assertEquals(healed, decodeDataViewEntity(encodeDataViewEntity(healed)))
    }
}
