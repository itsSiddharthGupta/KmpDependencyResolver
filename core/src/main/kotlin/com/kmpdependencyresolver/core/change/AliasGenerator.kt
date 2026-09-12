package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Coordinates

class AliasGenerator {
    fun generate(coordinates: Coordinates, existingAliases: Set<String>): String? {
        val occupied = existingAliases.map(::accessorKey).toSet()
        val artifact = kebab(coordinates.artifact)
        if (accessorKey(artifact) !in occupied) return artifact
        val groupTokens = coordinates.group.split('.').asReversed()
            .map(::kebab).filter { it.isNotBlank() && it !in GENERIC_GROUP_TOKENS }
        for (token in groupTokens) {
            val candidate = "$token-$artifact"
            if (accessorKey(candidate) !in occupied) return candidate
        }
        return null
    }

    companion object {
        fun accessorKey(alias: String): String = alias.lowercase().replace(Regex("[-_.]+"), ".")
        fun kebab(value: String): String = value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-').lowercase()

        private val GENERIC_GROUP_TOKENS = setOf("com", "org", "io", "dev", "net")
    }
}
