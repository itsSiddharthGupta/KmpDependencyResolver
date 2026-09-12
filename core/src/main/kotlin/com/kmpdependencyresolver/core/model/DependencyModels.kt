package com.kmpdependencyresolver.core.model

data class Coordinates(
    val group: String,
    val artifact: String,
) {
    val notation: String
        get() = "$group:$artifact"
}

data class DependencyVersion(
    val value: String,
    val stable: Boolean,
)

enum class EvidenceKind {
    VERIFIED,
    CURATED,
    INFERRED,
    UNKNOWN,
    INCOMPATIBLE,
}

data class Provenance(
    val providerId: String,
    val detail: String? = null,
)

data class Candidate(
    val coordinates: Coordinates,
    val displayName: String,
    val versions: List<DependencyVersion>,
    val supportedTargets: Set<TargetFamily>,
    val evidence: EvidenceKind,
    val provenance: Set<Provenance>,
)
