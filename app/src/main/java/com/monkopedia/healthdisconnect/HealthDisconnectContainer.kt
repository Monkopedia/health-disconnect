package com.monkopedia.healthdisconnect

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface CoroutineDispatcherProvider {
    val io: CoroutineDispatcher
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : CoroutineDispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
}

interface TimeProvider {
    fun now(): Instant

    /** The zone that turns [now] into local days and hours. Read per call, so a zone change applies. */
    fun zone(): ZoneId = ZoneId.systemDefault()
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}
