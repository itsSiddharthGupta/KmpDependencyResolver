package com.kmpdependencyresolver.plugin.editing

import com.kmpdependencyresolver.core.change.GradleOperation

/**
 * Applies planned operations to Kotlin DSL text. All searches run on a code mask in which comments and
 * string/char literals are blanked out, so commented-out declarations neither count as present nor
 * receive insertions, and braces inside literals never unbalance a block.
 */
class KotlinDslPlanRenderer {
    /** Renders module build-script operations; root-only operations are ignored here. */
    fun render(original: String, operations: List<GradleOperation>): String {
        var result = original
        operations.filterIsInstance<GradleOperation.AddPluginAlias>().sortedBy { it.alias }.forEach { operation ->
            val accessor = accessor(operation.alias)
            val expression = "alias(libs.plugins.$accessor)" + if (operation.applyFalse) " apply false" else ""
            if (!containsCode(result, Regex("alias\\(\\s*libs\\.plugins\\.${Regex.escape(accessor)}\\s*\\)"))) {
                result = insertIntoBlock(result, "plugins", expression)
            }
        }
        operations.filterIsInstance<GradleOperation.AddDependency>()
            .sortedWith(compareBy({ it.sourceSet }, { it.configuration }, { it.alias })).forEach { operation ->
                val reference = "libs.${accessor(operation.alias)}"
                val argument = if (operation.platform) "platform($reference)" else reference
                val expression = "${operation.configuration}($argument)"
                if (!containsDependency(result, operation.sourceSet, expression)) result = insertDependency(result, operation.sourceSet, expression)
            }
        operations.filterIsInstance<GradleOperation.AddTopLevelDependency>()
            .sortedWith(compareBy({ it.configuration }, { it.alias })).forEach { operation ->
                val expression = "add(\"${operation.configuration}\", libs.${accessor(operation.alias)})"
                if (!containsOutsideComments(result, expression)) result = insertTopLevelDependency(result, expression)
            }
        return result
    }

    /** Adds `alias(...) apply false` to the root script's `plugins { }`; a root without that block is left alone. */
    fun renderRoot(original: String, operations: List<GradleOperation>): String {
        var result = original
        operations.filterIsInstance<GradleOperation.AddRootPluginAlias>().sortedBy { it.alias }.forEach { operation ->
            val accessor = accessor(operation.alias)
            if (containsCode(result, Regex("alias\\(\\s*libs\\.plugins\\.${Regex.escape(accessor)}\\s*\\)"))) return@forEach
            if (findCode(result, Regex("\\bplugins\\s*\\{")) == null) return@forEach
            result = insertIntoBlock(result, "plugins", "alias(libs.plugins.$accessor) apply false")
        }
        return result
    }

    /** True when [literal] occurs in code; occurrences inside comments or strings do not count. */
    private fun containsOutsideComments(text: String, literal: String): Boolean {
        val mask = codeMask(text)
        var index = text.indexOf(literal)
        while (index >= 0) {
            if (mask[index] == text[index]) return true
            index = text.indexOf(literal, index + 1)
        }
        return false
    }

    private fun containsDependency(text: String, sourceSet: String, expression: String): Boolean {
        val mask = codeMask(text)
        val pattern = Regex(Regex.escape(expression).replace("\\(", "\\(\\s*").replace("\\)", "\\s*\\)"))
        return pattern.findAll(mask).any { match -> enclosingSourceSet(text, mask, match.range.first) == sourceSet }
    }

    /** Name of the source-set block (`commonMain.dependencies { }` / `val commonMain by getting { }`) around [index]. */
    private fun enclosingSourceSet(text: String, mask: String, index: Int): String? {
        val sourceSetBlock = Regex("\\b([A-Za-z][A-Za-z0-9]*)(?:\\.dependencies|\\s+by\\s+(?:getting|creating)|)\\s*\\{")
        return sourceSetBlock.findAll(mask).filter { match ->
            match.range.last < index && matchingBrace(text, mask, match.range.last) > index
        }.map { it.groupValues[1] }.lastOrNull { it.endsWith("Main") || it.endsWith("Test") }
    }

    private fun insertDependency(text: String, sourceSet: String, expression: String): String {
        val name = Regex.escape(sourceSet)
        findCode(text, Regex("(?<![A-Za-z0-9_])$name\\.dependencies\\s*\\{"))?.let { direct ->
            return insertAtBlock(text, direct.range.last, expression)
        }
        val sourceBlock = findCode(text, Regex("(?<![A-Za-z0-9_])(?:val\\s+)?$name(?:\\s+by\\s+(?:getting|creating))?\\s*\\{"))
            ?: return insertMissingSourceSet(text, sourceSet, expression)
        val sourceBrace = sourceBlock.range.last
        val mask = codeMask(text)
        val closing = matchingBrace(text, mask, sourceBrace)
        val inner = Regex("\\bdependencies\\s*\\{").find(mask.substring(sourceBrace + 1, closing))
        if (inner != null) return insertAtBlock(text, sourceBrace + 1 + inner.range.last, expression)
        val indent = lineIndent(text, sourceBrace) + "    "
        val block = "\n${indent}dependencies {\n${indent}    $expression\n$indent}"
        return text.substring(0, closing) + block + text.substring(closing)
    }

