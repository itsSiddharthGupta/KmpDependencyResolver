package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.EvidenceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KotlinToolingMetadataParser {
    fun parse(payload: ByteArray): PublicationEvidence = runCatching {
        val root = json.parseToJsonElement(payload.decodeToString()).jsonObject
        val rawTargets = linkedSetOf<String>()
        val families = root.getValue("projectTargets").jsonArray.mapNotNull { target ->
            val targetObject = target.jsonObject
            val platform = targetObject.getValue("platformType").jsonPrimitive.content
            val native = targetObject["extras"]?.jsonObject
                ?.get("native")?.jsonObject
                ?.get("konanTarget")?.jsonPrimitive?.content
            native?.let(rawTargets::add)
            targetFamily(platform, native)
        }.toSet()
        PublicationEvidence(
            null,
            families,
            if (families.isEmpty()) EvidenceKind.UNKNOWN else EvidenceKind.VERIFIED,
            rawTargets,
            if (families.isEmpty()) "No supported target families in Kotlin tooling metadata" else null,
        )
    }.getOrElse {
        PublicationEvidence(null, emptySet(), EvidenceKind.UNKNOWN, detail = "Malformed Kotlin tooling metadata")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
