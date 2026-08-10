package com.monkopedia.healthdisconnect.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import com.monkopedia.healthdisconnect.StorageJson
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

abstract class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        try {
            // StorageJson, not Json.Default: this reads a blob written by a possibly-older build,
            // so an added field or enum constant must not turn into a CorruptionException.
            return StorageJson.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (serialization: SerializationException) {
            throw CorruptionException("Unable to read ${toString()}", serialization)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) = withContext(Dispatchers.IO) {
        output.write(StorageJson.encodeToString(serializer, t).encodeToByteArray())
    }

}
