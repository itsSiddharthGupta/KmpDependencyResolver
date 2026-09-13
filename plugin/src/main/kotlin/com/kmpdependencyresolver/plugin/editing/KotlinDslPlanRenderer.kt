package com.kmpdependencyresolver.plugin.editing

import com.kmpdependencyresolver.core.change.GradleOperation

class KotlinDslPlanRenderer {
    fun render(original: String, operations: List<GradleOperation>): String {
        var result = original
        operations.filterIsInstance<GradleOperation.AddPluginAlias>().sortedBy { it.alias }.forEach { operation ->
            val accessor = accessor(operation.alias)
            val expression = "alias(libs.plugins.$accessor)" + if (operation.applyFalse) " apply false" else ""
            if (!result.contains(expression)) result = insertIntoBlock(result, "plugins", expression)
        }
        operations.filterIsInstance<GradleOperation.AddDependency>()
            .sortedWith(compareBy({ it.sourceSet }, { it.configuration }, { it.alias })).forEach { operation ->
                val reference = "libs.${accessor(operation.alias)}"
                val argument = if (operation.platform) "platform($reference)" else reference
                val expression = "${operation.configuration}($argument)"
                if (!result.contains(expression)) result = insertDependency(result, operation.sourceSet, expression)
            }
        return result
    }

    private fun insertDependency(text: String, sourceSet: String, expression: String): String {
        val directMarker = "$sourceSet.dependencies"
        val direct = text.indexOf(directMarker)
        if (direct >= 0) return insertAtBlock(text, text.indexOf('{', direct), expression)

        val sourceIndex = Regex("(?:val\\s+)?$sourceSet(?:\\s+by\\s+(?:getting|creating))?\\s*\\{").find(text)?.range?.first
            ?: throw RenderException("SOURCE_SET_NOT_FOUND", "Could not locate $sourceSet in Kotlin DSL.")
        val sourceBrace = text.indexOf('{', sourceIndex)
        val closing = matchingBrace(text, sourceBrace)
        val indent = lineIndent(text, sourceBrace) + "    "
        val block = "\n${indent}dependencies {\n${indent}    $expression\n$indent}"
        return text.substring(0, closing) + block + text.substring(closing)
    }

    private fun insertIntoBlock(text: String, blockName: String, expression: String): String {
        val marker = Regex("\\b$blockName\\s*\\{").find(text)
            ?: throw RenderException("BLOCK_NOT_FOUND", "Could not locate $blockName block.")
        return insertAtBlock(text, text.indexOf('{', marker.range.first), expression)
    }

    private fun insertAtBlock(text: String, openingBrace: Int, expression: String): String {
        val closing = matchingBrace(text, openingBrace)
        val baseIndent = lineIndent(text, openingBrace)
        val indent = "$baseIndent    "
        val closingLineStart = text.lastIndexOf('\n', closing - 1) + 1
        val closingHasOnlyIndent = text.substring(closingLineStart, closing).isBlank()
        return if (closingHasOnlyIndent) {
            text.substring(0, closingLineStart) + indent + expression + "\n" + text.substring(closingLineStart)
        } else {
            text.substring(0, closing) + "\n$indent$expression\n$baseIndent" + text.substring(closing)
        }
    }

    private fun matchingBrace(text: String, opening: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in opening until text.length) {
            val char = text[index]
            if (inString) {
                if (char == '"' && !escaped) inString = false
                escaped = char == '\\' && !escaped
                if (char != '\\') escaped = false
            } else when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        throw RenderException("UNBALANCED_BLOCK", "Kotlin DSL block is not balanced.")
    }

    private fun lineIndent(text: String, index: Int): String =
        text.substring(text.lastIndexOf('\n', index - 1) + 1, index).takeWhile { it == ' ' || it == '\t' }

    private fun accessor(alias: String) = alias.replace(Regex("[-_]+"), ".")
}

class RenderException(val code: String, message: String) : IllegalArgumentException(message)
