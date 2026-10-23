package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.change.AliasGenerator
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.recipe.DependencyRecipe

enum class CopyKind(val label: String) {
    COORDINATE("Coordinate"),
    KOTLIN_DSL("Kotlin DSL"),
    VERSION_CATALOG("Version catalog"),
    FULL_RECIPE("Full recipe"),
}

/**
 * @property kspTargets Kotlin target names compiled by [sourceSet]; when present, KSP processors are
 * written as per-target `add("ksp<Target>", …)` calls, which is how KSP runs in Kotlin Multiplatform.
 */
data class CopySelection(
    val candidate: Candidate,
    val version: String,
    val sourceSet: String,
    val configuration: String,
    val recipe: DependencyRecipe? = null,
    val kspTargets: List<String> = emptyList(),
)

class CopyFormatter {
    fun format(selection: CopySelection, kind: CopyKind): String = when (kind) {
        CopyKind.COORDINATE -> "${selection.candidate.coordinates.notation}:${selection.version}"
        CopyKind.KOTLIN_DSL -> "${selection.configuration}(\"${selection.candidate.coordinates.notation}:${selection.version}\")"
        CopyKind.VERSION_CATALOG -> catalog(selection)
        CopyKind.FULL_RECIPE -> fullRecipe(selection)
    }

    private fun catalog(selection: CopySelection): String {
        val alias = AliasGenerator.kebab(selection.candidate.coordinates.artifact)
        return """[versions]
$alias = "${selection.version}"

[libraries]
$alias = { module = "${selection.candidate.coordinates.notation}", version.ref = "$alias" }"""
    }

    private fun fullRecipe(selection: CopySelection): String {
        val alias = AliasGenerator.kebab(selection.candidate.coordinates.artifact)
        val recipe = selection.recipe
        val processors = recipe?.processors.orEmpty().filter { it.required }
        val plugins = recipe?.companionPlugins.orEmpty().filter { it.required }
        val catalogLibraries = buildList {
            add("$alias = { module = \"${selection.candidate.coordinates.notation}\", version.ref = \"$alias\" }")
            processors.forEach { processor ->
                val processorAlias = AliasGenerator.kebab(processor.coordinates.artifact)
                add("$processorAlias = { module = \"${processor.coordinates.notation}\", version.ref = \"$alias\" }")
            }
        }
        return buildString {
            appendLine("[versions]")
            appendLine("$alias = \"${selection.version}\"")
            // The plugin version is not known here; the placeholder keeps version.ref resolvable once filled in.
            plugins.forEach { appendLine("${it.alias} = \"$PLUGIN_VERSION_PLACEHOLDER\" # set the ${it.pluginId} version") }
            appendLine()
            appendLine("[libraries]")
            catalogLibraries.forEach(::appendLine)
            if (plugins.isNotEmpty()) {
                appendLine()
                appendLine("[plugins]")
                plugins.forEach { appendLine("${it.alias} = { id = \"${it.pluginId}\", version.ref = \"${it.alias}\" }") }
            }
            appendLine()
            appendLine("plugins {")
            plugins.forEach { appendLine("    alias(libs.plugins.${accessor(it.alias)})") }
            appendLine("}")
            appendLine()
            appendLine("${selection.sourceSet}.dependencies {")
            appendLine("    ${selection.configuration}(libs.${accessor(alias)})")
            val (kmpProcessors, sourceSetProcessors) = processors.partition { it.configuration == "ksp" && selection.kspTargets.isNotEmpty() }
            sourceSetProcessors.forEach { appendLine("    ${it.configuration}(libs.${accessor(AliasGenerator.kebab(it.coordinates.artifact))})") }
            append("}")
            if (kmpProcessors.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("dependencies {")
                kmpProcessors.forEach { processor ->
                    val reference = "libs.${accessor(AliasGenerator.kebab(processor.coordinates.artifact))}"
                    selection.kspTargets.forEach { target ->
                        appendLine("    add(\"ksp${target.replaceFirstChar(Char::uppercaseChar)}\", $reference)")
                    }
                }
                append("}")
            }
        }
    }

    private fun accessor(alias: String) = alias.replace(Regex("[-_]+"), ".")

    companion object {
        const val PLUGIN_VERSION_PLACEHOLDER = "<plugin version>"
    }
}
