package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Coordinates

/**
 * Reads the subset of TOML that Gradle version catalogs use, without regex scraping:
 *
 * - `[versions]`: `name = "1.0"` or rich versions `{ strictly = "…" }`, `{ require = "…" }`, `{ prefer = "…" }`.
 * - `[libraries]`: `"group:artifact:version"`, `{ module = "g:a", version.ref = "x" }`, `{ module = "g:a", version = "1.0" }`,
 *   `{ group = "g", name = "a", version.ref = "x" }`, entries without a version (BOM-managed).
 * - `[plugins]`: `"id:version"`, `{ id = "…", version.ref = "x" }`, `{ id = "…", version = "1.0" }`.
 * - Dotted keys at the top level (`ktor.module = "…"`) and sub-tables (`[libraries.ktor]`).
 * - Comments, literal strings and multi-line arrays/inline tables are tolerated.
 *
 * Malformed lines are skipped rather than failing the whole catalog.
 */
object VersionCatalogParser {
    data class Catalog(
        val versions: Map<String, String>,
        val libraries: Map<String, CatalogLibrary>,
        val plugins: Map<String, CatalogPlugin>,
        val bundles: Set<String>,
    ) {
        fun toState() = CatalogState(versions, libraries, plugins)
    }

    fun parse(text: String): Catalog {
        // section -> alias -> flattened dotted fields ("" for a plain string value)
        val tables = linkedMapOf<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()
        var section = ""
        var subAlias: String? = null
        for (statement in statements(text)) {
            val header = HEADER.matchEntire(statement)
            if (header != null) {
                val path = splitKey(header.groupValues[1])
                section = path.firstOrNull().orEmpty()
                subAlias = path.drop(1).joinToString(".").ifEmpty { null }
                continue
            }
            val separator = topLevelIndexOf(statement, '=')
            if (separator < 0) continue
            val key = splitKey(statement.substring(0, separator).trim())
            if (key.isEmpty()) continue
            val value = statement.substring(separator + 1).trim()
            val alias = subAlias ?: key.first()
            val fieldPath = (if (subAlias != null) key else key.drop(1)).joinToString(".")
            val fields = tables.getOrPut(section) { linkedMapOf() }.getOrPut(alias) { linkedMapOf() }
            flatten(fieldPath, value, fields)
        }
        return Catalog(
            versions = tables["versions"].orEmpty().mapNotNull { (alias, fields) -> version(fields)?.let { alias to it } }.toMap(),
            libraries = tables["libraries"].orEmpty().mapNotNull { (alias, fields) -> library(fields)?.let { alias to it } }.toMap(),
            plugins = tables["plugins"].orEmpty().mapNotNull { (alias, fields) -> plugin(fields)?.let { alias to it } }.toMap(),
            bundles = tables["bundles"].orEmpty().keys,
        )
    }

    private fun version(fields: Map<String, String>): String? =
        fields[""] ?: fields["strictly"] ?: fields["require"] ?: fields["prefer"]

    private fun library(fields: Map<String, String>): CatalogLibrary? {
        fields[""]?.let { notation ->
            val parts = notation.split(':')
            if (parts.size !in 2..3 || parts.any(String::isBlank)) return null
            return CatalogLibrary(Coordinates(parts[0], parts[1]), null, parts.getOrNull(2))
        }
        val coordinates = fields["module"]?.let { module ->
            val parts = module.split(':')
            if (parts.size != 2 || parts.any(String::isBlank)) return null
            Coordinates(parts[0], parts[1])
        } ?: run {
            val group = fields["group"] ?: return null
            val name = fields["name"] ?: return null
            Coordinates(group, name)
        }
        return CatalogLibrary(coordinates, fields["version.ref"], literalVersion(fields))
    }

    private fun plugin(fields: Map<String, String>): CatalogPlugin? {
        fields[""]?.let { notation ->
            val id = notation.substringBefore(':').ifBlank { return null }
            return CatalogPlugin(id, null, notation.substringAfter(':', "").ifBlank { null })
        }
        val id = fields["id"] ?: return null
        return CatalogPlugin(id, fields["version.ref"], literalVersion(fields))
    }

    private fun literalVersion(fields: Map<String, String>): String? =
        fields["version"] ?: fields["version.strictly"] ?: fields["version.require"] ?: fields["version.prefer"]

    /** Records `value` under `path`; inline tables are expanded into dotted paths. */
    private fun flatten(path: String, value: String, into: MutableMap<String, String>) {
        when {
            value.startsWith("{") -> {
                val body = value.removePrefix("{").removeSuffix("}")
                splitTopLevel(body, ',').forEach { pair ->
                    val separator = topLevelIndexOf(pair, '=')
                    if (separator < 0) return@forEach
                    val key = splitKey(pair.substring(0, separator).trim()).joinToString(".")
                    if (key.isEmpty()) return@forEach
                    flatten(listOf(path, key).filter(String::isNotEmpty).joinToString("."), pair.substring(separator + 1).trim(), into)
                }
            }
            value.startsWith("[") -> into[path] = value
            else -> stringValue(value)?.let { into[path] = it }
        }
    }

    private fun stringValue(value: String): String? = when {
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> unescape(value.substring(1, value.length - 1))
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1)
        else -> null
    }

    private fun unescape(value: String): String = value.replace(Regex("""\\(["\\])"""), "$1")

    private fun splitKey(key: String): List<String> = splitTopLevel(key, '.')
        .map { part -> part.trim().let { stringValue(it) ?: it } }
        .filter(String::isNotEmpty)

    /** Joins physical lines into logical statements and strips comments outside strings. */
    private fun statements(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (line in text.lines()) {
            val stripped = stripComment(line).trim()
            if (stripped.isEmpty()) continue
            if (current.isNotEmpty()) current.append(' ')
            current.append(stripped)
            depth += nestingDelta(stripped)
            if (depth <= 0) {
                result += current.toString()
                current.clear()
                depth = 0
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quote == '"' && char == '\\' -> index++
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '#' -> return line.substring(0, index)
            }
            index++
        }
        return line
    }

    /** Net bracket depth of a statement, ignoring brackets inside strings; table headers net to zero. */
    private fun nestingDelta(text: String): Int {
        if (HEADER.matches(text)) return 0
        var depth = 0
        scan(text) { char, inString -> if (!inString) when (char) { '{', '[' -> depth++; '}', ']' -> depth-- } }
        return depth
    }

    private fun topLevelIndexOf(text: String, target: Char): Int {
        var depth = 0
        var found = -1
        var index = -1
        scan(text) { char, inString ->
            index++
            if (found >= 0 || inString) return@scan
            when (char) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                target -> if (depth == 0) found = index
            }
        }
        return found
    }

    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        scan(text) { char, inString ->
            if (!inString) when (char) { '{', '[' -> depth++; '}', ']' -> depth-- }
            if (!inString && depth == 0 && char == separator) {
                parts += current.toString(); current.clear()
            } else current.append(char)
        }
        parts += current.toString()
        return parts.map(String::trim).filter(String::isNotEmpty)
    }

    /** Visits every character, reporting whether it lies inside (or delimits) a string literal. */
    private inline fun scan(text: String, visit: (Char, Boolean) -> Unit) {
        var quote: Char? = null
        var escaped = false
        for (char in text) {
            if (quote != null) {
                visit(char, true)
                when {
                    escaped -> escaped = false
                    quote == '"' && char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
            } else if (char == '"' || char == '\'') {
                quote = char
                visit(char, true)
            } else {
                visit(char, false)
            }
        }
    }

    private val HEADER = Regex("""^\[\s*([^\[\]]+?)\s*]$""")
}
