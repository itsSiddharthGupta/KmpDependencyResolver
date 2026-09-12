package com.kmpdependencyresolver.providers.recipe

import com.kmpdependencyresolver.core.model.Coordinates
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JsonRecipeRegistryTest {
    @Test
    fun `valid JSON loads a searchable recipe`() {
        val registry = JsonRecipeRegistry.fromBytes(VALID_JSON.encodeToByteArray())

        val recipe = registry.find(Coordinates("androidx.room", "room-runtime")).single()
        assertThat(recipe.id).isEqualTo("room")
        assertThat(recipe.processors.single().configuration).isEqualTo("ksp")
        assertThat(recipe.companionPlugins.single().pluginId).isEqualTo("com.google.devtools.ksp")
    }

    @Test
    fun `unknown executable-looking field is rejected`() {
        val payload = VALID_JSON.replace(
            "\"documentationUrl\"",
            "\"rawGradleScript\":\"exec('payload')\",\"documentationUrl\"",
        )

        assertThatThrownBy { JsonRecipeRegistry.fromBytes(payload.encodeToByteArray()) }
            .isInstanceOf(RecipeLoadException::class.java)
            .extracting("code")
            .isEqualTo("INVALID_JSON")
    }

    @Test
    fun `payload over one mebibyte is rejected before parsing`() {
        val payload = ByteArray(1_048_577) { 'x'.code.toByte() }

        assertThatThrownBy { JsonRecipeRegistry.fromBytes(payload) }
            .isInstanceOf(RecipeLoadException::class.java)
            .extracting("code")
            .isEqualTo("PAYLOAD_TOO_LARGE")
    }

    @Test
    fun `unsupported schema and duplicate ids are rejected`() {
        val unsupported = VALID_JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        val recipeJson = VALID_JSON.substringAfter("\"recipes\":[").substringBeforeLast("]")
        val duplicate = """{"schemaVersion":1,"recipes":[$recipeJson,$recipeJson]}"""

        assertThatThrownBy { JsonRecipeRegistry.fromBytes(unsupported.encodeToByteArray()) }
            .extracting("code").isEqualTo("UNSUPPORTED_SCHEMA")
        assertThatThrownBy { JsonRecipeRegistry.fromBytes(duplicate.encodeToByteArray()) }
            .extracting("code").isEqualTo("DUPLICATE_RECIPE_ID")
    }

    private companion object {
        val VALID_JSON = """
            {
              "schemaVersion":1,
              "recipes":[{
                "id":"room",
                "group":"androidx.room",
                "artifact":"room-runtime",
                "preferredConfiguration":"implementation",
                "documentationUrl":"https://developer.android.com/jetpack/androidx/releases/room",
                "companionPlugins":[{"alias":"ksp","pluginId":"com.google.devtools.ksp","required":true}],
                "processors":[{"group":"androidx.room","artifact":"room-compiler","configuration":"ksp","required":true}]
              }]
            }
        """.trimIndent()
    }
}
