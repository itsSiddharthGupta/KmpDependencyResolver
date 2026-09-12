package com.kmpdependencyresolver.providers.cache

import java.security.MessageDigest

data class SearchCacheKey(
    val providerId: String,
    val query: String,
    val targets: Set<String>,
    val includePreRelease: Boolean,
) {
    fun fileName(): String {
        val normalized = listOf(
            providerId,
            query.trim().lowercase(),
            targets.map(String::lowercase).sorted().joinToString(","),
            includePreRelease.toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.encodeToByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
    }
}

data class CacheEntry(
    val key: SearchCacheKey,
    val payload: ByteArray,
    val storedAtMillis: Long,
    val ttlMillis: Long,
)

class CachedPayload(
    val payload: ByteArray,
    val retrievedAtMillis: Long,
    val stale: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is CachedPayload &&
        payload.contentEquals(other.payload) &&
        retrievedAtMillis == other.retrievedAtMillis &&
        stale == other.stale

    override fun hashCode(): Int = 31 * payload.contentHashCode() +
        31 * retrievedAtMillis.hashCode() + stale.hashCode()
}

interface SearchCache {
    fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload?
    fun put(entry: CacheEntry)
}
