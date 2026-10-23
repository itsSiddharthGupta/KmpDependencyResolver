package com.kmpdependencyresolver.providers.recipe

import com.kmpdependencyresolver.providers.http.HttpPayload
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RemoteRecipeUpdaterTest {
    @Test
    fun `offline mode performs no requests and retains bundled catalog`() {
        val fixture = Fixture()

        val result = fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI, offline = true)

        assertThat(fixture.transport.calls).isZero()
        assertThat(result.updated).isFalse()
        assertThat(result.code).isEqualTo("CURATED_CATALOG_STALE")
        assertThat(result.catalog.find(com.kmpdependencyresolver.core.model.Coordinates("io.ktor", "ktor-client-core"))).hasSize(1)
    }

    @Test
    fun `valid signed manifest atomically installs length and digest verified catalog`() {
        val fixture = Fixture()
        fixture.transport.responses += fixture.manifestResponse()
        fixture.transport.responses += payload(fixture.remoteCatalog)

        val result = fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI)

        assertThat(result.updated).isTrue()
        assertThat(fixture.cache.resolve("catalog-v1.json")).hasBinaryContent(fixture.remoteCatalog)
        assertThat(fixture.transport.calls).isEqualTo(2)
    }

    @Test
    fun `only the catalog download may follow GitHub release redirects`() {
        val fixture = Fixture()
        fixture.transport.responses += fixture.manifestResponse()
        fixture.transport.responses += payload(fixture.remoteCatalog)

        fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI)

        val (manifest, catalog) = fixture.transport.requests
        assertThat(manifest.allowedRedirectHosts).isEmpty()
        assertThat(catalog.allowedRedirectHosts).containsExactlyInAnyOrder("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    @Test
    fun `redirected catalog through the real transport installs only when the signed digest matches`() {
        val fixture = Fixture()
        val manifest = fixture.manifestResponse().body
        fun updaterServing(catalog: ByteArray) = RemoteRecipeUpdater(
            com.kmpdependencyresolver.providers.http.JdkHttpTransport(sender = com.kmpdependencyresolver.providers.http.HttpSender { request, _ ->
                val uri = request.uri()
                when {
                    uri.host == "raw.githubusercontent.com" -> FakeResponse(200, "text/plain; charset=utf-8", manifest, uri)
                    uri.host == "github.com" -> FakeResponse(302, null, ByteArray(0), uri, "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc")
                    else -> FakeResponse(200, "application/octet-stream", catalog, uri)
                }
            }),
            Files.createTempDirectory("recipe-cache-"), fixture.keys.public.encoded, fixture.bundledCatalog,
        )

        val genuine = updaterServing(fixture.remoteCatalog).update(RemoteRecipeUpdater.MANIFEST_URI)
        val tampered = updaterServing(fixture.remoteCatalog.copyOf().also { it[it.size - 2] = 'X'.code.toByte() })
            .update(RemoteRecipeUpdater.MANIFEST_URI)

        assertThat(genuine.updated).isTrue()
        assertThat(genuine.catalog.find(com.kmpdependencyresolver.core.model.Coordinates("io.coil-kt.coil3", "coil-compose-core"))).hasSize(1)
        assertThat(tampered.updated).isFalse()
        assertThat(tampered.failure).hasMessage("Catalog digest mismatch")
    }

    private class FakeResponse(
        private val status: Int,
        contentType: String?,
        private val body: ByteArray,
        private val uri: URI,
        location: String? = null,
    ) : java.net.http.HttpResponse<java.io.InputStream> {
        private val headers = java.net.http.HttpHeaders.of(
            buildMap { contentType?.let { put("content-type", listOf(it)) }; location?.let { put("location", listOf(it)) } },
        ) { _, _ -> true }
        override fun statusCode() = status
        override fun headers() = headers
        override fun body(): java.io.InputStream = java.io.ByteArrayInputStream(body)
        override fun request(): java.net.http.HttpRequest = throw UnsupportedOperationException()
        override fun previousResponse(): java.util.Optional<java.net.http.HttpResponse<java.io.InputStream>> = java.util.Optional.empty()
        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()
        override fun uri() = uri
        override fun version() = java.net.http.HttpClient.Version.HTTP_2
    }

    @Test
    fun `untrusted location signature length digest and schema failures preserve last good`() {
        val fixture = Fixture()
        val lastGood = fixture.cache.resolve("catalog-v1.json")
        Files.createDirectories(fixture.cache)
        Files.write(lastGood, fixture.bundledCatalog)

        assertThat(fixture.updater.update(URI.create("http://example.com/manifest.json")).code)
            .isEqualTo("CURATED_CATALOG_STALE")
        assertThat(fixture.transport.calls).isZero()

        fixture.transport.responses += fixture.manifestResponse(signature = Base64.getEncoder().encodeToString(ByteArray(64)))
        assertThat(fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI).updated).isFalse()

        fixture.transport.responses += fixture.manifestResponse(schemaVersion = 2)
        val unsupported = fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI)
        assertThat(unsupported.updated).isFalse()
        assertThat(unsupported.failure).hasMessage("Unsupported manifest schema")

        fixture.transport.responses += fixture.manifestResponse(catalogUrl = "https://example.com/catalog.json")
        assertThat(fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI).updated).isFalse()

        fixture.transport.responses += fixture.manifestResponse(byteLength = fixture.remoteCatalog.size + 1)
        fixture.transport.responses += payload(fixture.remoteCatalog)
        assertThat(fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI).updated).isFalse()

        fixture.transport.responses += fixture.manifestResponse(sha256 = "00".repeat(32))
        fixture.transport.responses += payload(fixture.remoteCatalog)
        assertThat(fixture.updater.update(RemoteRecipeUpdater.MANIFEST_URI).updated).isFalse()

        assertThat(lastGood).hasBinaryContent(fixture.bundledCatalog)
    }

    @Test
    fun `committed manifest authenticates the exact committed catalog`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("registry/manifest.json")) }
        val manifest = Json.decodeFromString<RemoteRecipeManifest>(
            Files.readString(repository.resolve("registry/manifest.json")),
        )
        val catalog = Files.readAllBytes(repository.resolve("registry/catalog-v1.json"))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(RemoteRecipeUpdater.BUNDLED_PUBLIC_KEY_BASE64)),
            ),
        )
        verifier.update(manifest.signingPayload())

        assertThat(verifier.verify(Base64.getDecoder().decode(manifest.signature))).isTrue()
        assertThat(catalog).hasSize(manifest.byteLength)
        assertThat(sha256(catalog)).isEqualTo(manifest.sha256)
    }

    private class Fixture {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val cache = Files.createTempDirectory("recipe-cache-")
        val bundledCatalog = catalog("ktor-client-core", "io.ktor", "ktor-client-core")
        val remoteCatalog = catalog("coil", "io.coil-kt.coil3", "coil-compose-core")
        val transport = QueueTransport()
        val updater = RemoteRecipeUpdater(transport, cache, keys.public.encoded, bundledCatalog)

        fun manifestResponse(
            schemaVersion: Int = 1,
            catalogUrl: String = "https://github.com/itsSiddharthGupta/KmpDependencyResolver/releases/download/v0.1.0/catalog-v1.json",
            byteLength: Int = remoteCatalog.size,
            sha256: String = sha256(remoteCatalog),
            signature: String? = null,
        ): HttpPayload {
            val unsigned = RemoteRecipeManifest(
                schemaVersion = schemaVersion,
                catalogUrl = catalogUrl,
                byteLength = byteLength,
                maxBytes = 1_048_576,
                sha256 = sha256,
                signature = "",
            )
            val encodedSignature = signature ?: Signature.getInstance("Ed25519").run {
                initSign(keys.private)
                update(unsigned.signingPayload())
                Base64.getEncoder().encodeToString(sign())
            }
            val json = """{"schemaVersion":$schemaVersion,"catalogUrl":"${unsigned.catalogUrl}","byteLength":$byteLength,"maxBytes":${unsigned.maxBytes},"sha256":"$sha256","signature":"$encodedSignature"}"""
            return payload(json.encodeToByteArray())
        }
    }

    private class QueueTransport : HttpTransport {
        val responses = ArrayDeque<HttpPayload>()
        val requests = mutableListOf<HttpRequestSpec>()
        var calls = 0
        override fun execute(request: HttpRequestSpec): HttpPayload {
            calls++
            requests += request
            return responses.removeFirst()
        }
    }

    companion object {
        private fun payload(bytes: ByteArray) = HttpPayload(200, mapOf("content-type" to listOf("application/json")), bytes)
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun catalog(id: String, group: String, artifact: String) =
            """{"schemaVersion":1,"recipes":[{"id":"$id","group":"$group","artifact":"$artifact","preferredConfiguration":"implementation","documentationUrl":"https://example.com/docs"}]}""".encodeToByteArray()
    }
}
