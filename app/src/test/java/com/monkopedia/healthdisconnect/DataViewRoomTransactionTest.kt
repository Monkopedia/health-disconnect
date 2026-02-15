package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewDao
import com.monkopedia.healthdisconnect.room.DataViewEntity
import com.monkopedia.healthdisconnect.room.DataViewInfoDao
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DataViewRoomTransactionTest {

    private lateinit var appDb: AppDatabase
    private lateinit var appInfoDao: DataViewInfoDao
    private lateinit var appViewDao: DataViewDao
    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext<Application>()
        appDb = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appInfoDao = appDb.dataViewInfoDao()
        appViewDao = appDb.dataViewDao()
    }

    @After
    fun tearDown() {
        appDb.close()
    }

    @Test
    fun `create and delete operations rollback atomically`() = runBlocking {
        val info = DataViewInfoEntity(id = 1, name = "Weight", ordering = 1)
        val view = DataViewEntity(
            id = 1,
            type = ViewType.CHART.name,
            recordsJson = "[]",
            settingsJson = "{}"
        )

        appDb.withTransaction {
            appInfoDao.insert(info)
            appViewDao.insert(view)
        }

        assertEquals(1, appInfoDao.count())
        assertEquals(1, countDataViews())

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                appDb.withTransaction {
                    appInfoDao.insert(DataViewInfoEntity(id = 2, name = "Distance", ordering = 2))
                    appViewDao.insert(
                        DataViewEntity(
                            id = 2,
                            type = ViewType.CHART.name,
                            recordsJson = "[]",
                            settingsJson = "{}"
                        )
                    )
                    throw IllegalStateException("simulate failure")
                }
            }
        }

        assertEquals(1, appInfoDao.count())
        assertEquals(1, countDataViews())
    }

    @Test
    fun `deletion failures keep all state`() = runBlocking {
        val info = DataViewInfoEntity(id = 10, name = "Weight", ordering = 10)
        val view = DataViewEntity(
            id = 10,
            type = ViewType.CHART.name,
            recordsJson = "[]",
            settingsJson = "{}"
        )
        appDb.withTransaction {
            appInfoDao.insert(info)
            appViewDao.insert(view)
        }

        assertEquals(1, appInfoDao.count())
        assertEquals(1, countDataViews())

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                appDb.withTransaction {
                    appInfoDao.deleteById(info.id)
                    appViewDao.deleteById(view.id)
                    throw IllegalStateException("simulate failure")
                }
            }
        }

        assertEquals(1, appInfoDao.count())
        assertEquals(1, countDataViews())
    }

    @Test
    fun `migration from version 1 to 2 sets default settings`() = runBlocking {
        val dbFile = File(appContext.cacheDir, "migrated-room-db-${System.nanoTime()}.db")
        val legacyDatabase = Room.databaseBuilder(
            appContext,
            LegacyAppDatabaseV1::class.java,
            dbFile.absolutePath
        ).build()
        try {
            legacyDatabase.dataViewInfoDao().insert(
                DataViewInfoEntity(id = 20, name = "Steps", ordering = 1)
            )
            legacyDatabase.dataViewDao().insert(
                LegacyDataViewEntityV1(
                    id = 20,
                    type = ViewType.CHART.name,
                    recordsJson = "[]",
                )
            )
        } finally {
            legacyDatabase.close()
        }

        val migratedDb = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            dbFile.absolutePath
        ).addMigrations(migrationFrom1To2).build()
        try {
            val migratedInfo = migratedDb.dataViewInfoDao().allOrdered().first()
            val migratedView = migratedDb.dataViewDao().dataView(20).first()

            assertEquals("Steps", migratedInfo.single().name)
            assertEquals(1, migratedInfo.single().ordering)
            assertEquals(ViewType.CHART.name, migratedView.type)
            assertEquals("{}", migratedView.settingsJson)
        } finally {
            migratedDb.close()
            dbFile.delete()
            File("${dbFile.absolutePath}-shm").delete()
            File("${dbFile.absolutePath}-wal").delete()
        }
    }

    private fun countDataViews(): Int {
        appDb.openHelper.readableDatabase.query("SELECT COUNT(*) FROM data_views").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    @Database(
        entities = [LegacyDataViewEntityV1::class, DataViewInfoEntity::class],
        version = 1,
        exportSchema = false
    )
    abstract class LegacyAppDatabaseV1 : RoomDatabase() {
        abstract fun dataViewDao(): LegacyDataViewDao
        abstract fun dataViewInfoDao(): DataViewInfoDao
    }

    @Dao
    interface LegacyDataViewDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insert(view: LegacyDataViewEntityV1)
    }

    @Entity(tableName = "data_views")
    data class LegacyDataViewEntityV1(
        @PrimaryKey val id: Int,
        val type: String = ViewType.CHART.name,
        val recordsJson: String = "[]",
        val alwaysShowEntries: Boolean = false
    )

    private val migrationFrom1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE data_views ADD COLUMN settingsJson TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }
}
