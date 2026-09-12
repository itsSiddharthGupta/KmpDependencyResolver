package com.kmpdependencyresolver.providers.cache

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSearchCacheTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `key includes provider query targets and preview filter`() {
        val base = SearchCacheKey("central", "ktor", setOf("android", "ios"), includePreRelease = false)

        assertThat(base.fileName()).isNotEqualTo(base.copy(providerId = "klibs").fileName())
        assertThat(base.fileName()).isNotEqualTo(base.copy(query = "coil").fileName())
        assertThat(base.fileName()).isNotEqualTo(base.copy(targets = setOf("jvm")).fileName())
        assertThat(base.fileName()).isNotEqualTo(base.copy(includePreRelease = true).fileName())
    }

    @Test
    fun `fresh and stale entries retain payload and retrieval time`() {
        val cache = FileSearchCache(directory, maxBytes = 1_000_000)
        val key = SearchCacheKey("central", "ktor", setOf("ios"), false)
        cache.put(CacheEntry(key, "result".encodeToByteArray(), storedAtMillis = 100, ttlMillis = 50))

        assertThat(cache.get(key, nowMillis = 149)).isEqualTo(CachedPayload("result".encodeToByteArray(), 100, stale = false))
        assertThat(cache.get(key, nowMillis = 150)).isEqualTo(CachedPayload("result".encodeToByteArray(), 100, stale = true))
    }

    @Test
    fun `corrupt cache file is ignored`() {
        val cache = FileSearchCache(directory, maxBytes = 1_000_000)
        val key = SearchCacheKey("central", "ktor", emptySet(), false)
        Files.writeString(directory.resolve(key.fileName()), "not-json")

        assertThat(cache.get(key, nowMillis = 1)).isNull()

        Files.writeString(
            directory.resolve(key.fileName()),
            """{"schemaVersion":1,"key":{"providerId":"central","query":"ktor","targets":[],"includePreRelease":false},"payloadBase64":"%%%","storedAtMillis":0,"ttlMillis":10,"lastAccessedMillis":0}""",
        )
        assertThat(cache.get(key, nowMillis = 1)).isNull()
    }

    @Test
    fun `writing the same key atomically replaces its envelope`() {
        val cache = FileSearchCache(directory, maxBytes = 1_000_000)
        val key = SearchCacheKey("central", "ktor", emptySet(), false)
        cache.put(CacheEntry(key, "first".encodeToByteArray(), storedAtMillis = 1, ttlMillis = 100))
        cache.put(CacheEntry(key, "second".encodeToByteArray(), storedAtMillis = 2, ttlMillis = 100))

        assertThat(cache.get(key, 2)!!.payload.decodeToString()).isEqualTo("second")
        Files.list(directory).use { assertThat(it.toList()).hasSize(1) }
    }

    @Test
    fun `oldest entries are evicted when cache exceeds its limit`() {
        val cache = FileSearchCache(directory, maxBytes = 200)
        val old = SearchCacheKey("central", "old", emptySet(), false)
        val recent = SearchCacheKey("central", "recent", emptySet(), false)
        cache.put(CacheEntry(old, ByteArray(120), storedAtMillis = 1, ttlMillis = 100))
        cache.put(CacheEntry(recent, ByteArray(120), storedAtMillis = 2, ttlMillis = 100))

        assertThat(cache.get(old, 2)).isNull()
        assertThat(cache.get(recent, 2)).isNotNull()
    }

    @Test
    fun `recently read entries survive least recently used eviction`() {
        val cache = FileSearchCache(directory, maxBytes = 200)
        val first = SearchCacheKey("central", "first", emptySet(), false)
        val second = SearchCacheKey("central", "second", emptySet(), false)
        val third = SearchCacheKey("central", "third", emptySet(), false)
        cache.put(CacheEntry(first, ByteArray(100), storedAtMillis = 1, ttlMillis = 100))
        cache.put(CacheEntry(second, ByteArray(100), storedAtMillis = 2, ttlMillis = 100))

        assertThat(cache.get(first, nowMillis = 3)).isNotNull()
        cache.put(CacheEntry(third, ByteArray(100), storedAtMillis = 4, ttlMillis = 100))

        assertThat(cache.get(first, 4)).isNotNull()
        assertThat(cache.get(second, 4)).isNull()
        assertThat(cache.get(third, 4)).isNotNull()
    }
}
