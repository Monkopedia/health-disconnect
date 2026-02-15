package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.model.DataView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

interface HealthDataRecordCachePolicy {
    val recordCache: MutableMap<String, CachedRecordState>
    fun cacheKey(view: DataView): String
    fun collectData(
        view: DataView,
        refreshTick: Int,
        ioScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        recordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record>
    ): Flow<List<Record>>

    fun clear()

    data class CachedRecordState(
        val data: MutableStateFlow<List<Record>?> = MutableStateFlow(null),
        var loading: Boolean = false,
        var lastRefreshTick: Int = Int.MIN_VALUE,
        var lastAccessTick: Long = 0L
    )
}

class DefaultHealthDataRecordCachePolicy(
    private val recordCacheSchemaVersion: Int = 4,
    private val maxCachedViews: Int = 8
) : HealthDataRecordCachePolicy {
    private val recordCacheAccessCounter = AtomicLong(0L)
    private val recordCacheLock = Any()

    override val recordCache: MutableMap<String, HealthDataRecordCachePolicy.CachedRecordState> =
        mutableMapOf()

    override fun cacheKey(view: DataView): String {
        val recordsKey = view.records.map { it.fqn }.sorted().joinToString("|")
        val windowsKey = view.chartSettings.timeWindow.name
        return "v$recordCacheSchemaVersion|$recordsKey|windows=$windowsKey"
    }

    override fun collectData(
        view: DataView,
        refreshTick: Int,
        ioScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        recordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record>
    ): Flow<List<Record>> {
        val key = cacheKey(view)
        val cached = synchronized(recordCacheLock) {
            recordCache.getOrPut(key) { HealthDataRecordCachePolicy.CachedRecordState() }.also {
                it.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
            }.also {
                evictRecordCacheLocked()
            }
        }
        scheduleCacheLoad(view, refreshTick, cached, recordLoader, ioScope, ioDispatcher)
        return cached.data.filterNotNull()
    }

    override fun clear() {
        synchronized(recordCacheLock) {
            recordCache.clear()
        }
    }

    private fun scheduleCacheLoad(
        view: DataView,
        refreshTick: Int,
        cache: HealthDataRecordCachePolicy.CachedRecordState,
        recordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record>,
        ioScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher
    ) {
        val shouldRefresh = synchronized(recordCacheLock) {
            cache.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
            val needsRefresh = cache.data.value == null || refreshTick > cache.lastRefreshTick
            if (cache.loading || !needsRefresh) {
                false
            } else {
                cache.loading = true
                true
            }
        }
        if (!shouldRefresh) return
        ioScope.launch(ioDispatcher) {
            val records = recordLoader(view) { partial ->
                synchronized(recordCacheLock) {
                    cache.data.value = partial
                }
            }
            synchronized(recordCacheLock) {
                cache.data.value = records
                cache.lastRefreshTick = maxOf(cache.lastRefreshTick, refreshTick)
                cache.loading = false
                cache.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
                evictRecordCacheLocked()
            }
        }
    }

    private fun evictRecordCacheLocked() {
        if (recordCache.size <= maxCachedViews) return
        val removals = recordCache.entries
            .filter { !it.value.loading }
            .sortedBy { it.value.lastAccessTick }
            .take(recordCache.size - maxCachedViews)
        removals.forEach { (key, _) -> recordCache.remove(key) }
    }
}
