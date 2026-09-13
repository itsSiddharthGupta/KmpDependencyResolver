package com.kmpdependencyresolver.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.plugin.project.JetBrainsProjectModelReader
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import com.kmpdependencyresolver.providers.AggregatedSearchResult
import com.kmpdependencyresolver.providers.ProviderSearchCoordinator
import com.kmpdependencyresolver.providers.cache.FileSearchCache
import com.kmpdependencyresolver.providers.http.JdkHttpTransport
import com.kmpdependencyresolver.providers.klibs.KlibsProvider
import com.kmpdependencyresolver.providers.klibs.StreamableHttpMcpClient
import com.kmpdependencyresolver.providers.maven.GoogleMavenProvider
import com.kmpdependencyresolver.providers.maven.MavenCentralProvider
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DependencyResolverService(private val project: Project) : Disposable {
    private val transport = JdkHttpTransport()
    private val cache = FileSearchCache(Path.of(PathManager.getSystemPath(), "kmp-dependency-resolver", "cache"))
    private val coordinator = ProviderSearchCoordinator(
        listOf(
            KlibsProvider(StreamableHttpMcpClient(transport), cache),
            MavenCentralProvider(transport, cache),
            GoogleMavenProvider(transport, cache),
        ),
    )
    private val modelReader = JetBrainsProjectModelReader()

    fun search(request: SearchRequest): AggregatedSearchResult = coordinator.search(request)
    fun projectSnapshot(): ProjectSnapshot = modelReader.read(project)

    fun activeModuleId(snapshot: ProjectSnapshot = projectSnapshot()): String? {
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return snapshot.modules.firstOrNull()?.id
        val moduleName = ModuleUtilCore.findModuleForFile(selected, project)?.name ?: return snapshot.modules.firstOrNull()?.id
        return snapshot.modules.firstOrNull { it.id.substringAfterLast(':') == moduleName || it.id == moduleName }?.id
            ?: snapshot.modules.firstOrNull()?.id
    }

    override fun dispose() = coordinator.close()
}
