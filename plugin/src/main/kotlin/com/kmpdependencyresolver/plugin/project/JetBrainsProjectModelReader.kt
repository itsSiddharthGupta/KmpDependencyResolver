package com.kmpdependencyresolver.plugin.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.kotlin.psi.KtPsiFactory

class JetBrainsProjectModelReader(
    private val inspector: KotlinDslSourceSetInspector = KotlinDslSourceSetInspector(),
) : ProjectModelReader {
    override fun read(project: Project): ProjectSnapshot = ReadAction.compute<ProjectSnapshot, RuntimeException> {
        val root = checkNotNull(project.basePath) { "Project has no base path" }
        val buildFiles = linkedSetOf<com.intellij.openapi.vfs.VirtualFile>()
        val catalogs = linkedSetOf<com.intellij.openapi.vfs.VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && (file.name == "build.gradle.kts" || file.name == "build.gradle")) buildFiles += file
            if (!file.isDirectory && file.name == "libs.versions.toml" && file.parent.name == "gradle") catalogs += file
            true
        }
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(root)
        if (buildFiles.isEmpty() && rootFile != null) {
            VfsUtilCore.iterateChildrenRecursively(rootFile, null) { file ->
                if (!file.isDirectory && (file.name == "build.gradle.kts" || file.name == "build.gradle")) buildFiles += file
                if (!file.isDirectory && file.name == "libs.versions.toml" && file.parent.name == "gradle") catalogs += file
                true
            }
        }
        // Touch the public module model so imported Gradle content roots participate in discovery.
        ModuleManager.getInstance(project).modules

        val modules = buildFiles.sortedBy { it.path }.map { buildFile ->
            val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(buildFile)
            val relativeDir = contentRoot?.let { VfsUtilCore.getRelativePath(buildFile.parent, it) }
                ?: buildFile.parent.path.removePrefix(root).trim('/')
            val id = if (relativeDir.isEmpty()) ":" else ":" + relativeDir.replace('/', ':')
            if (buildFile.extension == "kts") {
                val ktFile = KtPsiFactory(project, markGenerated = false).createFile(buildFile.name, VfsUtilCore.loadText(buildFile))
                val result = inspector.inspect(ktFile)
                GradleModuleSnapshot(id, buildFile.path, result.targets, result.sourceSets, result.pluginAliases, result.configurations, result.copyOnlyReasons)
            } else {
                GradleModuleSnapshot(
                    id, buildFile.path, emptySet(), emptyList(), emptySet(), emptySet(),
                    listOf("Groovy build scripts are not safely editable; Kotlin DSL is required."),
                )
            }
        }
        val catalog = rootFile?.findFileByRelativePath("gradle/libs.versions.toml") ?: catalogs.firstOrNull()
        val catalogText = catalog?.let(VfsUtilCore::loadText).orEmpty()
        ProjectSnapshot(root, catalog?.path, modules, parseVersions(catalogText), parseAliases(catalogText))
    }

    private fun parseVersions(text: String): Map<String, String> {
        val section = text.substringAfter("[versions]", "").substringBefore("\n[")
        return ENTRY.findAll(section).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun parseAliases(text: String): Set<String> {
        val section = text.substringAfter("[libraries]", "").substringBefore("\n[")
        return KEY.findAll(section).map { it.groupValues[1] }.toSet()
    }

    private companion object {
        val ENTRY = Regex("(?m)^([A-Za-z0-9_.-]+)\\s*=\\s*\\\"([^\\\"]*)\\\"")
        val KEY = Regex("(?m)^([A-Za-z0-9_.-]+)\\s*=")
    }
}
