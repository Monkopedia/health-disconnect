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
        withTimeout(1_000) {
            while (permissionCalls.get() < 2) {
                delay(20)
            }
        }

        assertEquals(
            listOf(emptySet<String>(), PermissionsViewModel.PERMISSIONS),
            emissions
        )
        collector.await()
    }

    @Test
    fun `permissions include background health data access`() {
        assertTrue(
            PermissionsViewModel.PERMISSIONS.contains(
                PermissionsViewModel.BACKGROUND_PERMISSION
            )
        )
    }
}
