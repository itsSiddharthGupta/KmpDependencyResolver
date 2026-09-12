package com.kmpdependencyresolver.providers.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileSearchCache(
    private val directory: Path,
    private val maxBytes: Long = 50L * 1024 * 1024,
) : SearchCache {
    init {
        Files.createDirectories(directory)
    }

    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload? {
        val target = directory.resolve(key.fileName())
        val envelope = read(target) ?: return null
        if (envelope.key != key.toDto()) return null
        val payload = decodePayload(envelope) ?: return null
        if (nowMillis > envelope.lastAccessedMillis) {
            runCatching { writeEnvelope(target, envelope.copy(lastAccessedMillis = nowMillis)) }
        }
        return CachedPayload(
            payload = payload,
            retrievedAtMillis = envelope.storedAtMillis,
            stale = nowMillis >= envelope.storedAtMillis + envelope.ttlMillis,
        )
    }

    override fun put(entry: CacheEntry) {
        val target = directory.resolve(entry.key.fileName())
        val envelope = CacheEnvelope(
            schemaVersion = CACHE_SCHEMA_VERSION,
            key = entry.key.toDto(),
            payloadBase64 = Base64.getEncoder().encodeToString(entry.payload),
            storedAtMillis = entry.storedAtMillis,
            ttlMillis = entry.ttlMillis,
            lastAccessedMillis = entry.storedAtMillis,
        )
        writeEnvelope(target, envelope)
        evictIfNeeded()
    }

    private fun writeEnvelope(target: Path, envelope: CacheEnvelope) {
        val temporary = Files.createTempFile(directory, ".cache-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(envelope))
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun evictIfNeeded() {
        val entries = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }
                .map { path -> path to read(path) }
                .filter { it.second != null && decodePayload(it.second!!) != null }
                .sorted(compareBy { it.second!!.lastAccessedMillis })
                .toList()
        }
        var payloadBytes = entries.sumOf { decodePayload(it.second!!)!!.size.toLong() }
        for ((path, envelope) in entries) {
            if (payloadBytes <= maxBytes) break
            Files.deleteIfExists(path)
            payloadBytes -= decodePayload(envelope!!)!!.size
        }
    }

    private fun read(path: Path): CacheEnvelope? = try {
        json.decodeFromString<CacheEnvelope>(Files.readString(path))
            .takeIf { it.schemaVersion == CACHE_SCHEMA_VERSION }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodePayload(envelope: CacheEnvelope): ByteArray? = try {
        Base64.getDecoder().decode(envelope.payloadBase64)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val CACHE_SCHEMA_VERSION = 1
        val json = Json { ignoreUnknownKeys = false }
    }
}

private fun SearchCacheKey.toDto() = CacheKeyDto(
    providerId = providerId,
    query = query.trim().lowercase(),
    targets = targets.map(String::lowercase).sorted(),
    includePreRelease = includePreRelease,
)

@Serializable
private data class CacheEnvelope(
    val schemaVersion: Int,
    val key: CacheKeyDto,
    val payloadBase64: String,
    val storedAtMillis: Long,
    val ttlMillis: Long,
    val lastAccessedMillis: Long,
)

@Serializable
private data class CacheKeyDto(
    val providerId: String,
    val query: String,
    val targets: List<String>,
    val includePreRelease: Boolean,
)
