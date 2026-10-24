package com.kmpdependencyresolver.providers.klibs

import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.cache.CacheEntry
import com.kmpdependencyresolver.providers.cache.CachedPayload
import com.kmpdependencyresolver.providers.cache.SearchCache
import com.kmpdependencyresolver.providers.cache.SearchCacheKey
import com.kmpdependencyresolver.providers.ProviderMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KlibsProviderTest {
    @Test
    fun `maps target filters coordinates versions and six hour cache ttl`() {
        val client = FixtureMcpClient(resource("search-projects.json"))
        val cache = KlibsRecordingCache()
        val provider = KlibsProvider(client, cache, clock = { 100 })

        val result = provider.search(
            SearchRequest("serialization", setOf(TargetFamily.IOS, TargetFamily.JVM), includePreRelease = false),
        )

        assertThat(client.toolsListed).isTrue()
        assertThat(client.arguments.toString()).contains("IOS", "JVM")
        val candidate = result.candidates.single()
        assertThat(candidate.coordinates.notation).isEqualTo("org.jetbrains.kotlinx:kotlinx-serialization-json")
        assertThat(candidate.versions).extracting<String> { it.value }.containsExactly("1.11.0")
        assertThat(candidate.supportedTargets).contains(
            TargetFamily.IOS, TargetFamily.JVM, TargetFamily.JS, TargetFamily.WASM,
            TargetFamily.LINUX, TargetFamily.MACOS, TargetFamily.MINGW,
        )
        assertThat(cache.lastPut!!.ttlMillis).isEqualTo(21_600_000)
    }

    @Test
    fun `uses advertised latest version tool when search omits versions`() {
        val search = Json.parseToJsonElement(
            """{"projects":[{"projectName":"sample","targets":["JVM_17"],"packages":[{"groupId":"dev.sample","artifactId":"library"}]}]}""",
        ).jsonObject
        val latest = Json.parseToJsonElement(
            """{"latestVersion":{"version":"2.0.0-RC1"},"latestStableVersion":{"version":"1.9.0"},"packageFound":true}""",
        ).jsonObject
        val client = FixtureMcpClient(search, latest)

        val result = KlibsProvider(client, KlibsRecordingCache(), clock = { 1 })
            .search(SearchRequest("sample", emptySet(), includePreRelease = true))

        assertThat(client.calledTools).containsExactly("searchProjects", "getLatestVersion")
        assertThat(result.candidates.single().versions).extracting<String> { it.value }
            .containsExactly("1.9.0", "2.0.0-RC1")
    }

    @Test
    fun `cache only mode returns stale results without MCP calls`() {
        val cachedSearch = resource("search-projects.json")
        val client = FixtureMcpClient(cachedSearch)
        val cache = KlibsFixedCache(CachedPayload(cachedSearch.toString().encodeToByteArray(), 100, stale = true))
        val provider = KlibsProvider(client, cache, clock = { 1_000 }, mode = ProviderMode.CACHE_ONLY)

        val result = provider.search(SearchRequest("serialization", emptySet(), includePreRelease = false))

        assertThat(result.candidates).isNotEmpty()
        assertThat(result.fromCache).isTrue()
        assertThat(result.candidates.first().provenance.single().detail).contains("Stale cache")
        assertThat(client.toolsListed).isFalse()
        assertThat(client.calledTools).isEmpty()
    }

    @Test
    fun `exact coordinates use getLatestVersion and keep targets from the matching project`() {
        val search = Json.parseToJsonElement(
            """{"projects":[{"projectName":"ktor","targets":["ANDROIDJVM","COMMON","JVM_1.8","NATIVE_ios_arm64"],
               "packages":[{"groupId":"io.ktor","artifactId":"ktor-client-darwin","latestVersion":"3.6.0","latestStableVersion":"3.6.0"},
                           {"groupId":"io.ktor","artifactId":"ktor-client-core","latestVersion":"3.6.0","latestStableVersion":"3.6.0"}]}]}""",
        ).jsonObject
        val latest = Json.parseToJsonElement("""{"latestVersion":{"version":"3.6.0"},"latestStableVersion":{"version":"3.6.0"},"packageFound":true}""").jsonObject
        val client = FixtureMcpClient(search, latest)

        val result = KlibsProvider(client, KlibsRecordingCache(), clock = { 1 })
            .search(SearchRequest("io.ktor:ktor-client-core", setOf(TargetFamily.ANDROID, TargetFamily.IOS), includePreRelease = false))

        assertThat(client.searchArguments.toString()).contains("\"query\":\"ktor-client-core\"", "\"maxPackagesPerProject\":200", "IOS")
            .doesNotContain("AndroidJvm")
        assertThat(client.calledTools).containsExactly("searchProjects", "getLatestVersion")
        val candidate = result.candidates.single()
        assertThat(candidate.coordinates.notation).isEqualTo("io.ktor:ktor-client-core")
        assertThat(candidate.supportedTargets).containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.JVM, TargetFamily.IOS)
        assertThat(candidate.evidence).isEqualTo(EvidenceKind.VERIFIED)
    }

    @Test
    fun `exact coordinates missing from search still return the looked up version without claiming targets`() {
        val search = Json.parseToJsonElement("""{"projects":[]}""").jsonObject
        val latest = Json.parseToJsonElement("""{"latestVersion":{"version":"2.8.5"},"latestStableVersion":{"version":"2.8.5"},"packageFound":true}""").jsonObject

        val result = KlibsProvider(FixtureMcpClient(search, latest), KlibsRecordingCache(), clock = { 1 })
            .search(SearchRequest("androidx.room:room-runtime", emptySet(), includePreRelease = false))

        val candidate = result.candidates.single()
        assertThat(candidate.coordinates.notation).isEqualTo("androidx.room:room-runtime")
        assertThat(candidate.versions).extracting<String> { it.value }.containsExactly("2.8.5")
        assertThat(candidate.supportedTargets).isEmpty()
        assertThat(candidate.evidence).isEqualTo(EvidenceKind.UNKNOWN)
    }

    private fun resource(name: String): JsonObject {
        val envelope = checkNotNull(javaClass.getResourceAsStream("/klibs/$name")).use { it.readAllBytes().decodeToString() }
        val root = Json.parseToJsonElement(envelope).jsonObject
        val text = root["result"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }
}

private class FixtureMcpClient(
    private val search: JsonObject,
    private val latest: JsonObject = JsonObject(emptyMap()),
) : McpClient {
    var toolsListed = false
    var arguments: JsonObject = JsonObject(emptyMap())
    var searchArguments: JsonObject = JsonObject(emptyMap())
    val calledTools = mutableListOf<String>()
    override fun initialize() = Unit
    override fun listTools(): Set<String> { toolsListed = true; return setOf("getLatestVersion", "searchProjects") }
    override fun callTool(name: String, arguments: JsonObject): JsonObject {
        calledTools += name
        this.arguments = arguments
        if (name == "searchProjects") searchArguments = arguments
        return if (name == "getLatestVersion") latest else search
    }
}

private class KlibsRecordingCache : SearchCache {
    var lastPut: CacheEntry? = null
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload? = null
    override fun put(entry: CacheEntry) { lastPut = entry }
}

private class KlibsFixedCache(private val payload: CachedPayload) : SearchCache {
    override fun get(key: SearchCacheKey, nowMillis: Long): CachedPayload = payload
    override fun put(entry: CacheEntry) = error("Cache-only search must not write")
}
