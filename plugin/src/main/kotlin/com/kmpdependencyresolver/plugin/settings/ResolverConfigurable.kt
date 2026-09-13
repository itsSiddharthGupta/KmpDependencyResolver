package com.kmpdependencyresolver.plugin.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ResolverConfigurable : Configurable {
    private val settings = service<ResolverSettings>()
    private val klibs = JBCheckBox("Enable klibs.io")
    private val central = JBCheckBox("Enable Maven Central")
    private val google = JBCheckBox("Enable Google Maven")
    private val offline = JBCheckBox("Offline mode (cached results only)")
    private val previews = JBCheckBox("Include pre-release versions by default")
    private val recipes = JBCheckBox("Enable verified remote recipe updates")
    private var panel: JPanel? = null

    override fun getDisplayName() = "KMP Dependency Resolver"

    override fun createComponent(): JComponent = JPanel(GridLayout(0, 1, 0, JBUI.scale(6))).also { root ->
        root.add(JBLabel("Search providers"))
        root.add(klibs); root.add(JBLabel("api.klibs.io (fixed)"))
        root.add(central); root.add(JBLabel("search.maven.org and repo1.maven.org (fixed)"))
        root.add(google); root.add(JBLabel("dl.google.com (fixed)"))
        root.add(offline)
        root.add(previews)
        root.add(recipes)
        root.add(JBLabel("Plugin metadata: plugins.gradle.org (fixed)"))
        root.add(JBLabel("Remote recipes: raw.githubusercontent.com and github.com (fixed)"))
        root.add(JButton("Clear Cache…").apply {
            addActionListener {
                if (Messages.showYesNoDialog(
                        "Remove locally cached KMP Dependency Resolver search and recipe data?",
                        "Clear KMP Dependency Resolver Cache",
                        null,
                    ) == Messages.YES
                ) ResolverSettings.clearCache(Path.of(PathManager.getSystemPath()))
            }
        })
        panel = root
        reset()
    }

    override fun isModified(): Boolean = with(settings.state) {
        klibs.isSelected != klibsEnabled || central.isSelected != mavenCentralEnabled ||
            google.isSelected != googleMavenEnabled || offline.isSelected != offlineMode ||
            previews.isSelected != includePreReleases || recipes.isSelected != remoteRecipesEnabled
    }

    override fun apply() {
        settings.loadState(
            ResolverSettings.State(
                klibs.isSelected, central.isSelected, google.isSelected,
                offline.isSelected, previews.isSelected, recipes.isSelected,
            ),
        )
    }

    override fun reset() = with(settings.state) {
        klibs.isSelected = klibsEnabled
        central.isSelected = mavenCentralEnabled
        google.isSelected = googleMavenEnabled
        offline.isSelected = offlineMode
        previews.isSelected = includePreReleases
        recipes.isSelected = remoteRecipesEnabled
    }

    override fun disposeUIResources() {
        panel = null
    }
}
