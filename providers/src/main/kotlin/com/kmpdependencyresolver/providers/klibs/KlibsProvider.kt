package com.kmpdependencyresolver.providers.klibs

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.CoordinateQuery
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.ProviderMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class KlibsProvider(
    private val client: McpClient,
    private val cache: SearchCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mode: ProviderMode = ProviderMode.ONLINE,
) : SearchProvider {
    override val id: String get() = ID

    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            var advertisedTools: Set<String>? = null
            fun tools(): Set<String> = advertisedTools ?: run {
                client.initialize()
                client.listTools().also { advertisedTools = it }
            }
            val exact = CoordinateQuery.parse(request.query)
            val key = SearchCacheKey(ID, request.query, request.requiredTargets.map { it.name }.toSet(), request.includePreRelease)
            val cached = cache.get(key, now)?.takeIf { mode == ProviderMode.CACHE_ONLY || !it.stale }
            val result = cached?.let { json.parseToJsonElement(it.payload.decodeToString()).jsonObject } ?: run {
                if (mode == ProviderMode.CACHE_ONLY) error("No cached klibs.io results for '${request.query}'")
                if ("searchProjects" !in tools()) error("klibs MCP does not advertise searchProjects")
                val search = client.callTool("searchProjects", arguments(request, exact))
                val combined = if (exact != null && "getLatestVersion" in tools()) {
                    // searchProjects returns only five projects with their newest packages, so an exact
                    // coordinate may be absent from it; getLatestVersion looks the package up directly.
                    val latest = client.callTool("getLatestVersion", buildJsonObject {
                        put("groupId", exact.coordinates.group); put("artifactId", exact.coordinates.artifact)
                    })
                    JsonObject(search + (EXACT_KEY to latest))
                } else search
                combined.also { cache.put(CacheEntry(key, it.toString().encodeToByteArray(), now, SEARCH_TTL_MILLIS)) }
            }
            val cacheDetail = cached?.let { if (it.stale) "; Stale cache" else "; Cached" }.orEmpty()
            val projects = result["projects"]?.jsonArray.orEmpty().map { it.jsonObject }
            val candidates = projects.flatMap { project ->
                val rawTargets = project["targets"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                val families = rawTargets.mapNotNull(::mapTarget).toSet()
                project["packages"]?.jsonArray.orEmpty().mapNotNull { packageElement ->
                    val pkg = packageElement.jsonObject
                    val coordinates = Coordinates(pkg.getValue("groupId").jsonPrimitive.content, pkg.getValue("artifactId").jsonPrimitive.content)
                    if (exact != null && coordinates != exact.coordinates) return@mapNotNull null
                    var latest = pkg.stringOrNull("latestVersion")
                    var stable = pkg.stringOrNull("latestStableVersion")
                    if (latest == null && stable == null && mode == ProviderMode.ONLINE && "getLatestVersion" in tools()) {
                        val versionResult = client.callTool("getLatestVersion", buildJsonObject {
                            put("groupId", coordinates.group); put("artifactId", coordinates.artifact)
                        })
                        latest = versionResult.versionOrNull("latestVersion")
                        stable = versionResult.versionOrNull("latestStableVersion")
                    }
                    val versions = versions(stable, latest, request.includePreRelease)
                    if (versions.isEmpty()) return@mapNotNull null
                    Candidate(
                        coordinates,
                        pkg.stringOrNull("description") ?: coordinates.artifact,
                        versions,
                        families,
                        EvidenceKind.VERIFIED,
                        setOf(Provenance(ID, "klibs.io indexed targets=${rawTargets.joinToString()}$cacheDetail")),
                    )
                }
            }
            val exactCandidate = exact?.takeIf { requested -> candidates.none { it.coordinates == requested.coordinates } }
                ?.let { requested ->
                    val latest = result[EXACT_KEY]?.jsonObject ?: return@let null
                    val versions = versions(latest.versionOrNull("latestStableVersion"), latest.versionOrNull("latestVersion"), request.includePreRelease)
                    if (versions.isEmpty()) return@let null
                    // Indexed by klibs.io, but target coverage was not part of this response.
                    Candidate(
                        requested.coordinates, requested.coordinates.artifact, versions, emptySet(), EvidenceKind.UNKNOWN,
                        setOf(Provenance(ID, "klibs.io latest version lookup$cacheDetail")),
                    )
                }
            ProviderResult(ID, candidates + listOfNotNull(exactCandidate), retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
        }.getOrElse { ProviderResult(ID, emptyList(), ProviderFailure(ID, it.message ?: "klibs search failed"), now, false) }
    }

    private fun versions(stable: String?, latest: String?, includePreRelease: Boolean) =
        listOfNotNull(stable?.let { DependencyVersion(it, true) }, latest?.takeIf { it != stable }?.let { DependencyVersion(it, false) })
            .filter { includePreRelease || it.stable }

    private fun arguments(request: SearchRequest, exact: CoordinateQuery?) = buildJsonObject {
        put("query", exact?.coordinates?.artifact ?: request.query)
        put("maxPackagesPerProject", if (exact != null) EXACT_MAX_PACKAGES else 10)
        put("targetGroupFilters", buildJsonArray {
            // klibs.io's AndroidJvm group filter drops projects indexed with the bare ANDROIDJVM target
            // (Ktor, Coil), so Android coverage is judged client-side from the returned targets instead.
            request.requiredTargets.filter { it != TargetFamily.ANDROID }.sortedBy { it.name }.forEach { family ->
                add(buildJsonObject { put(groupName(family), JsonArray(emptyList())) })
            }
        })
    }

    private fun groupName(target: TargetFamily) = when (target) {
        TargetFamily.ANDROID -> "AndroidJvm"; TargetFamily.JVM -> "JVM"; TargetFamily.IOS -> "IOS"
        TargetFamily.MACOS -> "MacOS"; TargetFamily.LINUX -> "Linux"; TargetFamily.MINGW -> "Windows"
        TargetFamily.JS -> "JavaScript"; TargetFamily.WASM -> "Wasm"
    }

    private fun mapTarget(raw: String): TargetFamily? = when {
        raw.startsWith("ANDROIDJVM") -> TargetFamily.ANDROID
        raw == "JVM" || raw.startsWith("JVM_") -> TargetFamily.JVM
        raw.startsWith("NATIVE_ios_") -> TargetFamily.IOS
        raw.startsWith("NATIVE_macos_") -> TargetFamily.MACOS
        raw.startsWith("NATIVE_linux_") -> TargetFamily.LINUX
        raw.startsWith("NATIVE_mingw_") -> TargetFamily.MINGW
        raw.startsWith("NATIVE_android_") -> TargetFamily.ANDROID
        raw == "JS" -> TargetFamily.JS
        raw.startsWith("WASM") -> TargetFamily.WASM
        else -> null
    }

    private companion object {
        const val ID = "klibs"
        const val SEARCH_TTL_MILLIS = 21_600_000L
        const val EXACT_MAX_PACKAGES = 200
        const val EXACT_KEY = "exactLatestVersion"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.versionOrNull(name: String): String? =
    (this[name] as? JsonObject)?.stringOrNull("version")
