package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.change.CatalogState
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.core.change.ChangeProjectSnapshot
import com.kmpdependencyresolver.core.change.DependencySelection
import com.kmpdependencyresolver.core.change.PlanConflict
import com.kmpdependencyresolver.core.change.PlanResult
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.SourceSetNode
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.recipe.CompanionPlugin
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.core.recipe.ProcessorRequirement
import com.kmpdependencyresolver.plugin.editing.ApplyRejectedException
import com.kmpdependencyresolver.plugin.editing.ApplyResult
import com.kmpdependencyresolver.plugin.editing.ChangePreview
import com.kmpdependencyresolver.plugin.editing.FileChangePreview
import com.kmpdependencyresolver.plugin.editing.PreviewFileKind
import com.kmpdependencyresolver.plugin.project.GradleModuleSnapshot
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AddDependencyFlowTest {
    @Test
    fun `unsupported project cannot enter the mutating flow`() {
        val unsupported = snapshot().copy(catalogPath = null)

        assertThatThrownBy { AddDependencyFlow(FakeBackend(unsupported)).start(candidate(), ":shared") }
            .isInstanceOf(AddUnavailableException::class.java)
            .hasMessageContaining("gradle/libs.versions.toml")
    }

    @Test
    fun `non KMP module explains that Add is KMP only instead of a generic error`() {
        val server = GradleModuleSnapshot(":server", "/project/server/build.gradle.kts", emptySet(), emptyList(), emptySet(), setOf("implementation"),
            listOf("No Kotlin Multiplatform targets are declared in this module's kotlin { } block."))
        val withServer = snapshot().let { it.copy(modules = it.modules + server) }

        val error = runCatching { AddDependencyFlow(FakeBackend(withServer)).start(candidate(), ":server") }.exceptionOrNull()

        assertThat(error).isInstanceOf(AddUnavailableException::class.java)
        val userError = (error as AddUnavailableException).error
        assertThat(userError.title).isEqualTo("Add isn't available for :server")
        assertThat(userError.message).isEqualTo(":server isn't a Kotlin Multiplatform or Kotlin/JVM module. Add supports those only; use Copy to get the declaration.")
    }

    @Test
    fun `Kotlin JVM module offers its main source set and JVM configurations`() {
        val server = GradleModuleSnapshot(
            ":server", "/project/server/build.gradle.kts", setOf(TargetFamily.JVM),
            listOf(SourceSetNode("main", setOf(TargetFamily.JVM))), emptySet(),
            setOf("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation"), jvmModule = true,
        )
        val withServer = snapshot().let { it.copy(modules = it.modules + server) }

        val state = AddDependencyFlow(FakeBackend(withServer)).start(candidate(targets = setOf(TargetFamily.JVM)), ":server")

        assertThat(state.sourceSets).containsExactly("main")
        assertThat(state.selectedSourceSet).isEqualTo("main")
        assertThat(state.configurations).contains("testImplementation")
        assertThat(state.selectedConfiguration).isEqualTo("implementation")
    }

    @Test
    fun `recipe status codes read as sentences`() {
        assertThat(recipeStatusText("CURATED_CATALOG_STALE")).isEqualTo("online recipe update unavailable, using cached or bundled recipes")
        assertThat(recipeStatusText("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW")
    }

    @Test
    fun `confirmation defaults recommendation and exposes only valid structured choices`() {
        val backend = FakeBackend(snapshot())
        val flow = AddDependencyFlow(backend)

        val state = flow.start(candidate(), ":shared")

        assertThat(state.selectedSourceSet).isEqualTo("commonMain")
        assertThat(state.sourceSets).containsExactly("commonMain", "jvmMain")
        assertThat(state.versions).containsExactly("3.1.0", "3.2.0-RC")
        assertThat(state.configurations).containsExactly("api", "implementation", "ksp")
        assertThat(state.companions.single { it.id == "plugin:serialization" }.locked).isTrue()
        assertThat(state.companions.single { it.id.startsWith("processor:") }.locked).isFalse()
    }

    @Test
    fun `preferred version from the result row is preselected`() {
        val state = AddDependencyFlow(FakeBackend(snapshot())).start(candidate(), ":shared", preferredVersion = "3.2.0-RC")

        assertThat(state.selectedVersion).isEqualTo("3.2.0-RC")
    }

    @Test
    fun `reopening after a preview error keeps the user's choices`() {
        val state = AddDependencyFlow(FakeBackend(snapshot())).start(candidate(), ":shared")
        val processor = state.companions.single { it.id.startsWith("processor:") }.id

        val reopened = state.withOptions(
            AddOptions("3.2.0-RC", "jvmMain", "api", setOf(processor), acknowledgeUnknown = false, incompatibilityPhrase = ""),
        )

        assertThat(reopened.selectedVersion).isEqualTo("3.2.0-RC")
        assertThat(reopened.selectedSourceSet).isEqualTo("jvmMain")
        assertThat(reopened.selectedConfiguration).isEqualTo("api")
        assertThat(reopened.companions.single { it.id == processor }.selected).isTrue()
        assertThat(reopened.companions.single { it.locked }.selected).isTrue()
    }

    @Test
    fun `unknown and incompatible evidence require explicit acknowledgements`() {
        val backend = FakeBackend(snapshot())
        val flow = AddDependencyFlow(backend)
        val unknown = flow.start(candidate(EvidenceKind.UNKNOWN, emptySet()), ":shared")

        val unknownBlocked = flow.preview(unknown.toOptions(sourceSet = "commonMain"))
        assertThat(unknownBlocked.applyEnabled).isFalse()
        assertThat(unknownBlocked.warnings).anyMatch { it.contains("unknown", ignoreCase = true) }
        assertThat(flow.preview(unknown.toOptions(sourceSet = "commonMain").copy(acknowledgeUnknown = true)).applyEnabled)
            .isTrue()

        val incompatible = flow.start(candidate(EvidenceKind.INCOMPATIBLE, setOf(TargetFamily.JS)), ":shared")
        val incompatibleBlocked = flow.preview(
            incompatible.toOptions(sourceSet = "commonMain", incompatibilityPhrase = "yes"),
        )
        assertThat(incompatibleBlocked.applyEnabled).isFalse()
        assertThat(incompatibleBlocked.warnings).anyMatch { it.contains(AddDependencyFlow.INCOMPATIBLE_PHRASE) }
        assertThat(
            flow.preview(
                incompatible.toOptions(
                    sourceSet = "commonMain",
                    incompatibilityPhrase = AddDependencyFlow.INCOMPATIBLE_PHRASE,
                ),
            ).applyEnabled,
        ).isTrue()
    }

    @Test
    fun `preview preserves selected companions and includes both changed files`() {
        val backend = FakeBackend(snapshot())
        val flow = AddDependencyFlow(backend)
        val state = flow.start(candidate(), ":shared")
        val optional = state.companions.single { !it.locked }

        val preview = flow.preview(state.toOptions(selectedCompanions = setOf(optional.id)))

        assertThat(preview.applyEnabled).isTrue()
        assertThat(preview.preview!!.files.map { it.kind })
            .containsExactlyInAnyOrder(PreviewFileKind.VERSION_CATALOG, PreviewFileKind.KOTLIN_DSL)
        assertThat(backend.lastSelection!!.selectedCompanionIds).contains(optional.id, "plugin:serialization")
    }

    @Test
    fun `planner conflicts disable apply and map to actionable diagnostics`() {
        val backend = FakeBackend(snapshot()).apply {
            planResult = PlanResult(null, listOf(PlanConflict("VERSION_CONFLICT", "already resolves differently")))
        }
        val flow = AddDependencyFlow(backend)

        val preview = flow.preview(flow.start(candidate(), ":shared").toOptions())

        assertThat(preview.applyEnabled).isFalse()
        assertThat(preview.error!!.code).isEqualTo("VERSION_CONFLICT")
        assertThat(preview.error.message).contains("version", "Choose")
    }

    @Test
    fun `apply reports paths and sync suggestion while stale preview returns to fresh confirmation`() {
        val backend = FakeBackend(snapshot())
        val flow = AddDependencyFlow(backend)
        val preview = flow.preview(flow.start(candidate(), ":shared").toOptions())

        val success = flow.apply(preview)
        assertThat(success).isInstanceOf(AddApplyOutcome.Success::class.java)
        success as AddApplyOutcome.Success
        assertThat(success.relativePaths).containsExactly("gradle/libs.versions.toml", "shared/build.gradle.kts")
        assertThat(success.suggestGradleSync).isTrue()

        backend.applyFailure = ApplyRejectedException("STALE_STATE", "changed")
        backend.currentSnapshot = snapshot().copy(versions = mapOf("fresh" to "1"))
        val stale = flow.apply(preview)
        assertThat(stale).isInstanceOf(AddApplyOutcome.Stale::class.java)
        stale as AddApplyOutcome.Stale
        assertThat(stale.confirmation.notice).contains("changed", "Review")
        assertThat(backend.snapshotReads).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `stable error mapper covers supported diagnostics without leaking exception detail`() {
        val codes = listOf(
            "UNSUPPORTED_PROJECT", "AMBIGUOUS_SOURCE_SETS", "CATALOG_CONFLICT", "VERSION_CONFLICT",
            "SOURCE_SET_NOT_FOUND", "READ_ONLY", "INVALID_RENDER", "STALE_STATE", "PROVIDER_UNAVAILABLE", "CACHE_CORRUPT", "UNEXPECTED",
        )

        assertThat(codes.map { UserFacingError.from(it, IllegalStateException("secret file contents")) }.map { it.code })
            .containsExactlyElementsOf(codes)
        assertThat(codes.map { UserFacingError.from(it, IllegalStateException("secret file contents")) })
            .allMatch { "secret file contents" !in it.message }
    }

    @Test
    fun `missing source set diagnostic tells the user to choose another module or copy`() {
        val error = UserFacingError.from("SOURCE_SET_NOT_FOUND")

        assertThat(error.title).isEqualTo("Source set cannot be edited")
        assertThat(error.message).contains("another module", "Copy")
    }

    private fun ConfirmationState.toOptions(
        sourceSet: String? = selectedSourceSet,
        selectedCompanions: Set<String> = companions.filter { it.selected }.mapTo(linkedSetOf()) { it.id },
        incompatibilityPhrase: String = "",
    ) = AddOptions(
        version = selectedVersion,
        sourceSet = sourceSet,
        configuration = selectedConfiguration,
        selectedCompanionIds = selectedCompanions,
        acknowledgeUnknown = false,
        incompatibilityPhrase = incompatibilityPhrase,
    )

    private fun candidate(
        evidence: EvidenceKind = EvidenceKind.VERIFIED,
        targets: Set<TargetFamily> = setOf(TargetFamily.JVM, TargetFamily.ANDROID),
    ) = Candidate(
        Coordinates("io.ktor", "ktor-client-core"), "Ktor", listOf(
            DependencyVersion("3.1.0", true), DependencyVersion("3.2.0-RC", false),
        ), targets, evidence, emptySet(),
    )

    private fun snapshot() = ProjectSnapshot(
        "/project", "/project/gradle/libs.versions.toml",
        listOf(
            GradleModuleSnapshot(
                ":shared", "/project/shared/build.gradle.kts", setOf(TargetFamily.JVM, TargetFamily.ANDROID),
                listOf(
                    SourceSetNode("commonMain", setOf(TargetFamily.JVM, TargetFamily.ANDROID)),
                    SourceSetNode("jvmMain", setOf(TargetFamily.JVM), setOf("commonMain")),
                    SourceSetNode("iosMain", setOf(TargetFamily.IOS), setOf("commonMain")),
                ),
                emptySet(), setOf("implementation", "api", "ksp"),
            ),
        ), emptyMap(), emptySet(),
    )

    private class FakeBackend(initial: ProjectSnapshot) : AddDependencyBackend {
        var currentSnapshot = initial
        var snapshotReads = 0
        var lastSelection: DependencySelection? = null
        var planResult = PlanResult(ChangePlan(":shared", emptyList(), emptyList()), emptyList())
        var applyFailure: RuntimeException? = null

        override fun snapshot(): ProjectSnapshot = currentSnapshot.also { snapshotReads++ }
        override fun recipes(candidate: Candidate): List<DependencyRecipe> = listOf(
            DependencyRecipe(
                "ktor", candidate.coordinates, "implementation",
                companionPlugins = listOf(CompanionPlugin("serialization", "org.jetbrains.kotlin.plugin.serialization", true)),
                processors = listOf(ProcessorRequirement(Coordinates("io.ktor", "ktor-processor"), "ksp", false)),
                documentationUrl = "https://ktor.io",
            ),
        )
        override fun plan(selection: DependencySelection, project: ChangeProjectSnapshot): PlanResult {
            lastSelection = selection
            return planResult
        }
        override fun projectFor(snapshot: ProjectSnapshot, moduleId: String) =
            ChangeProjectSnapshot(moduleId, CatalogState(emptyMap(), emptyMap(), emptyMap()), setOf("api", "implementation", "ksp"))
        override fun preview(plan: ChangePlan, snapshot: ProjectSnapshot) = ChangePreview(
            listOf(
                file("/project/gradle/libs.versions.toml", PreviewFileKind.VERSION_CATALOG),
                file("/project/shared/build.gradle.kts", PreviewFileKind.KOTLIN_DSL),
            ),
        )
        override fun apply(preview: ChangePreview): ApplyResult {
            applyFailure?.let { throw it }
            return ApplyResult(preview.files.map { it.path })
        }
        private fun file(path: String, kind: PreviewFileKind) =
            FileChangePreview(path, kind, "old", "new", "hash", 1)
    }
}
