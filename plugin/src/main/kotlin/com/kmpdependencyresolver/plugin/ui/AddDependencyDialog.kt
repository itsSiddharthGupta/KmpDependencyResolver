package com.kmpdependencyresolver.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.kmpdependencyresolver.core.change.ChangePlan
import com.kmpdependencyresolver.core.change.ChangeProjectSnapshot
import com.kmpdependencyresolver.core.change.DependencySelection
import com.kmpdependencyresolver.core.change.PlanResult
import com.kmpdependencyresolver.core.compatibility.SourceSetRecommender
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.ModuleModel
import com.kmpdependencyresolver.core.model.PlacementRecommendation
import com.kmpdependencyresolver.core.recipe.DependencyRecipe
import com.kmpdependencyresolver.plugin.editing.ApplyRejectedException
import com.kmpdependencyresolver.plugin.editing.ApplyResult
import com.kmpdependencyresolver.plugin.editing.ChangePreview
import com.kmpdependencyresolver.plugin.editing.PreviewException
import com.kmpdependencyresolver.plugin.project.ProjectSnapshot
import com.kmpdependencyresolver.plugin.project.AddCapability
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

data class CompanionChoice(
    val id: String,
    val label: String,
    val locked: Boolean,
    val selected: Boolean,
)

data class ConfirmationState(
    val candidate: Candidate,
    val moduleId: String,
    val versions: List<String>,
    val selectedVersion: String,
    val sourceSets: List<String>,
    val selectedSourceSet: String?,
    val configurations: List<String>,
    val selectedConfiguration: String,
    val recommendation: PlacementRecommendation,
    val recipe: DependencyRecipe?,
    val companions: List<CompanionChoice>,
    val snapshot: ProjectSnapshot,
    val notice: String? = null,
)

data class AddOptions(
    val version: String,
    val sourceSet: String?,
    val configuration: String,
    val selectedCompanionIds: Set<String>,
    val acknowledgeUnknown: Boolean,
    val incompatibilityPhrase: String,
)

data class AddPreviewState(
    val confirmation: ConfirmationState,
    val options: AddOptions,
    val preview: ChangePreview?,
    val warnings: List<String>,
    val error: UserFacingError? = null,
    val applyEnabled: Boolean = preview != null && error == null,
)

sealed interface AddApplyOutcome {
    data class Success(val relativePaths: List<String>, val suggestGradleSync: Boolean = true) : AddApplyOutcome
    data class Stale(val confirmation: ConfirmationState) : AddApplyOutcome
    data class Failure(val error: UserFacingError) : AddApplyOutcome
}

interface AddDependencyBackend {
    fun snapshot(): ProjectSnapshot
    fun recipes(candidate: Candidate): List<DependencyRecipe>
    fun projectFor(snapshot: ProjectSnapshot, moduleId: String): ChangeProjectSnapshot
    fun plan(selection: DependencySelection, project: ChangeProjectSnapshot): PlanResult
    fun preview(plan: ChangePlan, snapshot: ProjectSnapshot): ChangePreview
    fun apply(preview: ChangePreview): ApplyResult
}

