package com.kmpdependencyresolver.core.model

enum class TargetFamily {
    ANDROID,
    JVM,
    IOS,
    MACOS,
    LINUX,
    MINGW,
    JS,
    WASM,
}

data class SourceSetNode(
    val name: String,
    val descendantTargets: Set<TargetFamily>,
    val dependsOn: Set<String> = emptySet(),
)

data class ModuleModel(
    val id: String,
    val sourceSets: List<SourceSetNode>,
)

data class PlacementRecommendation(
    val sourceSetName: String?,
    val evidence: EvidenceKind,
    val explanation: String,
    val requiresOverride: Boolean,
)
