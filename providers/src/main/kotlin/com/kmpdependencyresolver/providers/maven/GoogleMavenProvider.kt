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
import com.kmpdependencyresolver.providers.http.HttpTransport
import com.kmpdependencyresolver.providers.ProviderMode
import java.net.URI
import org.w3c.dom.Element

class GoogleMavenProvider(
    private val transport: HttpTransport,
    private val cache: SearchCache,
    private val evidenceReader: PublicationEvidenceReader = RepositoryPublicationEvidenceReader(transport, GOOGLE_REPOSITORY),
    private val clock: () -> Long = System::currentTimeMillis,
    private val mode: ProviderMode = ProviderMode.ONLINE,
) : SearchProvider {
    override val id: String get() = ID

    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            val payloads = mutableListOf<CachedXml>()
            fun cachedXml(id: String, url: String): org.w3c.dom.Document {
                val payload = fetchCached(id, url, request, now)
                payloads += payload
                return MavenMetadata.parse(payload.bytes)
            }
            val groups = cachedXml("master", MASTER_INDEX).documentElement.childElements().map { it.tagName }.toSet()
            val exact = CoordinateQuery.parse(request.query)
            val matches: List<Pair<Coordinates, Element>> = if (exact != null) {
                val coordinates = exact.coordinates
                if (coordinates.group !in groups) return@runCatching emptyResult(now)
                cachedXml("group:${coordinates.group}", groupIndexUrl(coordinates.group)).documentElement.childElements()
                    .filter { it.tagName == coordinates.artifact }
                    .map { coordinates to it }
            } else {
                val tokens = queryTokens(request.query)
                if (tokens.isEmpty()) return@runCatching emptyResult(now)
                matchingGroups(groups, tokens).flatMap { group ->
                    cachedXml("group:$group", groupIndexUrl(group)).documentElement.childElements()
                        .map { Coordinates(group, it.tagName) to it }
                        .filter { (coordinates, _) -> tokens.all { it in coordinates.notation.lowercase() } }
                }.sortedWith(compareBy({ artifactRank(it.first, tokens) }, { it.first.notation.length }, { it.first.notation }))
                    .take(MAX_CANDIDATES)
            }
            val resolved = matches.mapNotNull { (coordinates, element) ->
                val versions = versions(coordinates, element, ::cachedXml)
                    .map { DependencyVersion(it, isStableVersion(it)) }
                    .filter { request.includePreRelease || it.stable || it.value == exact?.version }
                if (versions.isEmpty()) null else coordinates to versions
            }
            val evidence = if (mode == ProviderMode.CACHE_ONLY) {
                resolved.map { (coordinates, _) -> PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN) }
            } else {
                BoundedEvidenceLookup(evidenceReader).readAll(
                    resolved.map { (coordinates, versions) ->
                        coordinates to (exact?.version?.takeIf { requested -> versions.any { it.value == requested } } ?: versions.last().value)
                    },
                )
            }
            val cacheDetail = when {
                payloads.any { it.stale } -> "; Stale cache"
                payloads.all { it.fromCache } -> "; Cached"
                else -> ""
            }
            val candidates = resolved.zip(evidence).map { (resolvedEntry, publication) ->
                val (coordinates, versions) = resolvedEntry
                val canonical = publication.canonicalCoordinates ?: coordinates
                Candidate(
                    canonical, canonical.artifact, versions.reversed(), publication.supportedTargets, publication.evidence,
                    setOf(Provenance(ID, "Google Maven group index$cacheDetail" + (publication.detail?.let { "; $it" } ?: ""))),
                )
            }
            ProviderResult(
                ID,
                candidates,
                retrievedAtEpochMillis = payloads.minOfOrNull { it.retrievedAtMillis } ?: now,
                fromCache = payloads.isNotEmpty() && payloads.all { it.fromCache },
            )
        }.getOrElse { exception ->
            ProviderResult(ID, emptyList(), ProviderFailure(ID, exception.message ?: "Google Maven search failed"), now, false)
        }
    }

    /** Google's group-index lists every version inline; only fall back to maven-metadata.xml when it does not. */
    private fun versions(
        coordinates: Coordinates,
        element: Element,
        cachedXml: (String, String) -> org.w3c.dom.Document,
    ): List<String> {
        val inline = element.getAttribute("versions").split(',').map(String::trim).filter(String::isNotBlank)
        if (inline.isNotEmpty()) return inline
        val metadataUrl = "$GOOGLE_REPOSITORY/${coordinates.group.replace('.', '/')}/${coordinates.artifact}/maven-metadata.xml"
        return runCatching { cachedXml("metadata:${coordinates.notation}", metadataUrl) }.getOrNull()
            ?.getElementsByTagName("version")
            ?.let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent.trim() } }
            ?.filter(String::isNotBlank)
            .orEmpty()
    }

    private fun fetchCached(id: String, url: String, request: SearchRequest, now: Long): CachedXml {
        val key = SearchCacheKey("$ID:$id", request.query, request.requiredTargets.map { it.name }.toSet(), request.includePreRelease)
        cache.get(key, now)?.takeIf { mode == ProviderMode.CACHE_ONLY || !it.stale }?.let {
            return CachedXml(it.payload, it.retrievedAtMillis, fromCache = true, stale = it.stale)
        }
        if (mode == ProviderMode.CACHE_ONLY) error("No cached Google Maven results for '${request.query}'")
        val payload = transport.execute(
            HttpRequestSpec(URI.create(url), expectedContentTypes = setOf("application/xml", "text/xml")),
        ).body
        cache.put(CacheEntry(key, payload, now, SEARCH_TTL_MILLIS))
        return CachedXml(payload, now, fromCache = false, stale = false)
    }

    private fun emptyResult(now: Long) = ProviderResult(ID, emptyList(), retrievedAtEpochMillis = now, fromCache = false)

    private companion object {
        const val ID = "google-maven"
        const val GOOGLE_REPOSITORY = "https://dl.google.com/dl/android/maven2"
        const val MASTER_INDEX = "$GOOGLE_REPOSITORY/master-index.xml"
        const val SEARCH_TTL_MILLIS = 86_400_000L
        const val MAX_GROUPS = 10
        const val MAX_CANDIDATES = 20

        fun groupIndexUrl(group: String) = "$GOOGLE_REPOSITORY/${group.replace('.', '/')}/group-index.xml"

        fun queryTokens(query: String): List<String> =
            query.trim().lowercase().split(Regex("[\\s:]+")).filter(String::isNotBlank)

        /**
         * Groups whose name contains a token (`androidx.lifecycle`), that prefix a token (`androidx.room:…`),
         * or that have a segment named after part of a token (`room` in `room-runtime` → `androidx.room`).
         */
        fun matchingGroups(groups: Set<String>, tokens: List<String>): List<String> {
            val fragments = tokens.flatMap { it.split('-') }.filter { it.length >= 3 }.toSet()
            return groups.mapNotNull { group ->
                val score = when {
                    tokens.any { group.contains(it) || it.startsWith(group) } -> 0
                    group.split('.').any { it in fragments } -> 1
                    else -> return@mapNotNull null
                }
                Triple(group, score, group.length)
            }.sortedWith(compareBy({ it.second }, { it.third }, { it.first })).take(MAX_GROUPS).map { it.first }
        }

        fun artifactRank(coordinates: Coordinates, tokens: List<String>): Int {
            val artifact = coordinates.artifact.lowercase()
            return when {
                tokens.any { it == artifact } -> 0
                tokens.any { artifact.startsWith(it) } -> 1
                else -> 2
            }
        }
    }
}

private data class CachedXml(
    val bytes: ByteArray,
    val retrievedAtMillis: Long,
    val fromCache: Boolean,
    val stale: Boolean,
)
