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
}

fun interface ProjectModelReader {
    fun read(project: Project): ProjectSnapshot
}
