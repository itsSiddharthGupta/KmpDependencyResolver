package com.kmpdependencyresolver.providers.klibs

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
) : SearchProvider {
    override fun search(request: SearchRequest): ProviderResult {
        val now = clock()
        return runCatching {
            var advertisedTools: Set<String>? = null
            val key = SearchCacheKey(ID, request.query, request.requiredTargets.map { it.name }.toSet(), request.includePreRelease)
            val cached = cache.get(key, now)?.takeUnless { it.stale }
            val result = cached?.let { json.parseToJsonElement(it.payload.decodeToString()).jsonObject } ?: run {
                client.initialize()
                advertisedTools = client.listTools()
                if ("searchProjects" !in advertisedTools.orEmpty()) error("klibs MCP does not advertise searchProjects")
                client.callTool("searchProjects", arguments(request)).also {
                    cache.put(CacheEntry(key, it.toString().encodeToByteArray(), now, SEARCH_TTL_MILLIS))
                }
            }
            val candidates = result.getValue("projects").jsonArray.flatMap { projectElement ->
                val project = projectElement.jsonObject
                val families = project.getValue("targets").jsonArray.mapNotNull { mapTarget(it.jsonPrimitive.content) }.toSet()
                project.getValue("packages").jsonArray.mapNotNull { packageElement ->
                    val pkg = packageElement.jsonObject
                    val coordinates = Coordinates(pkg.getValue("groupId").jsonPrimitive.content, pkg.getValue("artifactId").jsonPrimitive.content)
                    var latest = pkg["latestVersion"]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content }
                    var stable = pkg["latestStableVersion"]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content }
                    if (latest == null && stable == null) {
                        if (advertisedTools == null) {
                            client.initialize()
                            advertisedTools = client.listTools()
                        }
                        if ("getLatestVersion" in advertisedTools.orEmpty()) {
                            val versionResult = client.callTool("getLatestVersion", buildJsonObject {
                                put("groupId", coordinates.group); put("artifactId", coordinates.artifact)
                            })
                            latest = versionResult["latestVersion"]?.let {
                                if (it.toString() == "null") null else it.jsonObject["version"]?.jsonPrimitive?.content
                            }
                            stable = versionResult["latestStableVersion"]?.let {
                                if (it.toString() == "null") null else it.jsonObject["version"]?.jsonPrimitive?.content
                            }
                        }
                    }
                    val versions = listOfNotNull(stable?.let { DependencyVersion(it, true) }, latest?.takeIf { it != stable }?.let { DependencyVersion(it, false) })
                        .filter { request.includePreRelease || it.stable }
                    if (versions.isEmpty()) return@mapNotNull null
                    Candidate(
                        coordinates,
                        pkg["description"]?.jsonPrimitive?.content ?: coordinates.artifact,
                        versions,
                        families,
                        EvidenceKind.VERIFIED,
                        setOf(Provenance(ID, "klibs.io indexed targets=${project.getValue("targets").jsonArray.joinToString { it.jsonPrimitive.content }}")),
                    )
                }
            }
            ProviderResult(ID, candidates, retrievedAtEpochMillis = cached?.retrievedAtMillis ?: now, fromCache = cached != null)
        }.getOrElse { ProviderResult(ID, emptyList(), ProviderFailure(ID, it.message ?: "klibs search failed"), now, false) }
    }

    private fun arguments(request: SearchRequest) = buildJsonObject {
        put("query", request.query)
        put("maxPackagesPerProject", 10)
        put("targetGroupFilters", buildJsonArray {
            request.requiredTargets.sortedBy { it.name }.forEach { family ->
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
        raw.startsWith("JVM_") -> TargetFamily.JVM
        raw.startsWith("NATIVE_ios_") -> TargetFamily.IOS
        raw.startsWith("NATIVE_macos_") -> TargetFamily.MACOS
        raw.startsWith("NATIVE_linux_") -> TargetFamily.LINUX
        raw.startsWith("NATIVE_mingw_") -> TargetFamily.MINGW
        raw.startsWith("NATIVE_android_") -> TargetFamily.ANDROID
        raw == "JS" -> TargetFamily.JS
        raw == "WASM" -> TargetFamily.WASM
        else -> null
    }

    private companion object {
        const val ID = "klibs"
        const val SEARCH_TTL_MILLIS = 21_600_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
