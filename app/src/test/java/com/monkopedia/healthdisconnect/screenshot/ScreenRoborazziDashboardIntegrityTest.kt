package com.monkopedia.healthdisconnect.screenshot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRoborazziDashboardIntegrityTest {

    @Test
    fun phoneAndTabletScreensHaveCounterparts() {
        val screenshotDir = File("app/build/outputs/roborazzi/screens")
        if (!screenshotDir.exists()) return

        val buckets = listOf("phone", "tablet", "tablet7")
        val screensByBucket = buckets.associateWith { bucket ->
            screenshotDir.listFiles { file ->
                file.name.endsWith("_$bucket.png")
            }?.map { it.name.removeSuffix("_$bucket.png") }?.toSet() ?: emptySet()
        }

        val nonEmpty = screensByBucket.filterValues { it.isNotEmpty() }
        if (nonEmpty.size < 2) return

        val allScreens = nonEmpty.values.reduce { acc, set -> acc union set }

        nonEmpty.forEach { (bucket, screens) ->
            val missing = allScreens - screens
            assertTrue(
                "Screens missing on $bucket dashboard: ${missing.joinToString()}",
                missing.isEmpty()
            )
        }
    }
}
