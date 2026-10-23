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

    @Test
    fun `full recipe declares a version for every plugin version ref and uses per target KSP in KMP`() {
        val text = CopyFormatter().format(selection.copy(kspTargets = listOf("android", "iosArm64")), CopyKind.FULL_RECIPE)

        assertThat(text).contains("ksp = \"<plugin version>\" # set the com.google.devtools.ksp version")
        assertThat(text).contains("ksp = { id = \"com.google.devtools.ksp\", version.ref = \"ksp\" }")
        assertThat(text).endsWith(
            """commonMain.dependencies {
    implementation(libs.room.runtime)
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
}""",
        )
        assertThat(text).doesNotContain("ksp(libs")
    }

    @Test
    fun `full recipe for a Kotlin JVM module uses the top level dependencies block`() {
        val text = CopyFormatter().format(selection.copy(sourceSet = "main"), CopyKind.FULL_RECIPE)

        assertThat(text).endsWith("dependencies {\n    implementation(libs.room.runtime)\n    ksp(libs.room.compiler)\n}")
        assertThat(text).doesNotContain("main.dependencies")
    }

    @Test
    fun `copy menu labels are human readable`() {
        assertThat(CopyKind.entries.map { it.label }).containsExactly("Coordinate", "Kotlin DSL", "Version catalog", "Full recipe")
    }
}
