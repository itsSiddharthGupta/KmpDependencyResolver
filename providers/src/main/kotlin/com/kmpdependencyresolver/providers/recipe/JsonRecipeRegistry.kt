package com.kmpdependencyresolver.providers.recipe

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.recipe.BomRule
import com.kmpdependencyresolver.core.recipe.CompanionPlugin
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.recipe.ProcessorRequirement
import com.kmpdependencyresolver.core.recipe.RecipeRegistry
import com.kmpdependencyresolver.core.recipe.RecipeValidator
import com.kmpdependencyresolver.core.recipe.VersionConstraint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class RecipeLoadException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class JsonRecipeRegistry private constructor(
    recipes: List<DependencyRecipe>,
) : RecipeRegistry {
    private val recipesByCoordinate = recipes.groupBy(DependencyRecipe::coordinate)

    override fun find(coordinates: Coordinates): List<DependencyRecipe> =
        recipesByCoordinate[coordinates].orEmpty()

    companion object {
        private const val MAX_PAYLOAD_BYTES = 1_048_576
        private val json = Json { ignoreUnknownKeys = false }

        fun fromBytes(payload: ByteArray): JsonRecipeRegistry {
            if (payload.size > MAX_PAYLOAD_BYTES) {
                throw RecipeLoadException("PAYLOAD_TOO_LARGE", "Recipe payload exceeds 1 MiB.")
            }

            val catalog = try {
                json.decodeFromString<CatalogDto>(payload.decodeToString())
            } catch (exception: SerializationException) {
                throw RecipeLoadException("INVALID_JSON", "Recipe payload does not match the schema.", exception)
            } catch (exception: IllegalArgumentException) {
                throw RecipeLoadException("INVALID_JSON", "Recipe payload is not valid JSON.", exception)
            }

            if (catalog.schemaVersion != 1) {
                throw RecipeLoadException("UNSUPPORTED_SCHEMA", "Only recipe schema version 1 is supported.")
            }
            val duplicateId = catalog.recipes.groupingBy(RecipeDto::id).eachCount()
                .entries.firstOrNull { it.value > 1 }?.key
            if (duplicateId != null) {
                throw RecipeLoadException("DUPLICATE_RECIPE_ID", "Duplicate recipe id: $duplicateId")
            }

            val recipes = catalog.recipes.map(RecipeDto::toDomain)
            val issue = recipes.asSequence().flatMap { RecipeValidator().validate(it) }.firstOrNull()
            if (issue != null) {
                throw RecipeLoadException("INVALID_RECIPE", "${issue.code}: ${issue.message}")
            }
            return JsonRecipeRegistry(recipes)
        }
    }
}

@Serializable
private data class CatalogDto(
    val schemaVersion: Int,
    val recipes: List<RecipeDto>,
)

@Serializable
private data class RecipeDto(
    val id: String,
    val group: String,
    val artifact: String,
    val preferredConfiguration: String,
    val documentationUrl: String,
    val bom: BomDto? = null,
    val companionPlugins: List<CompanionPluginDto> = emptyList(),
    val processors: List<ProcessorDto> = emptyList(),
    val constraints: List<ConstraintDto> = emptyList(),
) {
    fun toDomain() = DependencyRecipe(
        id = id,
        coordinate = Coordinates(group, artifact),
        preferredConfiguration = preferredConfiguration,
        documentationUrl = documentationUrl,
        bom = bom?.let { BomRule(Coordinates(it.group, it.artifact), it.enforced) },
        companionPlugins = companionPlugins.map { CompanionPlugin(it.alias, it.pluginId, it.required) },
        processors = processors.map {
            ProcessorRequirement(Coordinates(it.group, it.artifact), it.configuration, it.required)
        },
        constraints = constraints.map { VersionConstraint(it.subject, it.expression) },
    )
}

@Serializable private data class BomDto(val group: String, val artifact: String, val enforced: Boolean = false)
@Serializable private data class CompanionPluginDto(val alias: String, val pluginId: String, val required: Boolean)
@Serializable private data class ProcessorDto(
    val group: String,
    val artifact: String,
    val configuration: String,
    val required: Boolean,
)
@Serializable private data class ConstraintDto(val subject: String, val expression: String)
