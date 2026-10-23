package com.kmpdependencyresolver.plugin.project

import com.intellij.openapi.project.Project
import com.kmpdependencyresolver.core.model.SourceSetNode
import com.kmpdependencyresolver.core.model.TargetFamily

enum class AddCapability { APPLY, COPY_ONLY }

data class GradleModuleSnapshot(
    val id: String,
    val buildFile: String,
    val targets: Set<TargetFamily>,
    val sourceSets: List<SourceSetNode>,
    val pluginAliases: Set<String>,
    val configurations: Set<String>,
    val copyOnlyReasons: List<String> = emptyList(),
    val sourceSetTargetNames: Map<String, List<String>> = emptyMap(),
)

data class ProjectSnapshot(
    val rootPath: String,
    val catalogPath: String?,
    val modules: List<GradleModuleSnapshot>,
    val versions: Map<String, String>,
    val existingAliases: Set<String>,
) {
    fun addCapability(moduleId: String): AddCapability =
        if (modules.firstOrNull { it.id == moduleId }?.copyOnlyReasons.isNullOrEmpty()) AddCapability.APPLY
        else AddCapability.COPY_ONLY

    /**
     * Why Add cannot edit [moduleId], phrased for the user, or null when Add is available. Plain JVM or
     * Android modules are out of scope on purpose: Add edits Kotlin Multiplatform modules only.
     */
    fun addUnavailableReason(moduleId: String): String? {
        val module = modules.firstOrNull { it.id == moduleId } ?: return "Module $moduleId is no longer part of the project."
        return when {
            module.buildFile.endsWith(".kts") && module.targets.isEmpty() ->
                "$moduleId isn't a Kotlin Multiplatform module. Add supports KMP modules only; use Copy to get the declaration."
            module.copyOnlyReasons.isNotEmpty() ->
                "Add can't safely edit $moduleId: ${module.copyOnlyReasons.joinToString(" ")} Use Copy instead."
            catalogPath == null ->
                "No gradle/libs.versions.toml was found at the Gradle root, and Add records dependencies there. Use Copy instead."
            else -> null
        }
    }

    /** The module whose project directory is the nearest ancestor of [filePath]. */
    fun moduleContaining(filePath: String): GradleModuleSnapshot? {
        val normalized = filePath.replace('\\', '/')
        return modules
            .filter { module ->
                val dir = module.buildFile.replace('\\', '/').substringBeforeLast('/')
                normalized == dir || normalized.startsWith("$dir/")
            }
            .maxByOrNull { it.buildFile.length }
    }

    /** The module to preselect: the one containing [filePath], else the first module Add can edit. */
    fun defaultModuleId(filePath: String?): String? =
        filePath?.let(::moduleContaining)?.id
            ?: modules.firstOrNull { addCapability(it.id) == AddCapability.APPLY }?.id
            ?: modules.firstOrNull()?.id
}

fun interface ProjectModelReader {
    fun read(project: Project): ProjectSnapshot
}
