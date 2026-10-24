package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.search.CoordinateQuery
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpStatusException
import com.kmpdependencyresolver.providers.http.HttpTransport
import com.kmpdependencyresolver.providers.ProviderMode
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
    private val mode: ProviderMode = ProviderMode.ONLINE,
) : SearchProvider {
    override val id: String get() = ID

    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            val exact = CoordinateQuery.parse(request.query)
            if (exact != null) searchExact(request, exact, now) else searchText(request, now)
        }.getOrElse { exception ->
            ProviderResult(ID, emptyList(), ProviderFailure(ID, exception.message ?: "Central search failed"), now, false)
        }
    }

    /**
     * `group:artifact[:version]` queries bypass Solr: its query parser rejects `:` with HTTP 400 and its
     * index lags behind the repository. `maven-metadata.xml` on repo1 is authoritative and current.
     */
    private fun searchExact(request: SearchRequest, exact: CoordinateQuery, now: Long): ProviderResult {
        val coordinates = exact.coordinates
        val key = cacheKey(request)
        val cached = cache.get(key, now)?.takeIf { mode == ProviderMode.CACHE_ONLY || !it.stale }
        val payload = cached?.payload ?: if (mode == ProviderMode.CACHE_ONLY) {
            error("No cached Maven Central results for '${request.query}'")
        } else {
            val metadataUri = URI.create("$CENTRAL_REPOSITORY/${coordinates.group.replace('.', '/')}/${coordinates.artifact}/maven-metadata.xml")
            val fetched = try {
                transport.execute(HttpRequestSpec(metadataUri, expectedContentTypes = XML_TYPES)).body
            } catch (exception: HttpStatusException) {
                if (exception.status != 404) throw exception
                // Not published on Central (for example an androidx artifact). Another provider may have it.
                return ProviderResult(ID, emptyList(), retrievedAtEpochMillis = now, fromCache = false)
            }
            cache.put(CacheEntry(key, fetched, now, SEARCH_TTL_MILLIS))
            fetched
        }
        val versions = MavenMetadata.versions(payload)
            .map { DependencyVersion(it, isStableVersion(it)) }
            .filter { request.includePreRelease || it.stable || it.value == exact.version }
        if (versions.isEmpty()) return ProviderResult(ID, emptyList(), retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
        val evidenceVersion = exact.version?.takeIf { requested -> versions.any { it.value == requested } }
            ?: versions.lastOrNull { it.stable }?.value ?: versions.last().value
        val cacheDetail = cached?.let { if (it.stale) "Stale cache" else "Cached" }
        val candidate = candidate(coordinates, versions.reversed(), evidenceFor(listOf(coordinates to evidenceVersion)).single(), "Central metadata", cacheDetail)
        return ProviderResult(ID, listOf(candidate), retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
    }

    private fun searchText(request: SearchRequest, now: Long): ProviderResult {
        val key = cacheKey(request)
        val cached = cache.get(key, now)?.takeIf { mode == ProviderMode.CACHE_ONLY || !it.stale }
        val payload = cached?.payload ?: if (mode == ProviderMode.CACHE_ONLY) {
            error("No cached Maven Central results for '${request.query}'")
        } else fetch(request.query).also {
            cache.put(CacheEntry(key, it, now, SEARCH_TTL_MILLIS))
        }
        val docs = json.decodeFromString<CentralResponse>(payload.decodeToString()).response.docs
            .filter { request.includePreRelease || isStableVersion(it.latestVersion) }
        val cacheDetail = cached?.let { if (it.stale) "Stale cache" else "Cached" }
        val evidence = evidenceFor(docs.map { Coordinates(it.g, it.a) to it.latestVersion })
        val candidates = docs.zip(evidence).map { (doc, publication) ->
            candidate(Coordinates(doc.g, doc.a), listOf(DependencyVersion(doc.latestVersion, isStableVersion(doc.latestVersion))), publication, "Central search", cacheDetail)
        }
        return ProviderResult(ID, candidates, retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
    }

    private fun evidenceFor(requests: List<Pair<Coordinates, String>>): List<PublicationEvidence> =
        if (mode == ProviderMode.CACHE_ONLY) {
            requests.map { (coordinates, _) -> PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN) }
        } else {
            BoundedEvidenceLookup(evidenceReader).readAll(requests)
        }

    private fun fetch(query: String): ByteArray {
        val encoded = URLEncoder.encode(solrSafe(query), StandardCharsets.UTF_8)
        return transport.execute(
            HttpRequestSpec(
                URI.create("$SEARCH_ENDPOINT?q=$encoded&rows=50&wt=json"),
                expectedContentTypes = setOf("application/json"),
            ),
        ).body
    }

    private fun candidate(
        requested: Coordinates,
        versions: List<DependencyVersion>,
        publication: PublicationEvidence,
        source: String,
        cacheDetail: String?,
    ): Candidate {
        val canonical = publication.canonicalCoordinates ?: requested
        val detail = buildString {
            append(source)
            cacheDetail?.let { append("; $it") }
            if (publication.rawTargetNames.isNotEmpty()) append("; targets=${publication.rawTargetNames.sorted().joinToString(",")}")
            publication.detail?.let { append("; $it") }
        }
        return Candidate(
            canonical,
            canonical.artifact,
            versions,
            publication.supportedTargets,
            publication.evidence,
            setOf(Provenance(ID, detail)),
        )
    }

    private fun cacheKey(request: SearchRequest) =
        SearchCacheKey(ID, request.query, request.requiredTargets.map { it.name }.toSet(), request.includePreRelease)

    private companion object {
        const val ID = "maven-central"
        const val SEARCH_ENDPOINT = "https://search.maven.org/solrsearch/select"
        const val CENTRAL_REPOSITORY = "https://repo1.maven.org/maven2"
        const val SEARCH_TTL_MILLIS = 86_400_000L
        val XML_TYPES = setOf("application/xml", "text/xml")
        val json = Json { ignoreUnknownKeys = true }

        /** Solr treats `:` and friends as query syntax and answers HTTP 400; free text never needs them. */
        fun solrSafe(query: String): String = query.replace(Regex("""[:\\/^"~*?(){}\[\]!]+"""), " ").trim().replace(Regex("\\s+"), " ")
    }
}

@Serializable private data class CentralResponse(val response: CentralDocs)
@Serializable private data class CentralDocs(val docs: List<CentralDoc>)
@Serializable private data class CentralDoc(val g: String, val a: String, val latestVersion: String)

internal fun isStableVersion(version: String): Boolean =
    !Regex("(?i)(?:^|[._-])(alpha|beta|rc|cr|m|milestone|snapshot|dev|eap|preview)(?:[._-]?\\d*)").containsMatchIn(version)
