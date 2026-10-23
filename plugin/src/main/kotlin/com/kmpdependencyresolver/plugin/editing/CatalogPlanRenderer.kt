package com.kmpdependencyresolver.plugin.editing

import com.kmpdependencyresolver.core.change.CatalogOperation
import com.kmpdependencyresolver.core.change.VersionCatalogParser

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
        val existing = parseTable(table, lines.subList(header + 1, nextHeader))
        val fresh = additions.filter { line ->
            val key = line.substringBefore('=').trim()
            val current = existing[key] ?: return@filter true
            // Re-adding an identical entry is a no-op; a different entry under the same key would be silently lost.
            if (current != parseTable(table, listOf(line))[key]) {
                throw RenderException("ALIAS_COLLISION", "[$table] already defines '$key' differently.")
            }
            false
        }
        lines.addAll(insertion, fresh.sorted())
        return lines
    }

    /** Parsed entries of one table, keyed by alias, so formatting differences do not count as conflicts. */
    private fun parseTable(table: String, lines: List<String>): Map<String, Any> {
        val catalog = VersionCatalogParser.parse("[$table]\n" + lines.joinToString("\n"))
        return when (table) {
            "versions" -> catalog.versions
            "libraries" -> catalog.libraries
            "plugins" -> catalog.plugins
            else -> emptyMap()
        }
    }
}
