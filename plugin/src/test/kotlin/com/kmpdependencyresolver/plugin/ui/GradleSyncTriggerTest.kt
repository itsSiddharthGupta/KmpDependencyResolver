package com.kmpdependencyresolver.plugin.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GradleSyncTriggerTest {
    @Test
    fun `Android Studio uses its own sync action`() {
        // Both Android Studio 2025.2.3.9 and 2025.3.1.6 register Android.SyncProject and ExternalSystem.RefreshAllProjects.
        val androidStudio = setOf("Android.SyncProject", "ExternalSystem.RefreshAllProjects")

        assertThat(GradleSyncTrigger.select { it in androidStudio }).isEqualTo(SyncMethod.ANDROID_STUDIO_ACTION)
    }

    @Test
    fun `IntelliJ IDEA uses the Gradle refresh API instead of an action lookup`() {
        val intellij = setOf("ExternalSystem.RefreshAllProjects")

        assertThat(GradleSyncTrigger.select { it in intellij }).isEqualTo(SyncMethod.GRADLE_REFRESH)
        assertThat(GradleSyncTrigger.select { false }).isEqualTo(SyncMethod.GRADLE_REFRESH)
    }
}
