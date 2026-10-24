package com.kmpdependencyresolver.core.change

import com.kmpdependencyresolver.core.model.Coordinates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VersionCatalogParserTest {
    @Test
    fun `parses every library and plugin notation Gradle accepts`() {
        val catalog = VersionCatalogParser.parse(
            """
            # Shared versions
            [versions]
            kotlin = "2.1.20" # trailing comment
            ktor = { strictly = "3.1.0" }
            agp = '8.8.2'

            [libraries]
            plain = "com.squareup.okio:okio:3.9.1"
            module-ref = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
            module-literal = { module = "io.coil-kt.coil3:coil", version = "3.1.0" }
            split-ref = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
            bom-managed = { module = "androidx.compose.ui:ui" }
            nested-version = { module = "a.b:c", version = { strictly = "1.0" } }

            [plugins]
            plain-plugin = "com.google.devtools.ksp:2.1.20-1.0.31"
            ref-plugin = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
            literal-plugin = { id = "com.android.library", version = "8.8.2" }

            [bundles]
            ktor = [
                "module-ref",
                "plain", # comment inside array
            ]
            """.trimIndent(),
        )

        assertThat(catalog.versions).containsExactlyInAnyOrderEntriesOf(mapOf("kotlin" to "2.1.20", "ktor" to "3.1.0", "agp" to "8.8.2"))
        assertThat(catalog.libraries).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "plain" to CatalogLibrary(Coordinates("com.squareup.okio", "okio"), null, "3.9.1"),
                "module-ref" to CatalogLibrary(Coordinates("io.ktor", "ktor-client-core"), "ktor"),
                "module-literal" to CatalogLibrary(Coordinates("io.coil-kt.coil3", "coil"), null, "3.1.0"),
                "split-ref" to CatalogLibrary(Coordinates("androidx.room", "room-runtime"), "room"),
                "bom-managed" to CatalogLibrary(Coordinates("androidx.compose.ui", "ui"), null),
                "nested-version" to CatalogLibrary(Coordinates("a.b", "c"), null, "1.0"),
            ),
        )
        assertThat(catalog.plugins).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "plain-plugin" to CatalogPlugin("com.google.devtools.ksp", null, "2.1.20-1.0.31"),
                "ref-plugin" to CatalogPlugin("org.jetbrains.kotlin.multiplatform", "kotlin"),
                "literal-plugin" to CatalogPlugin("com.android.library", null, "8.8.2"),
            ),
        )
        assertThat(catalog.bundles).containsExactly("ktor")
    }

    @Test
    fun `dotted keys and sub tables describe the same entries`() {
        val catalog = VersionCatalogParser.parse(
            """
            [libraries]
            ktor-core.module = "io.ktor:ktor-client-core"
            ktor-core.version.ref = "ktor"

            [libraries.coil]
            group = "io.coil-kt.coil3"
            name = "coil"
            version = "3.1.0"

            [plugins.ksp]
            id = "com.google.devtools.ksp"
            version.ref = "ksp"
            """.trimIndent(),
        )

        assertThat(catalog.libraries["ktor-core"]).isEqualTo(CatalogLibrary(Coordinates("io.ktor", "ktor-client-core"), "ktor"))
        assertThat(catalog.libraries["coil"]).isEqualTo(CatalogLibrary(Coordinates("io.coil-kt.coil3", "coil"), null, "3.1.0"))
        assertThat(catalog.plugins["ksp"]).isEqualTo(CatalogPlugin("com.google.devtools.ksp", "ksp"))
    }

    @Test
    fun `malformed entries are skipped without losing the rest`() {
        val catalog = VersionCatalogParser.parse(
            """
            [versions]
            broken =
            good = "1.0"
            [libraries]
            bad-notation = "not-a-coordinate"
            hash-in-string = { module = "dev.x:y", version = "1.0#beta" }
            """.trimIndent(),
        )

        assertThat(catalog.versions).containsExactly(org.assertj.core.api.Assertions.entry("good", "1.0"))
        assertThat(catalog.libraries.keys).containsExactly("hash-in-string")
        assertThat(catalog.libraries["hash-in-string"]!!.version).isEqualTo("1.0#beta")
    }
}
