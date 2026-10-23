package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.model.Coordinates

/** A search query that names one Maven publication exactly: `group:artifact` or `group:artifact:version`. */
data class CoordinateQuery(val coordinates: Coordinates, val version: String? = null) {
    companion object {
        private val PATTERN = Regex("""^([A-Za-z0-9_][A-Za-z0-9_.-]*):([A-Za-z0-9_][A-Za-z0-9_.-]*)(?::([A-Za-z0-9_][A-Za-z0-9_.+-]*))?$""")

        fun parse(query: String): CoordinateQuery? {
            val match = PATTERN.matchEntire(query.trim()) ?: return null
            val (group, artifact, version) = match.destructured
            return CoordinateQuery(Coordinates(group, artifact), version.ifBlank { null })
        }
    }
}

/** True when [coordinates] are exactly what the user typed, by coordinates or artifact name. */
fun isExactQueryMatch(coordinates: Coordinates, query: String): Boolean {
    val normalized = query.trim().lowercase()
    CoordinateQuery.parse(normalized)?.let { return it.coordinates.notation.lowercase() == coordinates.notation.lowercase() }
    return coordinates.artifact.lowercase() == normalized
}
