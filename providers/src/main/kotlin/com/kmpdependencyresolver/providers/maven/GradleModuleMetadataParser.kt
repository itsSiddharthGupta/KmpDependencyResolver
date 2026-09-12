package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PublicationEvidence(
    val canonicalCoordinates: Coordinates?,
    val supportedTargets: Set<TargetFamily>,
    val evidence: EvidenceKind,
    val rawTargetNames: Set<String> = emptySet(),
    val detail: String? = null,
)

fun interface PublicationEvidenceReader {
    fun read(coordinates: Coordinates, version: String): PublicationEvidence
}

class GradleModuleMetadataParser {
    fun parse(payload: ByteArray): PublicationEvidence = runCatching {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val component = root.getValue("component").jsonObject
        val canonical = Coordinates(
            component.getValue("group").jsonPrimitive.content,
            component.getValue("module").jsonPrimitive.content,
        )
        val variants = root.getValue("variants").jsonArray
        val rawTargets = linkedSetOf<String>()
        val families = variants.mapNotNull { variant ->
            val attributes = variant.jsonObject["attributes"]?.jsonObject ?: return@mapNotNull null
            val platform = attributes[PLATFORM_TYPE]?.jsonPrimitive?.content ?: return@mapNotNull null
            val nativeTarget = attributes[NATIVE_TARGET]?.jsonPrimitive?.content
            require(nativeTarget == null || platform.equals("native", ignoreCase = true)) {
                "Native target contradicts platform type"
            }
            nativeTarget?.let(rawTargets::add)
            targetFamily(platform, nativeTarget)
        }.toSet()
        PublicationEvidence(
            canonical,
            families,
            if (families.isEmpty()) EvidenceKind.UNKNOWN else EvidenceKind.VERIFIED,
            rawTargets,
            if (families.isEmpty()) "No supported target families in Gradle metadata" else null,
        )
    }.getOrElse {
        PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Malformed Gradle module metadata")
    }

    private companion object {
        const val PLATFORM_TYPE = "org.jetbrains.kotlin.platform.type"
        const val NATIVE_TARGET = "org.jetbrains.kotlin.native.target"
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun targetFamily(platformType: String, nativeTarget: String?): TargetFamily? = when (platformType.lowercase()) {
    "androidjvm" -> TargetFamily.ANDROID
    "jvm" -> TargetFamily.JVM
    "js" -> TargetFamily.JS
    "wasm" -> TargetFamily.WASM
    "native" -> when {
        nativeTarget == null -> null
        nativeTarget.startsWith("ios_") -> TargetFamily.IOS
        nativeTarget.startsWith("macos_") -> TargetFamily.MACOS
        nativeTarget.startsWith("linux_") -> TargetFamily.LINUX
        nativeTarget.startsWith("mingw_") -> TargetFamily.MINGW
        nativeTarget.startsWith("android_") -> TargetFamily.ANDROID
        else -> null
    }
    else -> null
}

class RepositoryPublicationEvidenceReader(
    private val transport: HttpTransport,
    private val repositoryBaseUrl: String,
) : PublicationEvidenceReader {
    override fun read(coordinates: Coordinates, version: String): PublicationEvidence {
        val base = repositoryBaseUrl.trimEnd('/') + "/" + coordinates.group.replace('.', '/') +
            "/${coordinates.artifact}/$version/${coordinates.artifact}-$version"
        val primary = runCatching {
            val module = transport.execute(
                HttpRequestSpec(URI.create("$base.module"), expectedContentTypes = setOf("application/json")),
            )
            if (module.status == 200) GradleModuleMetadataParser().parse(module.body)
            else PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Gradle metadata unavailable")
        }.getOrElse {
            PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Gradle metadata unavailable")
        }
        if (primary.evidence == EvidenceKind.VERIFIED && primary.supportedTargets.isNotEmpty()) return primary

        val supplement = runCatching {
            val tooling = transport.execute(
                HttpRequestSpec(
                    URI.create("$base-kotlin-tooling-metadata.json"),
                    expectedContentTypes = setOf("application/json"),
                ),
            )
            if (tooling.status == 200) KotlinToolingMetadataParser().parse(tooling.body)
            else PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Publication metadata unavailable")
        }.getOrElse {
            PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Publication metadata unavailable")
        }
        return supplement.copy(canonicalCoordinates = primary.canonicalCoordinates ?: coordinates)
    }
}
