package com.monkopedia.healthdisconnect.screenshot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cross-bucket parity for the committed screenshot baselines: every screen captured in one size
 * bucket must be captured in all of them, so a screen cannot silently vanish from one dashboard.
 *
 * This test previously could not fail (issue #64). Two independent reasons, both fixed here:
 *
 *  1. It resolved `File("app/src/test/screenshots")`, but a Gradle `Test` task's working directory
 *     is the PROJECT dir (`<repo>/app`), not the repo root — so the path became
 *     `<repo>/app/app/src/test/screenshots`, which has never existed, and the early return at the
 *     top was the entire behaviour of the test. Verified at the time with an init-script probe.
 *  2. Both escape hatches returned silently when their precondition was unmet. A check whose clean
 *     answer is indistinguishable from its did-not-run answer is not a check, so both now fail
 *     loudly instead.
 *
 * The `buckets` list also omitted `smallphone`, leaving a quarter of the matrix unexamined even
 * once the path was correct.
 *
 * Scope note: this fixes findings (b) and (d) of #64 only. The suite still RECORDS rather than
 * verifies — `verifyRoborazziGate` remains registered and unwired — which is finding (a) and an
 * open decision. Nothing here compares pixels; this asserts only which screens exist.
 */
class ScreenRoborazziDashboardIntegrityTest {

    @Test
    fun phoneAndTabletScreensHaveCounterparts() {
        // Relative to the Gradle Test working directory, which is the :app project directory.
        // Matches the capture path in ScreenRoborazziTest ("src/test/screenshots/...").
        val screenshotDir = File("src/test/screenshots")
        if (!screenshotDir.exists()) {
            fail(
                "Screenshot baseline directory not found at ${screenshotDir.absolutePath}. " +
                    "This test asserts cross-bucket parity of the committed baselines; if the " +
                    "directory is missing the assertion cannot run, so this is a failure rather " +
                    "than a pass. Record baselines with ./gradlew :app:roborazziGate."
            )
        }

        val buckets = listOf("phone", "smallphone", "tablet", "tablet7")
        val screensByBucket = buckets.associateWith { bucket ->
            screenshotDir.listFiles { file ->
                file.name.endsWith("_$bucket.png")
            }?.map { it.name.removeSuffix("_$bucket.png") }?.toSet() ?: emptySet()
        }

        val nonEmpty = screensByBucket.filterValues { it.isNotEmpty() }
        if (nonEmpty.size < 2) {
            fail(
                "Expected baselines in at least 2 of the ${buckets.size} size buckets to compare, " +
                    "but found: " +
                    screensByBucket.entries.joinToString { "${it.key}=${it.value.size}" } +
                    ". With fewer than two populated buckets there is nothing to cross-check, so " +
                    "this is a failure rather than a silent pass."
            )
        }

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
