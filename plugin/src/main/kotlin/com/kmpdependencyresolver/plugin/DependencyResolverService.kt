package com.kmpdependencyresolver.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.core.change.ChangePlanner
import com.kmpdependencyresolver.core.change.ChangeProjectSnapshot
import com.kmpdependencyresolver.core.change.DependencySelection
import com.kmpdependencyresolver.core.change.PlanResult
import com.kmpdependencyresolver.core.change.VersionCatalogParser
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.plugin.editing.ApplyResult
import com.kmpdependencyresolver.plugin.editing.ChangePreview
import com.kmpdependencyresolver.plugin.editing.ChangePreviewService
import com.kmpdependencyresolver.plugin.editing.PsiChangeApplicator
import com.kmpdependencyresolver.plugin.project.JetBrainsProjectModelReader
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import com.kmpdependencyresolver.plugin.settings.ResolverSettings
import com.kmpdependencyresolver.plugin.ui.AddDependencyBackend
import com.kmpdependencyresolver.providers.AggregatedSearchResult
import com.kmpdependencyresolver.providers.ProviderSearchCoordinator
import com.kmpdependencyresolver.providers.ProviderMode
import com.kmpdependencyresolver.providers.cache.FileSearchCache
import com.kmpdependencyresolver.providers.http.JdkHttpTransport
import com.kmpdependencyresolver.providers.klibs.KlibsProvider
import com.kmpdependencyresolver.providers.klibs.StreamableHttpMcpClient
import com.kmpdependencyresolver.providers.maven.GoogleMavenProvider
import com.kmpdependencyresolver.providers.maven.MavenCentralProvider
import com.kmpdependencyresolver.providers.recipe.JsonRecipeRegistry
import com.kmpdependencyresolver.providers.recipe.RemoteRecipeUpdater
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class DependencyResolverService(private val project: Project) : Disposable, AddDependencyBackend {
    private val transport = JdkHttpTransport()
    private val cache = FileSearchCache(Path.of(PathManager.getSystemPath(), "kmp-dependency-resolver", "cache"))
    private val settings = service<ResolverSettings>()
    private val coordinatorLock = Any()
    private var providerConfiguration = currentProviderConfiguration()
    private var coordinator = createCoordinator(providerConfiguration)
    private val modelReader = JetBrainsProjectModelReader()
    private val planner = ChangePlanner()
    private val bundledRecipeBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/recipes/bundled-recipes.json")) {
            "Bundled recipes are unavailable"
        }.use { it.readBytes() }
    }
    @Volatile private var recipeRegistry: JsonRecipeRegistry? = null
    @Volatile private var recipeUpdateCode: String? = null
    private val recipeUpdaterExecutor = Executors.newSingleThreadExecutor()

    init {
        if (settings.state.remoteRecipesEnabled) {
            recipeUpdaterExecutor.submit {
                val updater = RemoteRecipeUpdater(
                    transport,
                    Path.of(PathManager.getSystemPath(), "kmp-dependency-resolver", "cache", "recipes"),
                    Base64.getDecoder().decode(RemoteRecipeUpdater.BUNDLED_PUBLIC_KEY_BASE64),
                    bundledRecipeBytes,
                )
                try {
                    val result = updater.update(RemoteRecipeUpdater.MANIFEST_URI, settings.state.offlineMode)
                    recipeRegistry = result.catalog
                    recipeUpdateCode = result.code
                    result.failure?.let { LOG.warn("Remote recipe update failed; using cached or bundled recipes", it) }
                } catch (exception: Exception) {
                    // ExecutorService.submit would otherwise swallow this; bundled recipes stay in use.
                    LOG.warn("Remote recipe update failed; using bundled recipes", exception)
                    recipeUpdateCode = "RECIPE_UPDATE_FAILED"
                }
            }
        }
    }

    /**
     * Not synchronized as a whole: a slow provider must not block the next query. Only the swap of
     * the coordinator after a settings change is guarded; the search itself runs outside the lock.
     */
    fun search(request: SearchRequest): AggregatedSearchResult = currentCoordinator().search(request)

    private fun currentCoordinator(): ProviderSearchCoordinator = synchronized(coordinatorLock) {
        val configuration = currentProviderConfiguration()
        if (configuration != providerConfiguration) {
            coordinator.close()
            providerConfiguration = configuration
            coordinator = createCoordinator(configuration)
        }
        coordinator
    }
    fun projectSnapshot(): ProjectSnapshot = modelReader.read(project)
    override fun snapshot(): ProjectSnapshot = projectSnapshot()
    override fun recipes(candidate: Candidate): List<DependencyRecipe> =
        (recipeRegistry ?: JsonRecipeRegistry.fromBytes(bundledRecipeBytes)).find(candidate.coordinates)
    fun recipeStatus(): String? = recipeUpdateCode
    override fun plan(selection: DependencySelection, project: ChangeProjectSnapshot): PlanResult = planner.plan(selection, project)
    override fun preview(plan: ChangePlan, snapshot: ProjectSnapshot): ChangePreview = ChangePreviewService(project).preview(plan, snapshot)
    override fun apply(preview: ChangePreview): ApplyResult = PsiChangeApplicator(project).apply(preview)

    override fun projectFor(snapshot: ProjectSnapshot, moduleId: String): ChangeProjectSnapshot {
        val module = snapshot.modules.firstOrNull { it.id == moduleId }
            ?: error("Module $moduleId is unavailable")
        val catalogText = snapshot.catalogPath
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
            ?.let { file -> ReadAction.compute<String, RuntimeException> { FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file) } }
            .orEmpty()
        return ChangeProjectSnapshot(
            moduleId, VersionCatalogParser.parse(catalogText).toState(), module.configurations,
            sourceSetTargets = module.sourceSets.associate { it.name to it.descendantTargets },
            sourceSetTargetNames = module.sourceSetTargetNames,
        )
    }

    /** Matches the selected editor file to the module whose directory contains it, not by IntelliJ module name. */
    fun activeModuleId(snapshot: ProjectSnapshot = projectSnapshot()): String? =
        snapshot.defaultModuleId(FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path)

    override fun dispose() {
        synchronized(coordinatorLock) { coordinator.close() }
        recipeUpdaterExecutor.shutdownNow()
    }

    private fun currentProviderConfiguration() = ProviderConfiguration(
        settings.enabledProviderIds(),
        if (settings.state.offlineMode) ProviderMode.CACHE_ONLY else ProviderMode.ONLINE,
    )

    private fun createCoordinator(configuration: ProviderConfiguration) = ProviderSearchCoordinator(
        buildList {
            if ("klibs" in configuration.ids) add(KlibsProvider(StreamableHttpMcpClient(transport), cache, mode = configuration.mode))
            if ("maven-central" in configuration.ids) add(MavenCentralProvider(transport, cache, mode = configuration.mode))
            if ("google-maven" in configuration.ids) add(GoogleMavenProvider(transport, cache, mode = configuration.mode))
        },
    )

    private companion object {
        val LOG = Logger.getInstance(DependencyResolverService::class.java)
    }

    private data class ProviderConfiguration(val ids: List<String>, val mode: ProviderMode)
}
