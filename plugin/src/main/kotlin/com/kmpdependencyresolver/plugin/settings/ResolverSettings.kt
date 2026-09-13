package com.kmpdependencyresolver.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Service(Service.Level.APP)
@State(name = "KmpDependencyResolverSettings", storages = [Storage("kmp-dependency-resolver.xml")])
class ResolverSettings : PersistentStateComponent<ResolverSettings.State> {
    data class State(
        var klibsEnabled: Boolean = true,
        var mavenCentralEnabled: Boolean = true,
        var googleMavenEnabled: Boolean = true,
        var offlineMode: Boolean = false,
        var includePreReleases: Boolean = false,
        var remoteRecipesEnabled: Boolean = true,
    )

    private var stored = State()

    override fun getState(): State = stored
    override fun loadState(state: State) {
        stored = state.copy()
    }

    fun enabledProviderIds(): List<String> {
        if (stored.offlineMode) return emptyList()
        return buildList {
            if (stored.klibsEnabled) add("klibs")
            if (stored.mavenCentralEnabled) add("maven-central")
            if (stored.googleMavenEnabled) add("google-maven")
        }
    }

    companion object {
        fun clearCache(systemPath: Path) {
            val target = systemPath.resolve("kmp-dependency-resolver/cache").normalize()
            require(target.startsWith(systemPath.normalize()) && target.fileName.toString() == "cache")
            if (!Files.exists(target)) return
            Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
