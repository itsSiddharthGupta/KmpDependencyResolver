package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.change.AliasGenerator
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.recipe.DependencyRecipe

enum class CopyKind { COORDINATE, KOTLIN_DSL, VERSION_CATALOG, FULL_RECIPE }

data class CopySelection(
    val candidate: Candidate,
    val version: String,
    val sourceSet: String,
    val configuration: String,
    val recipe: DependencyRecipe? = null,
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
            processors.forEach { appendLine("    ${it.configuration}(libs.${accessor(AliasGenerator.kebab(it.coordinates.artifact))})") }
            append("}")
        }
    }

    private fun accessor(alias: String) = alias.replace(Regex("[-_]+"), ".")
}
