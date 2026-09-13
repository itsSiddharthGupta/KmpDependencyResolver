package com.kmpdependencyresolver.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.kmpdependencyresolver.core.change.CatalogLibrary
import com.kmpdependencyresolver.core.change.CatalogPlugin
import com.kmpdependencyresolver.core.change.CatalogState
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.core.change.ChangePlanner
import com.kmpdependencyresolver.core.change.ChangeProjectSnapshot
import com.kmpdependencyresolver.core.change.DependencySelection
import com.kmpdependencyresolver.core.change.PlanResult
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.plugin.editing.ApplyResult
import com.kmpdependencyresolver.plugin.editing.ChangePreview
import com.kmpdependencyresolver.plugin.editing.ChangePreviewService
import com.kmpdependencyresolver.plugin.editing.PsiChangeApplicator
import com.kmpdependencyresolver.plugin.project.JetBrainsProjectModelReader
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import com.kmpdependencyresolver.plugin.ui.AddDependencyBackend
import com.kmpdependencyresolver.providers.AggregatedSearchResult
import com.kmpdependencyresolver.providers.ProviderSearchCoordinator
import com.kmpdependencyresolver.providers.cache.FileSearchCache
import com.kmpdependencyresolver.providers.http.JdkHttpTransport
import com.kmpdependencyresolver.providers.klibs.KlibsProvider
import com.kmpdependencyresolver.providers.klibs.StreamableHttpMcpClient
import com.kmpdependencyresolver.providers.maven.GoogleMavenProvider
import com.kmpdependencyresolver.providers.maven.MavenCentralProvider
import com.kmpdependencyresolver.providers.recipe.JsonRecipeRegistry
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DependencyResolverService(private val project: Project) : Disposable, AddDependencyBackend {
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
    private val planner = ChangePlanner()
    private val recipeRegistry by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/recipes/bundled-recipes.json")) {
            "Bundled recipes are unavailable"
        }.use { it.readBytes() }
        JsonRecipeRegistry.fromBytes(bytes)
    }

    fun search(request: SearchRequest): AggregatedSearchResult = coordinator.search(request)
    fun projectSnapshot(): ProjectSnapshot = modelReader.read(project)
    override fun snapshot(): ProjectSnapshot = projectSnapshot()
    override fun recipes(candidate: Candidate): List<DependencyRecipe> = recipeRegistry.find(candidate.coordinates)
    override fun plan(selection: DependencySelection, project: ChangeProjectSnapshot): PlanResult = planner.plan(selection, project)
    override fun preview(plan: ChangePlan, snapshot: ProjectSnapshot): ChangePreview = ChangePreviewService(project).preview(plan, snapshot)
    override fun apply(preview: ChangePreview): ApplyResult = PsiChangeApplicator(project).apply(preview)

    override fun projectFor(snapshot: ProjectSnapshot, moduleId: String): ChangeProjectSnapshot {
        val module = snapshot.modules.firstOrNull { it.id == moduleId }
            ?: error("Module $moduleId is unavailable")
        val catalogText = snapshot.catalogPath
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            ?.let(VfsUtilCore::loadText).orEmpty()
        return ChangeProjectSnapshot(moduleId, parseCatalog(catalogText), module.configurations)
    }

    fun activeModuleId(snapshot: ProjectSnapshot = projectSnapshot()): String? {
        val selected = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return snapshot.modules.firstOrNull()?.id
        val moduleName = ModuleUtilCore.findModuleForFile(selected, project)?.name ?: return snapshot.modules.firstOrNull()?.id
        return snapshot.modules.firstOrNull { it.id.substringAfterLast(':') == moduleName || it.id == moduleName }?.id
            ?: snapshot.modules.firstOrNull()?.id
    }

    override fun dispose() = coordinator.close()

    private fun parseCatalog(text: String): CatalogState {
        fun section(name: String) = text.substringAfter("[$name]", "").substringBefore("\n[")
        val versions = ENTRY.findAll(section("versions")).associate { it.groupValues[1] to it.groupValues[2] }
        val libraries = TABLE_ENTRY.findAll(section("libraries")).mapNotNull { match ->
            val notation = stringField("module").find(match.groupValues[2])?.groupValues?.get(1) ?: return@mapNotNull null
            match.groupValues[1] to CatalogLibrary(
                Coordinates(notation.substringBefore(':'), notation.substringAfter(':')),
                stringField("version.ref").find(match.groupValues[2])?.groupValues?.get(1),
            )
        }.toMap()
        val plugins = TABLE_ENTRY.findAll(section("plugins")).mapNotNull { match ->
            val id = stringField("id").find(match.groupValues[2])?.groupValues?.get(1) ?: return@mapNotNull null
            val version = stringField("version.ref").find(match.groupValues[2])?.groupValues?.get(1) ?: return@mapNotNull null
            match.groupValues[1] to CatalogPlugin(id, version)
        }.toMap()
        return CatalogState(versions, libraries, plugins)
    }

    private companion object {
        val ENTRY = Regex("(?m)^([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]*)\"")
        val TABLE_ENTRY = Regex("(?m)^([A-Za-z0-9_.-]+)\\s*=\\s*\\{([^}]*)}")
        fun stringField(name: String) = Regex("(?:^|[,\\s])${Regex.escape(name)}\\s*=\\s*\"([^\"]+)\"")
    }
}
