package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily

class ChangePlanner(private val aliasGenerator: AliasGenerator = AliasGenerator()) {
    fun plan(selection: DependencySelection, project: ChangeProjectSnapshot): PlanResult {
        placementConflict(selection)?.let { return PlanResult(null, listOf(it)) }
        val sourceSet = selection.overrideSourceSet ?: selection.recommendation.sourceSetName
            ?: return conflict("PLACEMENT_OVERRIDE_REQUIRED", "Select a source set before adding this dependency.")
        val configuration = selection.configuration ?: selection.recipe?.preferredConfiguration ?: "implementation"
        if (configuration !in project.configurations) {
            return conflict("UNSUPPORTED_CONFIGURATION", "Configuration '$configuration' is not available in ${project.moduleId}.")
        }

        val catalog = mutableListOf<CatalogOperation>()
        val gradle = mutableListOf<GradleOperation>()
        val occupied = project.catalog.libraries.keys.toMutableSet()
        val versionAliases = project.catalog.versions.toMutableMap()

        /** Returns the catalog alias for [coordinates], adding catalog entries when needed. */
        fun catalogAlias(coordinates: Coordinates, version: String?): Pair<String?, PlanConflict?> {
            val sameCoordinate = project.catalog.libraries.entries.firstOrNull { it.value.coordinates == coordinates }
            if (sameCoordinate != null) {
                val resolved = sameCoordinate.value.versionAlias?.let(project.catalog.versions::get) ?: sameCoordinate.value.version
                if (version != null && resolved != version) {
                    return null to PlanConflict("VERSION_CONFLICT", "${coordinates.notation} already resolves to ${resolved ?: "no version"}; $version was requested.")
                }
                return sameCoordinate.key to null
            }
            val alias = aliasGenerator.generate(coordinates, occupied)
                ?: return null to PlanConflict("ALIAS_COLLISION", "No deterministic alias is available for ${coordinates.notation}.")
            occupied += alias
            val versionAlias = version?.let { requested ->
                versionAliases.entries.sortedBy { it.key }.firstOrNull { it.value == requested }?.key ?: run {
                    if (alias in versionAliases && versionAliases[alias] != requested) {
                        return null to PlanConflict("VERSION_CONFLICT", "Version alias '$alias' already has a different value.")
                    }
                    versionAliases[alias] = requested
                    catalog += CatalogOperation.PutVersion(alias, requested)
                    alias
                }
            }
            catalog += CatalogOperation.PutLibrary(alias, coordinates, versionAlias)
            return alias to null
        }

        fun addLibrary(coordinates: Coordinates, version: String?, targetConfiguration: String, platform: Boolean): PlanConflict? {
            val (alias, conflict) = catalogAlias(coordinates, version)
            if (alias == null) return conflict
            gradle += GradleOperation.AddDependency(sourceSet, targetConfiguration, alias, platform)
            return null
        }

        val recipe = selection.recipe
        recipe?.bom?.let { bom ->
            val families = project.sourceSetTargets[sourceSet]
            if (families != null && !JVM_FAMILIES.containsAll(families)) {
                return conflict("BOM_REQUIRES_JVM_SOURCE_SET", "${bom.coordinates.notation} is a Gradle platform (BOM), which only resolves for Android and JVM targets. Choose androidMain or a JVM source set instead of $sourceSet.")
            }
            addLibrary(bom.coordinates, selection.version, configuration, platform = true)?.let { return PlanResult(null, listOf(it)) }
        }
        addLibrary(selection.candidate.coordinates, if (recipe?.bom == null) selection.version else null, configuration, false)
            ?.let { return PlanResult(null, listOf(it)) }

        val kspTargets = project.sourceSetTargetNames[sourceSet].orEmpty()
        recipe?.processors.orEmpty()
            .filter { it.required || "processor:${it.coordinates.notation}" in selection.selectedCompanionIds }
            .sortedBy { it.coordinates.notation }.forEach { processor ->
            if (processor.configuration == KSP && kspTargets.isNotEmpty()) {
                // KSP in Kotlin Multiplatform runs per compilation: one kspAndroid/kspIosArm64/... configuration per target.
                val (alias, aliasConflict) = catalogAlias(processor.coordinates, selection.version)
                if (alias == null) return PlanResult(null, listOfNotNull(aliasConflict))
                kspTargets.forEach { target -> gradle += GradleOperation.AddTopLevelDependency(KSP + target.replaceFirstChar(Char::uppercaseChar), alias) }
                return@forEach
            }
            if (processor.configuration !in project.configurations) {
                return conflict("UNSUPPORTED_CONFIGURATION", "Configuration '${processor.configuration}' is required by ${processor.coordinates.notation}.")
            }
            addLibrary(processor.coordinates, selection.version, processor.configuration, false)
                ?.let { return PlanResult(null, listOf(it)) }
        }
        recipe?.companionPlugins.orEmpty()
            .filter { it.required || "plugin:${it.alias}" in selection.selectedCompanionIds }
            .sortedBy { it.alias }.forEach { plugin ->
            val existing = project.catalog.plugins.entries.firstOrNull { it.value.pluginId == plugin.pluginId }
                ?: return conflict("MISSING_PLUGIN_VERSION", "A versioned catalog plugin entry is required for ${plugin.pluginId}.")
            gradle += GradleOperation.AddPluginAlias(existing.key, applyFalse = false)
            if (project.moduleId != ":") gradle += GradleOperation.AddRootPluginAlias(existing.key)
        }

        val orderedCatalog = catalog.sortedWith(compareBy<CatalogOperation>({ operationRank(it) }, { operationAlias(it) }))
        val orderedGradle = gradle.sortedWith(compareBy<GradleOperation>({ gradleRank(it) }, { gradleAlias(it) }))
        return PlanResult(ChangePlan(project.moduleId, orderedCatalog, orderedGradle), emptyList())
    }

