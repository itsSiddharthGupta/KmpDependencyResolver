package com.kmpdependencyresolver.core.recipe

import com.kmpdependencyresolver.core.model.Coordinates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecipeValidatorTest {
    private val validator = RecipeValidator()

    @Test
    fun `valid companion recipe has no validation issues`() {
        val recipe = DependencyRecipe(
            id = "kotlinx-serialization-json",
            coordinate = Coordinates("org.jetbrains.kotlinx", "kotlinx-serialization-json"),
            preferredConfiguration = "implementation",
            companionPlugins = listOf(
                CompanionPlugin("kotlin-serialization", "org.jetbrains.kotlin.plugin.serialization", required = true),
            ),
            documentationUrl = "https://github.com/Kotlin/kotlinx.serialization",
        )

        assertThat(validator.validate(recipe)).isEmpty()
    }

    @Test
    fun `unsafe url and configuration are rejected with stable codes`() {
        val recipe = DependencyRecipe(
            id = "unsafe",
            coordinate = Coordinates("example", "unsafe"),
            preferredConfiguration = "compile files('/tmp/payload.jar')",
            documentationUrl = "file:///tmp/instructions",
        )

        assertThat(validator.validate(recipe).map(RecipeValidationIssue::code))
            .containsExactlyInAnyOrder("UNSUPPORTED_CONFIGURATION", "NON_HTTPS_DOCUMENTATION")
    }
}
