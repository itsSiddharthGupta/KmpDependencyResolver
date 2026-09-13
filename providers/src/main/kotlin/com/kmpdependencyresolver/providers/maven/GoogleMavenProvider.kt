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
import com.kmpdependencyresolver.providers.ProviderMode
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class GoogleMavenProvider(
    private val transport: HttpTransport,
    private val cache: SearchCache,
    private val evidenceReader: PublicationEvidenceReader = RepositoryPublicationEvidenceReader(transport, GOOGLE_REPOSITORY),
    private val clock: () -> Long = System::currentTimeMillis,
    private val mode: ProviderMode = ProviderMode.ONLINE,
) : SearchProvider {
    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            val payloads = mutableListOf<CachedXml>()
            fun cachedXml(id: String, url: String): org.w3c.dom.Document {
                val payload = fetchCached(id, url, request, now)
                payloads += payload
                return xml(payload.bytes)
            }
            val master = cachedXml("master", MASTER_INDEX)
            val query = request.query.trim().lowercase()
            val groups = master.documentElement.childElements().map { it.tagName }
                .filter { it.contains(query) || query.startsWith(it) }
            val candidates = groups.flatMap { group ->
                val groupUrl = "$GOOGLE_REPOSITORY/${group.replace('.', '/')}/group-index.xml"
                val document = cachedXml("group:$group", groupUrl)
                document.documentElement.childElements().mapNotNull { artifact ->
                    val coordinates = Coordinates(group, artifact.tagName)
                    if (!coordinates.notation.lowercase().contains(query)) return@mapNotNull null
                    val metadataUrl = "$GOOGLE_REPOSITORY/${group.replace('.', '/')}/${artifact.tagName}/maven-metadata.xml"
                    val metadata = runCatching {
                        cachedXml("metadata:${coordinates.notation}", metadataUrl)
                    }.getOrNull() ?: return@mapNotNull null
                    val versions = metadata.getElementsByTagName("version").let { nodes ->
                        (0 until nodes.length).map { nodes.item(it).textContent.trim() }
                    }.filter(String::isNotBlank).map { DependencyVersion(it, isStableVersion(it)) }
                        .filter { request.includePreRelease || it.stable }
                    if (versions.isEmpty()) return@mapNotNull null
                    val latest = versions.last()
                    val evidence = if (mode == ProviderMode.CACHE_ONLY) {
                        PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN)
                    } else {
                        runCatching { evidenceReader.read(coordinates, latest.value) }
                            .getOrElse { PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN, detail = it.message) }
                    }
                    val canonical = evidence.canonicalCoordinates ?: coordinates
                    val cacheDetail = when {
                        payloads.any { it.stale } -> "; Stale cache"
                        payloads.all { it.fromCache } -> "; Cached"
                        else -> ""
                    }
                    Candidate(
                        canonical, canonical.artifact, versions, evidence.supportedTargets, evidence.evidence,
                        setOf(Provenance(ID, "Google Maven group index$cacheDetail" + (evidence.detail?.let { "; $it" } ?: ""))),
                    )
                }
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

    private fun xml(payload: ByteArray) = factory.newDocumentBuilder().parse(ByteArrayInputStream(payload))

    private companion object {
        const val ID = "google-maven"
        const val GOOGLE_REPOSITORY = "https://dl.google.com/dl/android/maven2"
        const val MASTER_INDEX = "$GOOGLE_REPOSITORY/master-index.xml"
        const val SEARCH_TTL_MILLIS = 86_400_000L
        val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }
}

private data class CachedXml(
    val bytes: ByteArray,
    val retrievedAtMillis: Long,
    val fromCache: Boolean,
    val stale: Boolean,
)

private fun org.w3c.dom.Element.childElements(): List<org.w3c.dom.Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }
