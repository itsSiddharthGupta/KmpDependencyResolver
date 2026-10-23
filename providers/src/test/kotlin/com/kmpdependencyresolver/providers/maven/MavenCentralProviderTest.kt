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
import com.kmpdependencyresolver.providers.http.HttpStatusException
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

    @Test
    fun `exact coordinates read repository metadata instead of Solr`() {
        val metadata = """<metadata><groupId>io.ktor</groupId><artifactId>ktor-client-core</artifactId><versioning>
            <versions><version>3.2.0</version><version>3.6.0-rc1</version><version>3.6.0</version></versions>
            </versioning></metadata>""".encodeToByteArray()
        val transport = RoutingTransport(mapOf("maven-metadata.xml" to (200 to metadata)))
        val reads = mutableListOf<String>()
        val reader = PublicationEvidenceReader { coordinates, version ->
            reads += version
            PublicationEvidence(coordinates, setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.VERIFIED)
        }

        val result = MavenCentralProvider(transport, RecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("io.ktor:ktor-client-core", setOf(TargetFamily.IOS), includePreRelease = false))

        assertThat(transport.uris).containsExactly("https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml")
        assertThat(result.failure).isNull()
        val candidate = result.candidates.single()
        assertThat(candidate.coordinates.notation).isEqualTo("io.ktor:ktor-client-core")
        assertThat(candidate.versions).extracting<String> { it.value }.containsExactly("3.6.0", "3.2.0")
        assertThat(reads).containsExactly("3.6.0")
    }

    @Test
    fun `exact coordinate version is kept even when it is a pre-release`() {
        val metadata = """<metadata><versioning><versions><version>1.0.0</version><version>2.0.0-beta01</version></versions></versioning></metadata>""".encodeToByteArray()
        val reads = mutableListOf<String>()
        val reader = PublicationEvidenceReader { coordinates, version -> reads += version; PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN) }

        val result = MavenCentralProvider(RoutingTransport(mapOf("maven-metadata.xml" to (200 to metadata))), RecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("dev.sample:library:2.0.0-beta01", emptySet(), includePreRelease = false))

        assertThat(result.candidates.single().versions).extracting<String> { it.value }.containsExactly("2.0.0-beta01", "1.0.0")
        assertThat(reads).containsExactly("2.0.0-beta01")
    }

    @Test
    fun `exact coordinates absent from Central are an empty result not a failure`() {
        val transport = RoutingTransport(emptyMap())

        val result = MavenCentralProvider(transport, RecordingCache(), clock = { 1 })
            .search(SearchRequest("androidx.room:room-runtime", emptySet()))

        assertThat(result.failure).isNull()
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `server errors surface as a provider failure naming the host`() {
        val transport = RoutingTransport(mapOf("solrsearch" to (503 to ByteArray(0))))

        val result = MavenCentralProvider(transport, RecordingCache(), clock = { 1 }).search(SearchRequest("ktor", emptySet()))

        assertThat(result.failure!!.message).isEqualTo("search.maven.org returned HTTP 503.")
    }

    @Test
    fun `free text with query syntax characters is sanitised before reaching Solr`() {
        val transport = RecordingTransport(resource("central-search.json"))
        val reader = PublicationEvidenceReader { coordinates, _ -> PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN) }

        MavenCentralProvider(transport, RecordingCache(), reader, clock = { 1 }).search(SearchRequest("io.ktor: client", emptySet()))

        assertThat(transport.uri).isEqualTo("https://search.maven.org/solrsearch/select?q=io.ktor+client&rows=50&wt=json")
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

/** Answers by URI substring like a real server: unknown paths are 404, errors carry the host. */
private class RoutingTransport(private val routes: Map<String, Pair<Int, ByteArray>>) : HttpTransport {
    val uris = mutableListOf<String>()
    override fun execute(request: com.kmpdependencyresolver.providers.http.HttpRequestSpec): HttpPayload {
        uris += request.uri.toString()
        val (status, body) = routes.entries.firstOrNull { request.uri.toString().contains(it.key) }?.value ?: (404 to ByteArray(0))
        if (status !in 200..299) throw HttpStatusException(status, "${request.uri.host} returned HTTP $status.")
        return HttpPayload(status, mapOf("content-type" to listOf("application/xml")), body)
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
