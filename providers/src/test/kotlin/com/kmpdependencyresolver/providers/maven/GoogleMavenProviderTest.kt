package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.CachedPayload
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.http.HttpPayload
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import com.kmpdependencyresolver.providers.ProviderMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GoogleMavenProviderTest {
    @Test
    fun `uses Google group index and returns stable Android publication`() {
        val transport = GoogleFixtureTransport(
            master = resource("google-master-index.xml"),
            group = resource("google-group-index.xml"),
            metadata = resource("google-maven-metadata.xml"),
        )
        val cache = GoogleRecordingCache()
        val reader = PublicationEvidenceReader { coordinates, _ ->
            PublicationEvidence(coordinates, setOf(TargetFamily.ANDROID), EvidenceKind.VERIFIED)
        }
        val provider = GoogleMavenProvider(transport, cache, reader, clock = { 500 })

        val result = provider.search(SearchRequest("androidx.lifecycle", setOf(TargetFamily.ANDROID), false))

        // The group index carries every version inline, so no per-artifact maven-metadata.xml request is needed.
        assertThat(transport.uris).containsExactly(
            "https://dl.google.com/dl/android/maven2/master-index.xml",
            "https://dl.google.com/dl/android/maven2/androidx/lifecycle/group-index.xml",
        )
        assertThat(result.candidates).extracting<String> { it.coordinates.notation }
            .contains("androidx.lifecycle:lifecycle-runtime")
        assertThat(result.candidates.flatMap { it.versions }).allMatch { it.stable }
        assertThat(result.candidates.first().supportedTargets).containsExactly(TargetFamily.ANDROID)
        assertThat(result.candidates.first().evidence).isEqualTo(EvidenceKind.VERIFIED)
        assertThat(cache.entries).allMatch { it.ttlMillis == 86_400_000L }
    }

    @Test
    fun `cache only mode returns stale index data without network or publication lookup`() {
        val transport = GoogleFixtureTransport(ByteArray(0), ByteArray(0), ByteArray(0))
        var evidenceReads = 0
        val reader = PublicationEvidenceReader { coordinates, _ ->
            evidenceReads++
            PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN)
        }
        val cache = GoogleFixedCache(
            master = resource("google-master-index.xml"),
            group = resource("google-group-index.xml"),
            metadata = resource("google-maven-metadata.xml"),
        )
        val provider = GoogleMavenProvider(transport, cache, reader, clock = { 1_000 }, mode = ProviderMode.CACHE_ONLY)

        val result = provider.search(SearchRequest("androidx.lifecycle", emptySet(), false))

        assertThat(result.candidates).isNotEmpty()
        assertThat(result.fromCache).isTrue()
        assertThat(result.candidates.first().provenance.single().detail).contains("Stale cache")
        assertThat(transport.uris).isEmpty()
        assertThat(evidenceReads).isZero()
    }

    @Test
    fun `exact coordinate query reads only that group and artifact`() {
        val groupIndex = """<?xml version="1.0" encoding="UTF-8"?>
            <androidx.room>
              <room-runtime versions="2.7.0,2.8.4,2.9.0-alpha01"/>
              <room-runtime-android versions="2.7.0,2.8.4"/>
              <room-compiler versions="2.8.4"/>
            </androidx.room>""".trimIndent().encodeToByteArray()
        val transport = GoogleFixtureTransport(resource("google-master-index.xml"), groupIndex, ByteArray(0))
        val reads = mutableListOf<String>()
        val reader = PublicationEvidenceReader { coordinates, version ->
            reads += "${coordinates.notation}:$version"
            PublicationEvidence(coordinates, setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.VERIFIED)
        }

        val result = GoogleMavenProvider(transport, GoogleRecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("androidx.room:room-runtime", setOf(TargetFamily.ANDROID), false))

        assertThat(result.failure).isNull()
        assertThat(result.candidates).extracting<String> { it.coordinates.notation }.containsExactly("androidx.room:room-runtime")
        assertThat(result.candidates.single().versions).extracting<String> { it.value }.containsExactly("2.8.4", "2.7.0")
        assertThat(reads).containsExactly("androidx.room:room-runtime:2.8.4")
        assertThat(transport.uris).containsExactly(
            "https://dl.google.com/dl/android/maven2/master-index.xml",
            "https://dl.google.com/dl/android/maven2/androidx/room/group-index.xml",
        )
    }

    @Test
    fun `artifact name finds its group by segment and exact coordinates outside the index return nothing`() {
        val groupIndex = """<androidx.room><room-runtime versions="2.8.4"/><room-ktx versions="2.8.4"/></androidx.room>""".encodeToByteArray()
        val reader = PublicationEvidenceReader { coordinates, _ -> PublicationEvidence(coordinates, setOf(TargetFamily.ANDROID), EvidenceKind.VERIFIED) }

        val byName = GoogleMavenProvider(GoogleFixtureTransport(resource("google-master-index.xml"), groupIndex, ByteArray(0)), GoogleRecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("room-runtime", emptySet(), false))
        val missing = GoogleMavenProvider(GoogleFixtureTransport(resource("google-master-index.xml"), groupIndex, ByteArray(0)), GoogleRecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("io.ktor:ktor-client-core", emptySet(), false))

        assertThat(byName.candidates).extracting<String> { it.coordinates.notation }.containsExactly("androidx.room:room-runtime")
        assertThat(missing.failure).isNull()
        assertThat(missing.candidates).isEmpty()
    }

    @Test
    fun `falls back to maven metadata when the group index omits versions`() {
        val groupIndex = """<androidx.lifecycle><lifecycle-runtime/></androidx.lifecycle>""".encodeToByteArray()
        val transport = GoogleFixtureTransport(resource("google-master-index.xml"), groupIndex, resource("google-maven-metadata.xml"))
        val reader = PublicationEvidenceReader { coordinates, _ -> PublicationEvidence(coordinates, setOf(TargetFamily.ANDROID), EvidenceKind.VERIFIED) }

        val result = GoogleMavenProvider(transport, GoogleRecordingCache(), reader, clock = { 1 })
            .search(SearchRequest("androidx.lifecycle:lifecycle-runtime", emptySet(), false))

        assertThat(transport.uris).contains("https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/maven-metadata.xml")
        assertThat(result.candidates.single().versions).extracting<String> { it.value }.containsExactly("2.8.7")
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/maven/$name")).use { it.readAllBytes() }
}

private class GoogleFixtureTransport(
    private val master: ByteArray,
    private val group: ByteArray,
    private val metadata: ByteArray,
) : HttpTransport {
    val uris = mutableListOf<String>()
    override fun execute(request: HttpRequestSpec): HttpPayload {
        uris += request.uri.toString()
        val body = when {
            request.uri.path.endsWith("master-index.xml") -> master
            request.uri.path.endsWith("group-index.xml") -> group
            else -> metadata
        }
        return HttpPayload(200, mapOf("content-type" to listOf("application/xml")), body)
    }
}

private class GoogleRecordingCache : SearchCache {
    val entries = mutableListOf<CacheEntry>()
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload? = null
    override fun put(entry: CacheEntry) { entries += entry }
}

private class GoogleFixedCache(
    private val master: ByteArray,
    private val group: ByteArray,
    private val metadata: ByteArray,
) : SearchCache {
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload {
        val payload = when {
            key.providerId.endsWith(":master") -> master
            key.providerId.contains(":group:") -> group
            else -> metadata
        }
        return CachedPayload(payload, retrievedAtMillis = 100, stale = true)
    }

    override fun put(entry: CacheEntry) = error("Cache-only search must not write")
}
