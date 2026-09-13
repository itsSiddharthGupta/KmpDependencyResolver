package com.kmpdependencyresolver.plugin.settings

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResolverSettingsTest {
    @Test
    fun `defaults enable providers remote recipes and stable releases`() {
        val state = ResolverSettings().state

        assertThat(state.klibsEnabled).isTrue()
        assertThat(state.mavenCentralEnabled).isTrue()
        assertThat(state.googleMavenEnabled).isTrue()
        assertThat(state.remoteRecipesEnabled).isTrue()
        assertThat(state.offlineMode).isFalse()
        assertThat(state.includePreReleases).isFalse()
    }

    @Test
    fun `loaded state controls provider ids and offline preserves enabled providers for cached search`() {
        val settings = ResolverSettings()
        settings.loadState(ResolverSettings.State(googleMavenEnabled = false))
        assertThat(settings.enabledProviderIds()).containsExactly("klibs", "maven-central")

        val recreated = ResolverSettings().apply { loadState(settings.state) }
        assertThat(recreated.state.googleMavenEnabled).isFalse()

        settings.state.offlineMode = true
        assertThat(settings.enabledProviderIds()).containsExactly("klibs", "maven-central")
    }

    @Test
    fun `clear cache removes only resolver cache`() {
        val system = Files.createTempDirectory("resolver-system-")
        val resolver = system.resolve("kmp-dependency-resolver/cache")
        val unrelated = system.resolve("other-plugin/keep.txt")
        Files.createDirectories(resolver)
        Files.createDirectories(unrelated.parent)
        Files.writeString(resolver.resolve("entry.json"), "cached")
        Files.writeString(unrelated, "keep")

        ResolverSettings.clearCache(system)

        assertThat(resolver).doesNotExist()
        assertThat(unrelated).exists()
        system.toFile().deleteRecursively()
    }
}
