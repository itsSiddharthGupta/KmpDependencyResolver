package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.CachedPayload
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.http.HttpPayload
import com.kmpdependencyresolver.providers.http.HttpTransport
import com.kmpdependencyresolver.providers.ProviderMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MavenCentralProviderTest {
    @Test
    fun `encodes query filters preview versions and records 24 hour cache ttl`() {
        val transport = RecordingTransport(resource("central-search.json"))
        val cache = RecordingCache()
        val reader = PublicationEvidenceReader { coordinates, _ ->
            PublicationEvidence(coordinates, setOf(TargetFamily.JVM, TargetFamily.IOS), EvidenceKind.VERIFIED, setOf("ios_arm64"))
        }
        val provider = MavenCentralProvider(transport, cache, reader, clock = { 1_000 })

        val result = provider.search(SearchRequest("ktor client", setOf(TargetFamily.IOS), includePreRelease = false))

        assertThat(transport.uri).isEqualTo("https://search.maven.org/solrsearch/select?q=ktor+client&rows=50&wt=json")
        assertThat(result.candidates).extracting<String> { it.coordinates.notation }
            .containsExactly("io.ktor:ktor-client-core")
        assertThat(result.candidates.single().provenance).anyMatch { it.providerId == "maven-central" }
        assertThat(cache.lastPut!!.ttlMillis).isEqualTo(86_400_000)
    }

    @Test
    fun `malformed publication metadata degrades only that candidate`() {
        val reader = PublicationEvidenceReader { coordinates, _ ->
            if (coordinates.artifact == "ktor-client-core") error("bad metadata")
            PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN)
        }
        val provider = MavenCentralProvider(RecordingTransport(resource("central-search.json")), RecordingCache(), reader, clock = { 1 })

        val result = provider.search(SearchRequest("ktor", emptySet(), includePreRelease = true))

        assertThat(result.failure).isNull()
        assertThat(result.candidates).hasSize(2)
        assertThat(result.candidates.first().evidence).isEqualTo(EvidenceKind.UNKNOWN)
    }

    @Test
    fun `cache only mode returns stale search data without network or publication lookup`() {
        val payload = resource("central-search.json")
        val transport = RecordingTransport(payload)
        var evidenceReads = 0
        val reader = PublicationEvidenceReader { coordinates, _ ->
            evidenceReads++
            PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN)
        }
        val cache = FixedCache(CachedPayload(payload, retrievedAtMillis = 100, stale = true))
        val provider = MavenCentralProvider(transport, cache, reader, clock = { 1_000 }, mode = ProviderMode.CACHE_ONLY)

        val result = provider.search(SearchRequest("ktor", emptySet(), includePreRelease = false))

        assertThat(result.candidates).isNotEmpty()
        assertThat(result.fromCache).isTrue()
        assertThat(result.candidates.first().provenance.single().detail).contains("Stale cache")
        assertThat(transport.uri).isNull()
        assertThat(evidenceReads).isZero()
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/maven/$name")).use { it.readAllBytes() }
}

private class RecordingTransport(private val response: ByteArray) : HttpTransport {
    var uri: String? = null
    override fun execute(request: com.kmpdependencyresolver.providers.http.HttpRequestSpec): HttpPayload {
        uri = request.uri.toString()
        return HttpPayload(200, mapOf("content-type" to listOf("application/json")), response)
    }
}

private class RecordingCache : SearchCache {
    var lastPut: CacheEntry? = null
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload? = null
    override fun put(entry: CacheEntry) { lastPut = entry }
}

private class FixedCache(private val payload: CachedPayload) : SearchCache {
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload = payload
    override fun put(entry: CacheEntry) = error("Cache-only search must not write")
}
