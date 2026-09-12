package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MavenCentralProvider(
    private val transport: HttpTransport,
    private val cache: SearchCache,
    private val evidenceReader: PublicationEvidenceReader = RepositoryPublicationEvidenceReader(transport, CENTRAL_REPOSITORY),
    private val clock: () -> Long = System::currentTimeMillis,
) : SearchProvider {
    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            val key = SearchCacheKey(ID, request.query, request.requiredTargets.map { it.name }.toSet(), request.includePreRelease)
            val cached = cache.get(key, now)?.takeUnless { it.stale }
            val payload = cached?.payload ?: fetch(request.query).also {
                cache.put(CacheEntry(key, it, now, SEARCH_TTL_MILLIS))
            }
            val docs = json.decodeFromString<CentralResponse>(payload.decodeToString()).response.docs
            val candidates = docs.mapNotNull { doc -> candidate(doc, request.includePreRelease) }
            ProviderResult(ID, candidates, retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
        }.getOrElse { exception ->
            ProviderResult(ID, emptyList(), ProviderFailure(ID, exception.message ?: "Central search failed"), now, false)
        }
    }

    private fun fetch(query: String): ByteArray {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        return transport.execute(
            HttpRequestSpec(
                URI.create("$SEARCH_ENDPOINT?q=$encoded&rows=50&wt=json"),
                expectedContentTypes = setOf("application/json"),
            ),
        ).body
    }

    private fun candidate(doc: CentralDoc, includePreRelease: Boolean): Candidate? {
        val stable = isStableVersion(doc.latestVersion)
        if (!stable && !includePreRelease) return null
        val requested = Coordinates(doc.g, doc.a)
        val publication = runCatching { evidenceReader.read(requested, doc.latestVersion) }
            .getOrElse { PublicationEvidence(requested, emptySet(), EvidenceKind.UNKNOWN, detail = it.message) }
        val canonical = publication.canonicalCoordinates ?: requested
        val detail = buildString {
            append("Central search")
            if (publication.rawTargetNames.isNotEmpty()) append("; targets=${publication.rawTargetNames.sorted().joinToString(",")}")
            publication.detail?.let { append("; $it") }
        }
        return Candidate(
            canonical,
            canonical.artifact,
            listOf(DependencyVersion(doc.latestVersion, stable)),
            publication.supportedTargets,
            publication.evidence,
            setOf(Provenance(ID, detail)),
        )
    }

    private companion object {
        const val ID = "maven-central"
        const val SEARCH_ENDPOINT = "https://search.maven.org/solrsearch/select"
        const val CENTRAL_REPOSITORY = "https://repo1.maven.org/maven2"
        const val SEARCH_TTL_MILLIS = 86_400_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable private data class CentralResponse(val response: CentralDocs)
@Serializable private data class CentralDocs(val docs: List<CentralDoc>)
@Serializable private data class CentralDoc(val g: String, val a: String, val latestVersion: String)

internal fun isStableVersion(version: String): Boolean =
    !Regex("(?i)(?:^|[._-])(alpha|beta|rc|cr|m|milestone|snapshot|dev|eap|preview)(?:[._-]?\\d*)").containsMatchIn(version)
