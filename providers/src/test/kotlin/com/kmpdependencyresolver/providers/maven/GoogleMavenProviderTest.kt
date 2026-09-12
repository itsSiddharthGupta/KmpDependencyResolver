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

        assertThat(transport.uris).contains(
            "https://dl.google.com/dl/android/maven2/master-index.xml",
            "https://dl.google.com/dl/android/maven2/androidx/lifecycle/group-index.xml",
            "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/maven-metadata.xml",
        )
        assertThat(result.candidates).extracting<String> { it.coordinates.notation }
            .contains("androidx.lifecycle:lifecycle-runtime")
        assertThat(result.candidates.flatMap { it.versions }).allMatch { it.stable }
        assertThat(result.candidates.first().supportedTargets).containsExactly(TargetFamily.ANDROID)
        assertThat(result.candidates.first().evidence).isEqualTo(EvidenceKind.VERIFIED)
        assertThat(cache.entries).allMatch { it.ttlMillis == 86_400_000L }
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