    private fun insertMissingSourceSet(text: String, sourceSet: String, expression: String): String {
        val marker = findCode(text, Regex("\\bsourceSets\\s*\\{"))
            ?: throw RenderException("SOURCE_SET_NOT_FOUND", "Could not locate $sourceSet or sourceSets in Kotlin DSL.")
        val openingBrace = marker.range.last
        val indent = lineIndent(text, openingBrace) + "    "
        val block = "$sourceSet.dependencies {\n$indent    $expression\n$indent}"
        return insertAtBlock(text, openingBrace, block)
    }

    /** Inserts into the first `dependencies { }` at the top level of the script, creating one at the end if needed. */
    private fun insertTopLevelDependency(text: String, expression: String): String {
        val mask = codeMask(text)
        val topLevel = Regex("\\bdependencies\\s*\\{").findAll(mask).firstOrNull { braceDepth(mask, it.range.first) == 0 }
        if (topLevel != null) return insertAtBlock(text, topLevel.range.last, expression)
        val separator = if (text.endsWith("\n")) "\n" else "\n\n"
        return text + separator + "dependencies {\n    $expression\n}\n"
    }

    private fun insertIntoBlock(text: String, blockName: String, expression: String): String {
        val marker = findCode(text, Regex("\\b$blockName\\s*\\{"))
            ?: throw RenderException("BLOCK_NOT_FOUND", "Could not locate $blockName block.")
        return insertAtBlock(text, marker.range.last, expression)
    }

    private fun insertAtBlock(text: String, openingBrace: Int, expression: String): String {
        val closing = matchingBrace(text, codeMask(text), openingBrace)
        val baseIndent = lineIndent(text, openingBrace)
        val indent = "$baseIndent    "
        val closingLineStart = text.lastIndexOf('\n', closing - 1) + 1
        val closingHasOnlyIndent = closingLineStart > openingBrace && text.substring(closingLineStart, closing).isBlank()
        return if (closingHasOnlyIndent) {
            text.substring(0, closingLineStart) + indent + expression + "\n" + text.substring(closingLineStart)
        } else {
            text.substring(0, closing) + "\n$indent$expression\n$baseIndent" + text.substring(closing)
        }
    }

    private fun matchingBrace(text: String, mask: String, opening: Int): Int {
        var depth = 0
        for (index in opening until mask.length) {
            when (mask[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        throw RenderException("UNBALANCED_BLOCK", "Kotlin DSL block is not balanced near offset $opening of ${text.length}.")
    }

    private fun braceDepth(mask: String, index: Int): Int {
        var depth = 0
        for (position in 0 until index) when (mask[position]) { '{' -> depth++; '}' -> depth-- }
        return depth
    }

    private fun findCode(text: String, pattern: Regex): MatchResult? = pattern.find(codeMask(text))
    private fun containsCode(text: String, pattern: Regex): Boolean = pattern.containsMatchIn(codeMask(text))

    private fun lineIndent(text: String, index: Int): String =
        text.substring(text.lastIndexOf('\n', index - 1) + 1, index).takeWhile { it == ' ' || it == '\t' }

    private fun accessor(alias: String) = alias.replace(Regex("[-_]+"), ".")

    companion object {
        /**
         * Same-length copy of [text] with comments and the contents of string, raw-string and char literals
         * replaced by spaces (newlines kept). Quotes stay, so `"…"` still reads as an argument. String
         * templates are treated as literal text, which is safe for brace matching because `${'$'}{…}` is balanced.
         */
        fun codeMask(text: String): String {
            val mask = StringBuilder(text)
            fun blank(from: Int, until: Int) { for (i in from until until) if (mask[i] != '\n') mask.setCharAt(i, ' ') }
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                        blank(index, end); index = end
                    }
                    text.startsWith("/*", index) -> {
                        var depth = 0
                        var position = index
                        while (position < text.length) {
                            if (text.startsWith("/*", position)) { depth++; position += 2; continue }
                            if (text.startsWith("*/", position)) { depth--; position += 2; if (depth == 0) break; continue }
                            position++
                        }
                        blank(index, position); index = position
                    }
                    text.startsWith("\"\"\"", index) -> {
                        var end = text.indexOf("\"\"\"", index + 3).let { if (it < 0) text.length else it + 3 }
                        while (end < text.length && text[end] == '"') end++
                        blank(index + 3, end - 3); index = end
                    }
                    text[index] == '"' || text[index] == '\'' -> {
                        val quote = text[index]
                        var position = index + 1
                        while (position < text.length && text[position] != quote && text[position] != '\n') {
                            position += if (text[position] == '\\') 2 else 1
                        }
                        val end = (position + 1).coerceAtMost(text.length)
                        blank(index + 1, (end - 1).coerceAtLeast(index + 1)); index = end
                    }
                    else -> index++
                }
            }
            return mask.toString()
        }
    }
}

class RenderException(val code: String, message: String) : IllegalArgumentException(message)
