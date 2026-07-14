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
    fun `classForFqn returns null for an unresolvable stored name`() {
        // A legacy view saved under an obfuscated build holds a name like this; it must not resolve
        // (so callers show the localized "re-add this metric" prompt) rather than mis-resolve.
        assertEquals(null, PermissionsViewModel.classForFqn("S1.z0"))
        assertEquals(null, PermissionsViewModel.classForFqn(null))
        assertEquals(null, PermissionsViewModel.classForFqn(""))
    }
}
