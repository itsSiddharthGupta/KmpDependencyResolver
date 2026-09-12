package com.kmpdependencyresolver.core.recipe

import com.kmpdependencyresolver.core.model.Coordinates

data class CompanionPlugin(
    val alias: String,
    val pluginId: String,
    val required: Boolean,
)

data class ProcessorRequirement(
    val coordinates: Coordinates,
    val configuration: String,
    val required: Boolean,
)

data class BomRule(
    val coordinates: Coordinates,
    val enforced: Boolean = false,
)

data class VersionConstraint(
    val subject: String,
    val expression: String,
)

data class DependencyRecipe(
    val id: String,
    val coordinate: Coordinates,
    val preferredConfiguration: String,
    val bom: BomRule? = null,
    val companionPlugins: List<CompanionPlugin> = emptyList(),
    val processors: List<ProcessorRequirement> = emptyList(),
    val constraints: List<VersionConstraint> = emptyList(),
    val documentationUrl: String,
)

interface RecipeRegistry {
    fun find(coordinates: Coordinates): List<DependencyRecipe>
}