class AddDependencyFlow(
    private val backend: AddDependencyBackend,
    private val recommender: SourceSetRecommender = SourceSetRecommender(),
) {
    fun start(candidate: Candidate, moduleId: String): ConfirmationState {
        val snapshot = backend.snapshot()
        val module = snapshot.modules.firstOrNull { it.id == moduleId }
            ?: throw IllegalArgumentException("Module $moduleId is unavailable")
        require(snapshot.catalogPath != null && snapshot.addCapability(moduleId) == AddCapability.APPLY) {
            "Add requires a writable Kotlin DSL module and gradle/libs.versions.toml"
        }
        val recommendation = recommender.recommend(candidate, ModuleModel(module.id, module.sourceSets))
        val compatibleSourceSets = module.sourceSets
            .filter { node -> node.descendantTargets.isNotEmpty() && candidate.supportedTargets.containsAll(node.descendantTargets) }
            .map { it.name }
        val sourceSets = (if (compatibleSourceSets.isEmpty()) module.sourceSets.map { it.name } else compatibleSourceSets).sorted()
        val recipe = backend.recipes(candidate).firstOrNull()
        val configurations = module.configurations.sorted()
        val preferred = recipe?.preferredConfiguration?.takeIf { it in configurations }
            ?: "implementation".takeIf { it in configurations }
            ?: configurations.firstOrNull()
            ?: throw IllegalArgumentException("Module $moduleId has no dependency configurations")
        val companions = buildList {
            recipe?.companionPlugins.orEmpty().forEach {
                add(CompanionChoice("plugin:${it.alias}", "Plugin: ${it.pluginId}", it.required, selected = true))
            }
            recipe?.processors.orEmpty().forEach {
                add(CompanionChoice("processor:${it.coordinates.notation}", "${it.configuration}: ${it.coordinates.notation}", it.required, selected = true))
            }
        }.sortedBy { it.id }
        val versions = candidate.versions.map { it.value }
        val selectedVersion = candidate.versions.firstOrNull { it.stable }?.value ?: versions.firstOrNull()
            ?: throw IllegalArgumentException("${candidate.coordinates.notation} has no selectable version")
        return remember(ConfirmationState(
            candidate, moduleId, versions, selectedVersion, sourceSets,
            recommendation.sourceSetName?.takeIf { it in sourceSets }, configurations, preferred,
            recommendation, recipe, companions, snapshot,
        ))
    }

    fun preview(options: AddOptions, confirmation: ConfirmationState): AddPreviewState {
        val current = confirmation
        val baseWarnings = buildList {
            add(current.recommendation.explanation)
            current.recipe?.constraints.orEmpty().forEach { add("${it.subject}: ${it.expression}") }
        }
        validateOptions(current, options)?.let { error ->
            return AddPreviewState(current, options, null, baseWarnings + error.message, error, false)
        }
        val required = current.companions.filter { it.locked }.mapTo(linkedSetOf()) { it.id }
        val selected = options.selectedCompanionIds + required
        val selection = DependencySelection(
            current.candidate, options.version, current.recommendation, options.configuration, current.recipe,
            overrideSourceSet = options.sourceSet,
            allowIncompatible = current.recommendation.evidence == EvidenceKind.INCOMPATIBLE,
            selectedCompanionIds = selected,
        )
        val planned = try {
            backend.plan(selection, backend.projectFor(current.snapshot, current.moduleId))
        } catch (exception: RuntimeException) {
            val error = UserFacingError.from("UNEXPECTED", exception)
            return AddPreviewState(current, options, null, baseWarnings + error.message, error, false)
        }
        val plan = planned.plan
        if (plan == null) {
            val conflict = planned.conflicts.firstOrNull()
            val error = UserFacingError.from(conflict?.code ?: "UNEXPECTED")
            return AddPreviewState(current, options, null, baseWarnings + planned.conflicts.map { it.message }, error, false)
        }
        return try {
            val preview = backend.preview(plan, current.snapshot)
            AddPreviewState(current, options.copy(selectedCompanionIds = selected), preview, baseWarnings)
        } catch (exception: PreviewException) {
            val error = UserFacingError.from(exception.code, exception)
            AddPreviewState(current, options, null, baseWarnings + error.message, error, false)
        } catch (exception: RuntimeException) {
            val error = UserFacingError.from("UNEXPECTED", exception)
            AddPreviewState(current, options, null, baseWarnings + error.message, error, false)
        }
    }

    fun preview(options: AddOptions): AddPreviewState = preview(
        options,
        requireNotNull(lastConfirmation) { "Start the Add flow before requesting a preview" },
    )

    fun apply(state: AddPreviewState): AddApplyOutcome {
        val preview = state.preview ?: return AddApplyOutcome.Failure(state.error ?: UserFacingError.from("UNEXPECTED"))
        if (!state.applyEnabled) return AddApplyOutcome.Failure(state.error ?: UserFacingError.from("UNEXPECTED"))
        return try {
            val result = backend.apply(preview)
            val root = Path.of(state.confirmation.snapshot.rootPath)
            val relative = result.changedPaths.map { path ->
                runCatching { root.relativize(Path.of(path)).toString() }.getOrDefault(path)
            }
            AddApplyOutcome.Success(relative)
        } catch (exception: ApplyRejectedException) {
            if (exception.code == "STALE_STATE") {
                val refreshed = start(state.confirmation.candidate, state.confirmation.moduleId).copy(
                    notice = "The project changed after preview. Review the refreshed choices and preview again.",
                )
                AddApplyOutcome.Stale(refreshed)
            } else {
                AddApplyOutcome.Failure(UserFacingError.from(exception.code, exception))
            }
        } catch (exception: RuntimeException) {
            AddApplyOutcome.Failure(UserFacingError.from("UNEXPECTED", exception))
        }
    }

    private var lastConfirmation: ConfirmationState? = null

    private fun validateOptions(state: ConfirmationState, options: AddOptions): UserFacingError? = when {
        options.version !in state.versions -> UserFacingError.from("VERSION_CONFLICT")
        options.sourceSet !in state.sourceSets -> UserFacingError.from("AMBIGUOUS_SOURCE_SETS")
        options.configuration !in state.configurations -> UserFacingError.from("UNSUPPORTED_PROJECT")
        state.recommendation.evidence == EvidenceKind.UNKNOWN && !options.acknowledgeUnknown ->
            UserFacingError("UNKNOWN_ACKNOWLEDGEMENT_REQUIRED", "Unknown target coverage", "Acknowledge the unknown target coverage before previewing changes.")
        state.recommendation.evidence == EvidenceKind.INCOMPATIBLE && options.incompatibilityPhrase.trim() != INCOMPATIBLE_PHRASE ->
            UserFacingError("INCOMPATIBLE_OVERRIDE_REQUIRED", "Known incompatibility", "Type '$INCOMPATIBLE_PHRASE' to continue.")
        else -> null
    }

    companion object {
        const val INCOMPATIBLE_PHRASE = "Add despite known incompatibility"
    }

    private fun remember(state: ConfirmationState): ConfirmationState = state.also { lastConfirmation = it }
}

