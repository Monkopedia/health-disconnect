package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetBindingStoreTest {
    private lateinit var app: Application

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        val existing = app.widgetBindingsSnapshot().keys.toIntArray()
        app.unbindWidgets(existing)
    }

    @Test
    fun bindWidgetToView_persistsSnapshotAndLookup() = runBlocking {
        app.bindWidgetToView(appWidgetId = 101, viewId = 7)

        val snapshot = app.widgetBindingsSnapshot()
        assertEquals(7, snapshot[101])
        assertEquals(7, app.widgetViewId(101))
    }

    @Test
    fun unbindWidgets_removesMappings() = runBlocking {
        app.bindWidgetToView(appWidgetId = 201, viewId = 4)
        app.bindWidgetToView(appWidgetId = 202, viewId = 4)
        app.bindWidgetToView(appWidgetId = 203, viewId = 5)

        app.unbindWidgets(intArrayOf(201, 203))

        val snapshot = app.widgetBindingsSnapshot()
        assertFalse(snapshot.containsKey(201))
        assertFalse(snapshot.containsKey(203))
        assertEquals(4, snapshot[202])
    }

    @Test
    fun hasWidgetForView_reflectsBoundWidgets() = runBlocking {
        app.bindWidgetToView(appWidgetId = 301, viewId = 11)

        assertTrue(app.hasWidgetForView(11))
        assertFalse(app.hasWidgetForView(12))
    }
}
