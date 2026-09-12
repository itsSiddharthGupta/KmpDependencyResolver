package com.kmpdependencyresolver.plugin.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kmpdependencyresolver.core.model.TargetFamily
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat

class ProjectModelReaderTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = Paths.get("src/test/testData").toAbsolutePath().toString()

    fun `test hierarchical Kotlin DSL project model`() {
        myFixture.copyDirectoryToProject("projects/hierarchical-kmp", "")

        val snapshot = JetBrainsProjectModelReader().read(project)
        val module = snapshot.modules.singleOrNull { it.id == ":shared" }
            ?: error("Expected :shared in ${snapshot.modules}; root=${snapshot.rootPath}")

        assertThat(snapshot.catalogPath).endsWith("gradle/libs.versions.toml")
        assertThat(snapshot.versions).containsEntry("kotlin", "2.1.20").containsEntry("agp", "8.8.2")
        assertThat(snapshot.existingAliases).contains("ktor-client-core")
        assertThat(module.buildFile).endsWith("shared/build.gradle.kts")
        assertThat(module.targets).containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.JVM, TargetFamily.IOS)
        assertThat(module.sourceSets.map { it.name }).contains("commonMain", "iosMain", "iosArm64Main", "iosSimulatorArm64Main", "androidMain", "jvmMain")
        assertThat(module.sourceSets.single { it.name == "iosArm64Main" }.dependsOn).contains("iosMain")
        assertThat(module.configurations).contains("implementation")
        assertThat(snapshot.addCapability(":shared")).isEqualTo(AddCapability.APPLY)
    }

    fun `test common list target loop is recognized without execution`() {
        myFixture.copyDirectoryToProject("projects/compose-multiplatform", "")

        val snapshot = JetBrainsProjectModelReader().read(project)
        val module = snapshot.modules.singleOrNull { it.id == ":composeApp" }
            ?: error("Expected :composeApp in ${snapshot.modules}; root=${snapshot.rootPath}")

        assertThat(module.targets).contains(TargetFamily.ANDROID, TargetFamily.IOS)
        assertThat(module.pluginAliases).contains("kotlin-multiplatform", "compose")
    }

    fun `test Groovy project is copy only with a precise reason`() {
        myFixture.copyDirectoryToProject("projects/unsupported-groovy", "")

        val snapshot = JetBrainsProjectModelReader().read(project)
        val module = snapshot.modules.singleOrNull { it.id == ":app" }
            ?: error("Expected :app in ${snapshot.modules}; root=${snapshot.rootPath}")

        assertThat(snapshot.addCapability(":app")).isEqualTo(AddCapability.COPY_ONLY)
        assertThat(module.copyOnlyReasons).containsExactly("Groovy build scripts are not safely editable; Kotlin DSL is required.")
    }
}
