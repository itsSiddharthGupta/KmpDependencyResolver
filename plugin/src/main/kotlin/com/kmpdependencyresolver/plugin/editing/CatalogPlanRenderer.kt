package com.kmpdependencyresolver.plugin.editing

import com.kmpdependencyresolver.core.change.CatalogOperation

class CatalogPlanRenderer {
    fun render(original: String, operations: List<CatalogOperation>): String {
        var lines = original.removeSuffix("\n").split('\n').toMutableList()
        val versions = operations.filterIsInstance<CatalogOperation.PutVersion>()
            .map { "${it.alias} = \"${it.value}\"" }
        val libraries = operations.filterIsInstance<CatalogOperation.PutLibrary>().map { operation ->
            val version = operation.versionAlias?.let { ", version.ref = \"$it\"" }.orEmpty()
            "${operation.alias} = { module = \"${operation.coordinates.notation}\"$version }"
        }
        val plugins = operations.filterIsInstance<CatalogOperation.PutPlugin>()
            .map { "${it.alias} = { id = \"${it.pluginId}\", version.ref = \"${it.versionAlias}\" }" }
        lines = insert(lines, "versions", versions)
        lines = insert(lines, "libraries", libraries)
        lines = insert(lines, "plugins", plugins)
        return lines.joinToString("\n") + "\n"
    }

    private fun insert(input: MutableList<String>, table: String, additions: List<String>): MutableList<String> {
        if (additions.isEmpty()) return input
        val lines = input.toMutableList()
        var header = lines.indexOfFirst { it.trim() == "[$table]" }
        if (header < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$table]"
            header = lines.lastIndex
        }
        val nextHeader = (header + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        var insertion = nextHeader
        while (insertion > header + 1 && lines[insertion - 1].isBlank()) insertion--
        val existingKeys = lines.subList(header + 1, nextHeader).mapNotNull {
            Regex("^\\s*([A-Za-z0-9_.-]+)\\s*=").find(it)?.groupValues?.get(1)
        }.toSet()
        lines.addAll(insertion, additions.filter { line -> line.substringBefore('=').trim() !in existingKeys }.sorted())
        return lines
    }
}
