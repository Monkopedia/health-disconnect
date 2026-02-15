package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.room.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigration12Test {
    @Test
    fun migration_1_2_adds_settings_json_column_with_default() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "room_migration_1_2_test.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(SchemaV1DatabaseOpenHelperCallback())
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO data_view_info (id, name, ordering) VALUES (1, ?, 0)",
                arrayOf("Weight")
            )
            db.execSQL(
                "INSERT INTO data_views (id, type, recordsJson, alwaysShowEntries) VALUES (1, ?, ?, 0)",
                arrayOf("CHART", "[]")
            )

            migrationFromAppDatabase().migrate(db)

            assertTrue(columnExists(db, "data_views", "settingsJson"))

            db.query("SELECT id, type, recordsJson, alwaysShowEntries, settingsJson FROM data_views").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("CHART", cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("{}", cursor.getString(4))
            }

            assertTrue(columnExists(db, "data_views", "settingsJson"))
        }
    }

    private fun columnExists(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    private fun migrationFromAppDatabase(): Migration {
        val companionField = AppDatabase::class.java.getDeclaredField("Companion")
        val companion = companionField.get(null)
        val migrationField = companion.javaClass.getDeclaredField("MIGRATION_1_2")
        migrationField.isAccessible = true
        return migrationField.get(companion) as Migration
    }

    private class SchemaV1DatabaseOpenHelperCallback :
        SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE data_view_info (
                    id INTEGER NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    ordering INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE data_views (
                    id INTEGER NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    recordsJson TEXT NOT NULL DEFAULT '[]',
                    alwaysShowEntries INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
