package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PermissionsViewModelTest {
    @After
    fun tearDown() {
        unmockkObject(HealthConnectClient.Companion)
    }

    @Test
    fun `needsPermissions honors ignore flag`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionController = mockk<PermissionController>(relaxed = true)
        val client = mockk<HealthConnectClient>(relaxed = true)
        every { client.permissionController } returns permissionController

        mockkObject(HealthConnectClient.Companion)
        every { HealthConnectClient.Companion.getSdkStatus(any(), any()) } returns
            HealthConnectClient.SDK_AVAILABLE
        every { HealthConnectClient.Companion.getOrCreate(any()) } returns client
        coEvery { permissionController.getGrantedPermissions() } returns emptySet()

        val permissionsViewModel = PermissionsViewModel(
            context = app,
            healthConnectClient = client
        )
        assertEquals(true, permissionsViewModel.needsPermissions.first())

        permissionsViewModel.ignorePermissions()
        assertFalse(withTimeout(2_000) { permissionsViewModel.needsPermissions.first { !it } })
    }

    @Test
    fun `onResult refreshes granted permissions`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissionController = mockk<PermissionController>(relaxed = true)
        val client = mockk<HealthConnectClient>(relaxed = true)
        every { client.permissionController } returns permissionController
        val permissionCalls = AtomicInteger(0)

        mockkObject(HealthConnectClient.Companion)
        every { HealthConnectClient.Companion.getSdkStatus(any(), any()) } returns
            HealthConnectClient.SDK_AVAILABLE
        every { HealthConnectClient.Companion.getOrCreate(any()) } returns client
        coEvery { permissionController.getGrantedPermissions() } answers {
            permissionCalls.incrementAndGet()
            if (permissionCalls.get() == 1) {
                emptySet<String>()
            } else {
                PermissionsViewModel.PERMISSIONS
            }
        }

        val permissionsViewModel = PermissionsViewModel(
            context = app,
            healthConnectClient = client
        )
        val emissions = mutableListOf<Set<String>>()
        val collector = async {
            permissionsViewModel.grantedPermissions.take(2).collect {
                emissions.add(it)
            }
        }
        withTimeout(1_000) {
            while (permissionCalls.get() < 1) {
                delay(20)
            }
        }

        permissionsViewModel.onResult(emptySet())
        // Wait for the collector to actually receive both emissions (take(2) completes
        // once the second arrives) before asserting. Polling the call counter alone
        // only proves getGrantedPermissions was invoked, not that the resulting value
        // was collected — which races the emission into the list.
        withTimeout(1_000) { collector.await() }

        assertEquals(
            listOf(emptySet<String>(), PermissionsViewModel.PERMISSIONS),
            emissions
        )
    }

    @Test
    fun `permissions include background health data access`() {
        assertTrue(
            PermissionsViewModel.PERMISSIONS.contains(
                PermissionsViewModel.BACKGROUND_PERMISSION
            )
        )
    }

    // --- Record-class identity contract (regression guard for #56) ---
    // Views persist RecordSelection.fqn = KClass.qualifiedName and resolve it back via classForFqn.
    // If R8 ever obfuscates the Health Connect record classes (missing keep rule), qualifiedName
    // stops being the real, stable androidx.health.connect.client.records name and saved views break
    // after an update. These assert the invariant the app depends on; the release-build guard that
    // actually detects obfuscation lives in CI (grep of the R8 mapping), since unit tests run
    // against un-minified classes.

    @Test
    fun `every registered record class exposes its real Health Connect qualified name`() {
        PermissionsViewModel.CLASSES.forEach { cls ->
            val fqn = cls.qualifiedName
            assertTrue(
                "record class qualifiedName must be a real HC name, was: $fqn",
                fqn != null && fqn.startsWith("androidx.health.connect.client.records.")
            )
        }
    }

    @Test
    fun `classForFqn round-trips every registered record class`() {
        PermissionsViewModel.CLASSES.forEach { cls ->
            assertEquals(cls, PermissionsViewModel.classForFqn(cls.qualifiedName))
        }
    }

    @Test
    fun `classForFqn returns null for a genuinely unrecoverable stored name`() {
        // A name that is neither a real class nor a known legacy obfuscated one must not resolve
        // (so callers show the localized "re-add this metric" prompt) rather than mis-resolve.
        assertEquals(null, PermissionsViewModel.classForFqn("definitely.not.a.record.type"))
        assertEquals(null, PermissionsViewModel.classForFqn(null))
        assertEquals(null, PermissionsViewModel.classForFqn(""))
    }

    // --- Legacy obfuscated-name recovery (issue #56) ---
    // Views saved before v1.2.1 persisted R8-obfuscated record-class names. classForFqn recovers
    // them via LegacyFqnRecovery so the view auto-heals instead of breaking after an update.

    @Test
    fun `classForFqn recovers legacy obfuscated record names across eras`() {
        // WeightRecord was "S1.z0" through v1.1.1 and "i5.y1" in v1.2.0 — both must recover.
        val weight = "androidx.health.connect.client.records.WeightRecord"
        assertEquals(weight, PermissionsViewModel.classForFqn("S1.z0")?.qualifiedName)
        assertEquals(weight, PermissionsViewModel.classForFqn("i5.y1")?.qualifiedName)
        // StepsRecord likewise (S1.w0 / i5.v1).
        val steps = "androidx.health.connect.client.records.StepsRecord"
        assertEquals(steps, PermissionsViewModel.classForFqn("S1.w0")?.qualifiedName)
        assertEquals(steps, PermissionsViewModel.classForFqn("i5.v1")?.qualifiedName)
    }

    @Test
    fun `LegacyFqnRecovery normalize leaves real and unknown names unchanged`() {
        val real = "androidx.health.connect.client.records.WeightRecord"
        assertEquals(real, LegacyFqnRecovery.normalize(real))
        assertEquals("definitely.not.a.record.type", LegacyFqnRecovery.normalize("definitely.not.a.record.type"))
        assertEquals(real, LegacyFqnRecovery.normalize("S1.z0"))
    }
}
