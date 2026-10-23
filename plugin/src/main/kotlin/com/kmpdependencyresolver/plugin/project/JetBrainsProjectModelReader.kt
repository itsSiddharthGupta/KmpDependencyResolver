package com.kmpdependencyresolver.plugin.project

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.kmpdependencyresolver.core.change.VersionCatalogParser
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.plugins.gradle.util.GradleConstants

class JetBrainsProjectModelReader(
    private val inspector: KotlinDslSourceSetInspector = KotlinDslSourceSetInspector(),
) : ProjectModelReader {
    override fun read(project: Project): ProjectSnapshot = ReadAction.compute<ProjectSnapshot, RuntimeException> {
        val basePath = checkNotNull(project.basePath) { "Project has no base path" }
        val linked = linkedGradleProjects(project, basePath)
        val gradleRoot = linked?.rootPath ?: basePath
        val rootFile = LocalFileSystem.getInstance().findFileByPath(gradleRoot)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(gradleRoot)
        val index = ProjectFileIndex.getInstance(project)
        val buildFiles = linked?.projectDirs?.mapNotNull(::buildFileIn) ?: scanBuildFiles(index, rootFile)

        val modules = buildFiles.distinctBy { it.path }.sortedBy { it.path }.map { buildFile ->
            // Before the first sync the content root is the Gradle root; after it, the linked root is authoritative.
            val idRoot = if (linked != null) gradleRoot else index.getContentRootForFile(buildFile)?.path ?: gradleRoot
            val id = gradlePath(idRoot, buildFile.parent.path)
            if (buildFile.extension == "kts") {
                val ktFile = KtPsiFactory(project, markGenerated = false).createFile(buildFile.name, textOf(buildFile))
                val result = inspector.inspect(ktFile)
                GradleModuleSnapshot(id, buildFile.path, result.targets, result.sourceSets, result.pluginAliases, result.configurations, result.copyOnlyReasons)
            } else {
                GradleModuleSnapshot(
                    id, buildFile.path, emptySet(), emptyList(), emptySet(), emptySet(),
                    listOf("Groovy build scripts are not safely editable; Kotlin DSL is required."),
                )
            }
        }
        val catalog = rootFile?.findFileByRelativePath(CATALOG_PATH)
            ?: buildFiles.firstNotNullOfOrNull { index.getContentRootForFile(it)?.findFileByRelativePath(CATALOG_PATH) }
        val parsed = VersionCatalogParser.parse(catalog?.let(::textOf).orEmpty())
        ProjectSnapshot(gradleRoot, catalog?.path, modules, parsed.versions, parsed.libraries.keys)
    }

    private data class LinkedGradleProjects(val rootPath: String, val projectDirs: Set<String>)

    /**
     * Gradle project directories of the main build, from the IDE's imported Gradle model. Source-set
     * modules (`shared.commonMain`) share their project's directory; buildSrc and included builds such
     * as `build-logic` have a different root project path and are skipped. Null before the first sync.
     */
    private fun linkedGradleProjects(project: Project, basePath: String): LinkedGradleProjects? {
        val gradleModules = ModuleManager.getInstance(project).modules.filter {
            ExternalSystemApiUtil.isExternalSystemAwareModule(GradleConstants.SYSTEM_ID, it)
        }
        val roots = gradleModules.mapNotNull { module ->
            val root = ExternalSystemApiUtil.getExternalRootProjectPath(module) ?: return@mapNotNull null
            val path = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return@mapNotNull null
            normalize(root) to normalize(path)
        }
        if (roots.isEmpty()) return null
        val mainRoot = roots.map { it.first }.firstOrNull { it == normalize(basePath) }
            ?: roots.groupingBy { it.first }.eachCount().maxBy { it.value }.key
        val dirs = roots.filter { it.first == mainRoot }.map { it.second }
            .filterNot { it.endsWith("/buildSrc") || it.contains("/buildSrc/") }
            .toSet()
        return LinkedGradleProjects(mainRoot, dirs)
    }

    /** Fallback before the first Gradle sync: build files in the content, minus build output, buildSrc and nested builds. */
    private fun scanBuildFiles(index: ProjectFileIndex, rootFile: VirtualFile?): List<VirtualFile> {
        val buildFiles = linkedSetOf<VirtualFile>()
        index.iterateContent { file ->
            if (!file.isDirectory && file.name in BUILD_FILE_NAMES && belongsToMainBuild(file, index.getContentRootForFile(file))) buildFiles += file
            true
        }
        if (buildFiles.isEmpty() && rootFile != null) {
            VfsUtilCore.iterateChildrenRecursively(rootFile, { dir ->
                !dir.isDirectory || dir == rootFile || (dir.name !in SKIPPED_DIRS && !isNestedBuild(dir))
            }) { file ->
                if (!file.isDirectory && file.name in BUILD_FILE_NAMES) buildFiles += file
                true
            }
        }
        return buildFiles.toList()
    }

    private fun belongsToMainBuild(buildFile: VirtualFile, root: VirtualFile?): Boolean {
        var dir = buildFile.parent
        while (dir != null && dir != root) {
            if (dir.name in SKIPPED_DIRS || isNestedBuild(dir)) return false
            dir = dir.parent
        }
        return true
    }

    private fun isNestedBuild(dir: VirtualFile) = SETTINGS_FILE_NAMES.any { dir.findChild(it) != null }

    private fun buildFileIn(dir: String): VirtualFile? {
        val directory = LocalFileSystem.getInstance().findFileByPath(dir) ?: return null
        return BUILD_FILE_NAMES.firstNotNullOfOrNull { directory.findChild(it) }
    }

    /** Unsaved editor changes are part of the model; the file on disk may be stale. */
    private fun textOf(file: VirtualFile): String =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)

    private fun normalize(path: String) = path.replace('\\', '/').trimEnd('/')

    companion object {
        private val BUILD_FILE_NAMES = listOf("build.gradle.kts", "build.gradle")
        private val SETTINGS_FILE_NAMES = listOf("settings.gradle.kts", "settings.gradle")
        private const val CATALOG_PATH = "gradle/libs.versions.toml"
        private val SKIPPED_DIRS = setOf("build", "buildSrc", ".gradle", ".idea", ".git", "node_modules", ".kotlin")

        /** `:` for the root project, `:feature:shared` for `<root>/feature/shared`. */
        fun gradlePath(rootPath: String, projectDir: String): String {
            val relative = projectDir.replace('\\', '/').trimEnd('/')
                .removePrefix(rootPath.replace('\\', '/').trimEnd('/'))
                .trim('/')
            return if (relative.isEmpty()) ":" else ":" + relative.replace('/', ':')
        }
    }
}