class AddDependencyDialog(project: Project, private val state: ConfirmationState) : DialogWrapper(project) {
    private val version = JComboBox(state.versions.toTypedArray()).apply { selectedItem = state.selectedVersion }
    private val sourceSet = JComboBox(state.sourceSets.toTypedArray()).apply { selectedItem = state.selectedSourceSet }
    private val configuration = JComboBox(state.configurations.toTypedArray()).apply { selectedItem = state.selectedConfiguration }
    private val unknown = JBCheckBox("I understand that target compatibility is unknown")
    private val incompatibility = JBTextField()
    private val companionChecks = state.companions.associateWith { choice ->
        JBCheckBox(choice.label, choice.selected).apply { isEnabled = !choice.locked }
    }

    init {
        title = "Add ${state.candidate.displayName}"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).apply {
        state.notice?.let { add(JBLabel(it)) }
        add(JBLabel(state.recommendation.explanation))
        add(labeled("Version", version))
        add(labeled("Source set", sourceSet))
        add(labeled("Configuration", configuration))
        companionChecks.values.forEach(::add)
        if (state.recommendation.evidence == EvidenceKind.UNKNOWN) add(unknown)
        if (state.recommendation.evidence == EvidenceKind.INCOMPATIBLE) {
            add(JBLabel("Type '${AddDependencyFlow.INCOMPATIBLE_PHRASE}' to continue"))
            add(incompatibility)
        }
    }

    fun options() = AddOptions(
        version.selectedItem as String,
        sourceSet.selectedItem as? String,
        configuration.selectedItem as String,
        companionChecks.filterValues { it.isSelected }.keys.mapTo(linkedSetOf()) { it.id },
        unknown.isSelected,
        incompatibility.text,
    )

    private fun labeled(label: String, component: JComponent) = JPanel(GridLayout(1, 2)).apply {
        add(JBLabel(label)); add(component)
    }
}
