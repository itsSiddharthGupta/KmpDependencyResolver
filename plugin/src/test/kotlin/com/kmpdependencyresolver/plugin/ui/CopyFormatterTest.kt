package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.recipe.CompanionPlugin
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.recipe.ProcessorRequirement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CopyFormatterTest {
    private val candidate = Candidate(
        Coordinates("androidx.room", "room-runtime"), "Room", listOf(DependencyVersion("2.7.0", true)),
        emptySet(), EvidenceKind.VERIFIED, setOf(Provenance("test")),
    )
    private val recipe = DependencyRecipe(
        "room", candidate.coordinates, "implementation",
        companionPlugins = listOf(CompanionPlugin("ksp", "com.google.devtools.ksp", true)),
        processors = listOf(ProcessorRequirement(Coordinates("androidx.room", "room-compiler"), "ksp", true)),
        documentationUrl = "https://developer.android.com/jetpack/androidx/releases/room",
    )
    private val selection = CopySelection(candidate, "2.7.0", "commonMain", "implementation", recipe)

    @Test
    fun `formats all four copy variants exactly`() {
        val formatter = CopyFormatter()

        assertThat(formatter.format(selection, CopyKind.COORDINATE)).isEqualTo("androidx.room:room-runtime:2.7.0")
        assertThat(formatter.format(selection, CopyKind.KOTLIN_DSL))
            .isEqualTo("implementation(\"androidx.room:room-runtime:2.7.0\")")
        assertThat(formatter.format(selection, CopyKind.VERSION_CATALOG)).isEqualTo(
            """[versions]
room-runtime = "2.7.0"

[libraries]
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room-runtime" }""",
        )
        assertThat(formatter.format(selection, CopyKind.FULL_RECIPE)).contains(
            "[plugins]", "com.google.devtools.ksp", "room-compiler", "commonMain.dependencies", "ksp(libs.room.compiler)",
        )
    }
}
