package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.PlacementRecommendation
import com.kmpdependencyresolver.core.recipe.DependencyRecipe

sealed interface CatalogOperation {
    data class PutVersion(val alias: String, val value: String) : CatalogOperation
    data class PutLibrary(val alias: String, val coordinates: Coordinates, val versionAlias: String?) : CatalogOperation
    data class PutPlugin(val alias: String, val pluginId: String, val versionAlias: String) : CatalogOperation
}

sealed interface GradleOperation {
    data class AddDependency(val sourceSet: String, val configuration: String, val alias: String, val platform: Boolean) : GradleOperation
    data class AddPluginAlias(val alias: String, val applyFalse: Boolean) : GradleOperation
}

data class CatalogLibrary(val coordinates: Coordinates, val versionAlias: String?)
data class CatalogPlugin(val pluginId: String, val versionAlias: String)
data class CatalogState(
    val versions: Map<String, String>,
    val libraries: Map<String, CatalogLibrary>,
    val plugins: Map<String, CatalogPlugin>,
)

data class ChangeProjectSnapshot(
    val moduleId: String,
    val catalog: CatalogState,
    val configurations: Set<String>,
)

data class DependencySelection(
    val candidate: Candidate,
    val version: String,
    val recommendation: PlacementRecommendation,
    val configuration: String? = null,
    val recipe: DependencyRecipe? = null,
    val overrideSourceSet: String? = null,
    val allowIncompatible: Boolean = false,
    val selectedCompanionIds: Set<String> = emptySet(),
)

data class ChangePlan(
    val moduleId: String,
    val catalogOperations: List<CatalogOperation>,
    val gradleOperations: List<GradleOperation>,
)

data class PlanConflict(val code: String, val message: String)
data class PlanResult(val plan: ChangePlan?, val conflicts: List<PlanConflict>)
