package com.monkopedia.healthdisconnect

import com.monkopedia.healthdisconnect.room.DataViewEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recovery of legacy R8-obfuscated record fqns at decode time (issue #56). A view saved before
 * v1.2.1 persisted an obfuscated class name; decoding must heal it to the real name so it resolves
 * and re-persists cleanly, instead of surfacing "metric unavailable".
 */
class DataViewEntityCodecRecoveryTest {

    @Test
    fun `decode heals a legacy obfuscated fqn to the real record name`() {
        // "i5.v1" is StepsRecord's obfuscated name in the v1.2.0 build.
        val entity = DataViewEntity(id = 1, recordsJson = """[{"fqn":"i5.v1"}]""")
        val decoded = decodeDataViewEntity(entity)
        assertEquals(1, decoded.records.size)
        assertEquals(
            "androidx.health.connect.client.records.StepsRecord",
            decoded.records.single().fqn
        )
    }

    @Test
    fun `decode leaves a real fqn unchanged`() {
        val real = "androidx.health.connect.client.records.WeightRecord"
        val entity = DataViewEntity(id = 2, recordsJson = """[{"fqn":"$real"}]""")
        assertEquals(real, decodeDataViewEntity(entity).records.single().fqn)
    }

    @Test
    fun `decode leaves a genuinely unknown fqn unchanged for the re-add fallback`() {
        val entity = DataViewEntity(id = 3, recordsJson = """[{"fqn":"definitely.not.a.record"}]""")
        assertEquals("definitely.not.a.record", decodeDataViewEntity(entity).records.single().fqn)
    }
}
