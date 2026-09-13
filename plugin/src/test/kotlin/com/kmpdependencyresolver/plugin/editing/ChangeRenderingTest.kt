package com.kmpdependencyresolver.plugin.editing

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kmpdependencyresolver.core.change.CatalogOperation
import com.kmpdependencyresolver.core.change.GradleOperation
import com.kmpdependencyresolver.core.model.Coordinates
import java.nio.file.Files
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.psi.KtPsiFactory

class ChangeRenderingTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = Paths.get("src/test/testData").toAbsolutePath().toString()

    fun `test renders catalog and commonMain byte for byte while preserving comments`() {
        val catalog = CatalogPlanRenderer().render(
            fixture("catalog.before.toml"),
            listOf(
                CatalogOperation.PutVersion("ktor-client-core", "3.1.0"),
                CatalogOperation.PutLibrary("ktor-client-core", Coordinates("io.ktor", "ktor-client-core"), "ktor-client-core"),
            ),
        )
        val build = KotlinDslPlanRenderer().render(
            fixture("build.before.gradle.kts"),
            listOf(GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false)),
        )

        assertThat(catalog).isEqualTo(fixture("catalog.after.toml"))
        assertThat(build).isEqualTo(fixture("build.after.gradle.kts"))
        assertThat(PsiTreeUtil.findChildOfType(KtPsiFactory(project).createFile("build.gradle.kts", build), PsiErrorElement::class.java)).isNull()
        val toml = PsiFileFactory.getInstance(project).createFileFromText("libs.versions.toml", catalog)
        assertThat(PsiTreeUtil.findChildOfType(toml, PsiErrorElement::class.java)).isNull()
    }

    fun `test renders BOM plugins processors and missing dependency blocks`() {
        val original = """plugins {
}
kotlin {
    sourceSets {
        val androidMain by getting {
        }
    }
}
"""
        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(
                GradleOperation.AddPluginAlias("ksp", false),
                GradleOperation.AddDependency("androidMain", "implementation", "compose-bom", true),
                GradleOperation.AddDependency("androidMain", "ksp", "room-compiler", false),
            ),
        )

        assertThat(rendered).contains("alias(libs.plugins.ksp)")
        assertThat(rendered).contains("implementation(platform(libs.compose.bom))")
        assertThat(rendered).contains("ksp(libs.room.compiler)")
        assertThat(rendered).contains("dependencies {")
        assertThat(PsiTreeUtil.findChildOfType(KtPsiFactory(project).createFile("build.gradle.kts", rendered), PsiErrorElement::class.java)).isNull()
    }

    private fun fixture(name: String): String = Files.readString(Paths.get(testDataPath, "editing", name))
}
