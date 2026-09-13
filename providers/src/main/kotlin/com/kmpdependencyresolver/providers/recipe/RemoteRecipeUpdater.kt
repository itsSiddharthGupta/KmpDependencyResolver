package com.kmpdependencyresolver.providers.recipe

import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteRecipeManifest(
    val schemaVersion: Int,
    val catalogUrl: String,
    val byteLength: Int,
    val maxBytes: Int,
    val sha256: String,
    val signature: String,
) {
    fun signingPayload(): ByteArray = listOf(
        schemaVersion.toString(), catalogUrl, byteLength.toString(), maxBytes.toString(), sha256,
    ).joinToString("\n").encodeToByteArray()
}

data class RecipeUpdateResult(
    val catalog: JsonRecipeRegistry,
    val updated: Boolean,
    val code: String? = null,
)

class RemoteRecipeUpdater(
    private val transport: HttpTransport,
    private val cacheDirectory: Path,
    publicKeyBytes: ByteArray,
    private val bundledCatalog: ByteArray,
) {
    private val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyBytes))

    fun update(manifestUri: URI, offline: Boolean = false): RecipeUpdateResult {
        if (offline) return stale()
        if (manifestUri != MANIFEST_URI || !manifestUri.scheme.equals("https", ignoreCase = true)) return stale()
        return try {
            val manifestPayload = transport.execute(
                HttpRequestSpec(manifestUri, expectedContentTypes = JSON_TYPES, maxBytes = MANIFEST_MAX_BYTES),
            )
            require(manifestPayload.status == 200) { "Manifest request failed" }
            val manifest = json.decodeFromString<RemoteRecipeManifest>(manifestPayload.body.decodeToString())
            validateManifest(manifest)
            val catalogPayload = transport.execute(
                HttpRequestSpec(
                    URI.create(manifest.catalogUrl),
                    expectedContentTypes = JSON_TYPES + "application/octet-stream",
                    maxBytes = manifest.maxBytes,
                ),
            )
            require(catalogPayload.status == 200) { "Catalog request failed" }
            require(catalogPayload.body.size == manifest.byteLength) { "Catalog byte length mismatch" }
            require(sha256(catalogPayload.body).equals(manifest.sha256, ignoreCase = true)) { "Catalog digest mismatch" }
            val registry = JsonRecipeRegistry.fromBytes(catalogPayload.body)
            install(catalogPayload.body)
            RecipeUpdateResult(registry, updated = true)
        } catch (_: Exception) {
            stale()
        }
    }

    private fun validateManifest(manifest: RemoteRecipeManifest) {
        require(manifest.schemaVersion == 1) { "Unsupported manifest schema" }
        require(manifest.catalogUrl.startsWith(RELEASE_PREFIX)) { "Catalog URL is not an immutable release asset" }
        require(manifest.maxBytes in 1..CATALOG_MAX_BYTES) { "Invalid catalog size limit" }
        require(manifest.byteLength in 1..manifest.maxBytes) { "Invalid declared catalog length" }
        require(manifest.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid SHA-256" }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(manifest.signingPayload())
        require(verifier.verify(Base64.getDecoder().decode(manifest.signature))) { "Manifest signature is invalid" }
    }

    private fun install(bytes: ByteArray) {
        Files.createDirectories(cacheDirectory)
        val temporary = Files.createTempFile(cacheDirectory, ".catalog-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, cacheDirectory.resolve(CATALOG_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun stale(): RecipeUpdateResult = RecipeUpdateResult(currentCatalog(), false, "CURATED_CATALOG_STALE")

    private fun currentCatalog(): JsonRecipeRegistry {
        val cached = cacheDirectory.resolve(CATALOG_FILE)
        if (Files.isRegularFile(cached)) {
            runCatching { JsonRecipeRegistry.fromBytes(Files.readAllBytes(cached)) }.getOrNull()?.let { return it }
        }
        return JsonRecipeRegistry.fromBytes(bundledCatalog)
    }

    companion object {
        val MANIFEST_URI: URI = URI.create(
            "https://raw.githubusercontent.com/itsSiddharthGupta/KmpDependencyResolver/main/registry/manifest.json",
        )
        const val RELEASE_PREFIX = "https://github.com/itsSiddharthGupta/KmpDependencyResolver/releases/download/"
        const val BUNDLED_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAhMXjkTxFHp5x/yrCZESeWn1106IWK/DVCyiHMOlH7zI="
        private const val CATALOG_FILE = "catalog-v1.json"
        private const val MANIFEST_MAX_BYTES = 64 * 1024
        private const val CATALOG_MAX_BYTES = 1_048_576
        private val JSON_TYPES = setOf("application/json", "text/plain")
        private val json = Json { ignoreUnknownKeys = false }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
