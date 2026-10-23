package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.PlacementRecommendation
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.recipe.DependencyRecipe

sealed interface CatalogOperation {
    data class PutVersion(val alias: String, val value: String) : CatalogOperation
    data class PutLibrary(val alias: String, val coordinates: Coordinates, val versionAlias: String?) : CatalogOperation
    data class PutPlugin(val alias: String, val pluginId: String, val versionAlias: String) : CatalogOperation
}

sealed interface GradleOperation {
    data class AddDependency(val sourceSet: String, val configuration: String, val alias: String, val platform: Boolean) : GradleOperation
    data class AddPluginAlias(val alias: String, val applyFalse: Boolean) : GradleOperation

    /** `add("<configuration>", libs.<alias>)` in the module's top-level `dependencies { }`, e.g. per-target KSP. */
    data class AddTopLevelDependency(val configuration: String, val alias: String) : GradleOperation

    /** `alias(libs.plugins.<alias>) apply false` in the root build script, when it has a `plugins { }` block. */
    data class AddRootPluginAlias(val alias: String) : GradleOperation
}

/** A `[libraries]` entry; [version] holds a literal version when the entry does not use `version.ref`. */
data class CatalogLibrary(val coordinates: Coordinates, val versionAlias: String?, val version: String? = null)

/** A `[plugins]` entry; [version] holds a literal version when the entry does not use `version.ref`. */
data class CatalogPlugin(val pluginId: String, val versionAlias: String?, val version: String? = null)
data class CatalogState(
    val versions: Map<String, String>,
    val libraries: Map<String, CatalogLibrary>,
    val plugins: Map<String, CatalogPlugin>,
)

/**
 * @property sourceSetTargets target families compiled by each Kotlin source set, when known.
 * @property sourceSetTargetNames Kotlin target names (`android`, `iosArm64`, `desktop`) compiled by each source set.
 * Both are empty for a module whose Kotlin Multiplatform shape is unknown.
 */
data class ChangeProjectSnapshot(
    val moduleId: String,
    val catalog: CatalogState,
    val configurations: Set<String>,
    val sourceSetTargets: Map<String, Set<TargetFamily>> = emptyMap(),
    val sourceSetTargetNames: Map<String, List<String>> = emptyMap(),
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