    private fun placementConflict(selection: DependencySelection): PlanConflict? = when {
        selection.recommendation.evidence == EvidenceKind.INCOMPATIBLE &&
            (selection.overrideSourceSet == null || !selection.allowIncompatible) ->
            PlanConflict("INCOMPATIBLE_OVERRIDE_REQUIRED", "Incompatible target coverage requires an explicit source set and confirmation.")
        selection.recommendation.requiresOverride && selection.overrideSourceSet == null ->
            PlanConflict("PLACEMENT_OVERRIDE_REQUIRED", "Unknown target coverage requires an explicit source set.")
        else -> null
    }

    private fun conflict(code: String, message: String) = PlanResult(null, listOf(PlanConflict(code, message)))
    private fun operationRank(operation: CatalogOperation) = when (operation) {
        is CatalogOperation.PutVersion -> 0; is CatalogOperation.PutLibrary -> 1; is CatalogOperation.PutPlugin -> 2
    }
    private fun operationAlias(operation: CatalogOperation) = when (operation) {
        is CatalogOperation.PutVersion -> operation.alias; is CatalogOperation.PutLibrary -> operation.alias; is CatalogOperation.PutPlugin -> operation.alias
    }
    private fun gradleRank(operation: GradleOperation) = when (operation) {
        is GradleOperation.AddRootPluginAlias -> 0; is GradleOperation.AddPluginAlias -> 1
        is GradleOperation.AddDependency -> 2; is GradleOperation.AddTopLevelDependency -> 3
    }
    private fun gradleAlias(operation: GradleOperation) = when (operation) {
        is GradleOperation.AddDependency -> operation.alias; is GradleOperation.AddPluginAlias -> operation.alias
        is GradleOperation.AddTopLevelDependency -> operation.configuration + ":" + operation.alias
        is GradleOperation.AddRootPluginAlias -> operation.alias
    }

    private companion object {
        const val KSP = "ksp"
        val JVM_FAMILIES = setOf(TargetFamily.ANDROID, TargetFamily.JVM)
    }
}
