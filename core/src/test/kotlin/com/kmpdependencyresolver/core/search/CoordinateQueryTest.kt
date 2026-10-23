package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.model.Coordinates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoordinateQueryTest {
    @Test
    fun `parses group artifact and optional version`() {
        assertThat(CoordinateQuery.parse(" io.ktor:ktor-client-core "))
            .isEqualTo(CoordinateQuery(Coordinates("io.ktor", "ktor-client-core")))
        assertThat(CoordinateQuery.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"))
            .isEqualTo(CoordinateQuery(Coordinates("org.jetbrains.kotlinx", "kotlinx-coroutines-core"), "1.10.2"))
    }

    @Test
    fun `free text and partial coordinates are not exact queries`() {
        listOf("ktor-client-core", "ktor client", "io.ktor:", ":ktor", "a:b:c:d", "io.ktor:ktor client").forEach {
            assertThat(CoordinateQuery.parse(it)).describedAs(it).isNull()
        }
    }

    @Test
    fun `exact match accepts coordinates artifact name and ignores the version`() {
        val coordinates = Coordinates("io.ktor", "ktor-client-core")
        assertThat(isExactQueryMatch(coordinates, "io.ktor:ktor-client-core:3.1.0")).isTrue()
        assertThat(isExactQueryMatch(coordinates, "KTOR-CLIENT-CORE")).isTrue()
        assertThat(isExactQueryMatch(coordinates, "org.other:ktor-client-core")).isFalse()
        assertThat(isExactQueryMatch(coordinates, "ktor")).isFalse()
    }
}
