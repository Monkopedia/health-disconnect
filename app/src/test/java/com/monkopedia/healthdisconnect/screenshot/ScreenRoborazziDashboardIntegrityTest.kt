package com.monkopedia.healthdisconnect.screenshot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRoborazziDashboardIntegrityTest {

    @Test
    fun phoneAndTabletScreensHaveCounterparts() {
        val screenshotDir = File("app/build/outputs/roborazzi/screens")
        if (!screenshotDir.exists()) return

        val phoneScreens = screenshotDir.listFiles { file ->
            file.name.endsWith("_phone.png")
        }?.map { it.name.removeSuffix("_phone.png") } ?: emptyList()

        val tabletScreens = screenshotDir.listFiles { file ->
            file.name.endsWith("_tablet.png")
        }?.map { it.name.removeSuffix("_tablet.png") } ?: emptyList()

        if (phoneScreens.isEmpty() || tabletScreens.isEmpty()) return

        val phoneSet = phoneScreens.toSet()
        val tabletSet = tabletScreens.toSet()

        val missingOnTablet = phoneSet - tabletSet
        val missingOnPhone = tabletSet - phoneSet

        assertTrue(
            "Screens missing on tablet dashboard: ${missingOnTablet.joinToString()}",
            missingOnTablet.isEmpty()
        )
        assertTrue(
            "Screens missing on phone dashboard: ${missingOnPhone.joinToString()}",
            missingOnPhone.isEmpty()
        )
    }
}
