package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.PlacementRecommendation
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.recipe.BomRule
import com.kmpdependencyresolver.core.recipe.CompanionPlugin
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.recipe.ProcessorRequirement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChangePlannerTest {
    @Test
    fun `plans direct library with shared version and deterministic ordering`() {
        val project = project(versions = mapOf("ktor" to "3.1.0"))

        val result = ChangePlanner().plan(selection("io.ktor", "ktor-client-core", "3.1.0"), project)

        assertThat(result.conflicts).isEmpty()
        assertThat(result.plan!!.catalogOperations).containsExactly(
            CatalogOperation.PutLibrary("ktor-client-core", Coordinates("io.ktor", "ktor-client-core"), "ktor"),
        )
        assertThat(result.plan!!.gradleOperations).containsExactly(
            GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false),
        )
    }

    @Test
    fun `reuses equivalent alias but never overwrites conflicting version`() {
        val equivalent = project(
            versions = mapOf("ktor" to "3.1.0"),
            libraries = mapOf("ktor.core" to CatalogLibrary(Coordinates("io.ktor", "ktor-client-core"), "ktor")),
        )
        assertThat(ChangePlanner().plan(selection("io.ktor", "ktor-client-core", "3.1.0"), equivalent).plan!!
            .gradleOperations).containsExactly(GradleOperation.AddDependency("commonMain", "implementation", "ktor.core", false))

        val conflict = equivalent.copy(catalog = equivalent.catalog.copy(versions = mapOf("ktor" to "2.3.0")))
        assertThat(ChangePlanner().plan(selection("io.ktor", "ktor-client-core", "3.1.0"), conflict).conflicts)
            .extracting<String> { it.code }.contains("VERSION_CONFLICT")
    }

    @Test
    fun `alias ambiguity and unsupported configuration are conflicts`() {
        val collisions = project(libraries = mapOf(
            "library" to CatalogLibrary(Coordinates("one", "other"), null),
            "example-library" to CatalogLibrary(Coordinates("two", "other"), null),
        ))
        assertThat(ChangePlanner().plan(selection("com.example", "library", "1.0"), collisions).conflicts)
            .extracting<String> { it.code }.contains("ALIAS_COLLISION")

        val unsupported = project(configurations = setOf("api"))
        assertThat(ChangePlanner().plan(selection("sample", "library", "1.0"), unsupported).conflicts)
            .extracting<String> { it.code }.contains("UNSUPPORTED_CONFIGURATION")
    }

    @Test
    fun `BOM recipe uses platform and omits member version`() {
        val recipe = recipe(bom = BomRule(Coordinates("androidx.compose", "compose-bom")))
        val result = ChangePlanner().plan(selection("androidx.compose.ui", "ui", "2025.01.00", recipe), project())

        assertThat(result.plan!!.gradleOperations).contains(
            GradleOperation.AddDependency("commonMain", "implementation", "compose-bom", true),
            GradleOperation.AddDependency("commonMain", "implementation", "ui", false),
        )
        assertThat(result.plan!!.catalogOperations.filterIsInstance<CatalogOperation.PutLibrary>()
            .single { it.alias == "ui" }.versionAlias).isNull()
    }

    @Test
    fun `Room recipe adds compiler and existing KSP plugin`() {
        val recipe = recipe(
            plugins = listOf(CompanionPlugin("ksp", "com.google.devtools.ksp", true)),
            processors = listOf(ProcessorRequirement(Coordinates("androidx.room", "room-compiler"), "ksp", true)),
        )
        val project = project(
            configurations = setOf("implementation", "ksp"),
            plugins = mapOf("ksp" to CatalogPlugin("com.google.devtools.ksp", "kspVersion")),
        )
        val result = ChangePlanner().plan(selection("androidx.room", "room-runtime", "2.7.0", recipe), project)

        assertThat(result.plan!!.gradleOperations).contains(
            GradleOperation.AddPluginAlias("ksp", false),
            GradleOperation.AddDependency("commonMain", "ksp", "room-compiler", false),
        )
    }

    @Test
    fun `serialization recipe applies the matching catalog plugin`() {
        val recipe = recipe(
            plugins = listOf(CompanionPlugin("serialization", "org.jetbrains.kotlin.plugin.serialization", true)),
        )
        val project = project(
            plugins = mapOf("kotlin-serialization" to CatalogPlugin("org.jetbrains.kotlin.plugin.serialization", "kotlin")),
        )

        val result = ChangePlanner().plan(
            selection("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.9.0", recipe),
            project,
        )

        assertThat(result.plan!!.gradleOperations)
            .contains(GradleOperation.AddPluginAlias("kotlin-serialization", false))
    }

    @Test
    fun `optional companion is added only when selected`() {
        val processor = ProcessorRequirement(Coordinates("sample", "optional-processor"), "ksp", false)
        val recipe = recipe(processors = listOf(processor))
        val project = project(configurations = setOf("implementation", "ksp"))

        val omitted = ChangePlanner().plan(selection("sample", "library", "1.0", recipe), project).plan!!
        val selected = ChangePlanner().plan(
            selection("sample", "library", "1.0", recipe).copy(
                selectedCompanionIds = setOf("processor:sample:optional-processor"),
            ),
            project,
        ).plan!!

        assertThat(omitted.gradleOperations.filterIsInstance<GradleOperation.AddDependency>())
            .noneMatch { it.configuration == "ksp" }
        assertThat(selected.gradleOperations)
            .contains(GradleOperation.AddDependency("commonMain", "ksp", "optional-processor", false))
    }

    @Test
    fun `unknown and incompatible placement require explicit override`() {
        val unknown = selection("sample", "library", "1.0").copy(
            recommendation = PlacementRecommendation(null, EvidenceKind.UNKNOWN, "unknown", true),
        )
        assertThat(ChangePlanner().plan(unknown, project()).conflicts).extracting<String> { it.code }.contains("PLACEMENT_OVERRIDE_REQUIRED")
        assertThat(ChangePlanner().plan(unknown.copy(overrideSourceSet = "jvmMain"), project()).plan).isNotNull()

        val incompatible = unknown.copy(recommendation = PlacementRecommendation(null, EvidenceKind.INCOMPATIBLE, "bad", true))
        assertThat(ChangePlanner().plan(incompatible, project()).conflicts).extracting<String> { it.code }.contains("INCOMPATIBLE_OVERRIDE_REQUIRED")
        assertThat(ChangePlanner().plan(incompatible.copy(overrideSourceSet = "jvmMain", allowIncompatible = true), project()).plan).isNotNull()
    }

    private fun selection(group: String, artifact: String, version: String, recipe: DependencyRecipe? = null) = DependencySelection(
        candidate = Candidate(Coordinates(group, artifact), artifact, listOf(DependencyVersion(version, true)), emptySet(), EvidenceKind.VERIFIED, setOf(Provenance("test"))),
        version = version,
        recommendation = PlacementRecommendation("commonMain", EvidenceKind.VERIFIED, "test", false),
        recipe = recipe,
    )

    private fun project(
        versions: Map<String, String> = emptyMap(),
        libraries: Map<String, CatalogLibrary> = emptyMap(),
        plugins: Map<String, CatalogPlugin> = emptyMap(),
        configurations: Set<String> = setOf("implementation"),
    ) = ChangeProjectSnapshot(":shared", CatalogState(versions, libraries, plugins), configurations)

    private fun recipe(
        bom: BomRule? = null,
        plugins: List<CompanionPlugin> = emptyList(),
        processors: List<ProcessorRequirement> = emptyList(),
    ) = DependencyRecipe("test", Coordinates("ignored", "ignored"), "implementation", bom, plugins, processors, documentationUrl = "https://example.com")
}
