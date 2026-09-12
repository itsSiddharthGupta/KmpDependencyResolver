package com.kmpdependencyresolver.core.recipe

data class RecipeValidationIssue(
    val code: String,
    val message: String,
)

class RecipeValidator {
    fun validate(recipe: DependencyRecipe): List<RecipeValidationIssue> = buildList {
        if (!ID.matches(recipe.id)) {
            add(RecipeValidationIssue("INVALID_RECIPE_ID", "Recipe id must use lowercase kebab-case."))
        }
        if (recipe.coordinate.group.isBlank() || recipe.coordinate.artifact.isBlank()) {
            add(RecipeValidationIssue("INVALID_COORDINATES", "Recipe coordinates must be nonblank."))
        }
        if (recipe.preferredConfiguration !in CONFIGURATIONS) {
            add(RecipeValidationIssue("UNSUPPORTED_CONFIGURATION", "Unsupported Gradle configuration."))
        }
        if (!recipe.documentationUrl.startsWith("https://")) {
            add(RecipeValidationIssue("NON_HTTPS_DOCUMENTATION", "Documentation URL must use HTTPS."))
        }
        recipe.processors
            .filter { it.configuration !in PROCESSOR_CONFIGURATIONS }
            .forEach {
                add(RecipeValidationIssue("UNSUPPORTED_PROCESSOR_CONFIGURATION", "Unsupported processor configuration."))
            }
        recipe.companionPlugins
            .filter { it.alias.isBlank() || it.pluginId.isBlank() }
            .forEach {
                add(RecipeValidationIssue("INVALID_COMPANION_PLUGIN", "Companion plugin fields must be nonblank."))
            }
    }

    private companion object {
        val ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val CONFIGURATIONS = setOf("implementation", "api", "compileOnly", "runtimeOnly")
        val PROCESSOR_CONFIGURATIONS = setOf("ksp", "kapt", "annotationProcessor")
    }
}
