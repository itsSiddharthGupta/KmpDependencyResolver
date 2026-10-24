package com.kmpdependencyresolver.plugin.project

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProjectSnapshotTest {
    private val snapshot = ProjectSnapshot(
        "/work", "/work/gradle/libs.versions.toml",
        listOf(
            module(":", "/work/build.gradle.kts", copyOnly = true),
            module(":shared", "/work/shared/build.gradle.kts"),
            module(":shared:ui", "/work/shared/ui/build.gradle.kts"),
            module(":sharedExtras", "/work/sharedExtras/build.gradle.kts"),
        ),
        emptyMap(), emptySet(),
    )

    @Test
    fun `selected file maps to the nearest enclosing module directory`() {
        assertThat(snapshot.defaultModuleId("/work/shared/ui/src/commonMain/kotlin/App.kt")).isEqualTo(":shared:ui")
        assertThat(snapshot.defaultModuleId("/work/shared/src/iosMain/kotlin/Platform.kt")).isEqualTo(":shared")
        assertThat(snapshot.defaultModuleId("/work/sharedExtras/build.gradle.kts")).isEqualTo(":sharedExtras")
        assertThat(snapshot.defaultModuleId("/work/settings.gradle.kts")).isEqualTo(":")
    }

    @Test
    fun `without a selected file the first editable module is preselected`() {
        assertThat(snapshot.defaultModuleId(null)).isEqualTo(":shared")
        assertThat(snapshot.defaultModuleId("/elsewhere/Notes.kt")).isEqualTo(":shared")
    }

    private fun module(id: String, buildFile: String, copyOnly: Boolean = false) = GradleModuleSnapshot(
        id, buildFile, emptySet(), emptyList(), emptySet(), emptySet(),
        if (copyOnly) listOf("No Kotlin Multiplatform targets") else emptyList(),
    )
}
