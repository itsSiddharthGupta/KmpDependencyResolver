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

    fun `test nested project paths skip buildSrc and included builds and read the catalog`() {
        myFixture.copyDirectoryToProject("projects/multi-build", "")

        val snapshot = JetBrainsProjectModelReader().read(project)

        assertThat(snapshot.modules.map { it.id }).containsExactly(":", ":feature:shared")
        assertThat(snapshot.addCapability(":")).isEqualTo(AddCapability.COPY_ONLY)
        assertThat(snapshot.addCapability(":feature:shared")).isEqualTo(AddCapability.APPLY)
        assertThat(snapshot.existingAliases).containsExactly("ktor")
        assertThat(snapshot.versions).containsEntry("kotlin", "2.2.0")
    }

    fun `test unsaved build script edits are part of the model`() {
        myFixture.copyDirectoryToProject("projects/multi-build", "")
        val buildFile = myFixture.findFileInTempDir("feature/shared/build.gradle.kts")
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(buildFile)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText("kotlin {\n    jvm()\n    iosArm64()\n    wasmJs()\n}\n")
        }

        val module = JetBrainsProjectModelReader().read(project).modules.single { it.id == ":feature:shared" }

        assertThat(module.targets).contains(TargetFamily.WASM)
    }

    fun `test KMP project with a plain JVM Ktor server module`() {
        // Layout of a real project: KMP app/network/core modules plus a kotlin("jvm") Ktor :server.
        myFixture.copyDirectoryToProject("projects/kmp-with-jvm-server", "")

        val snapshot = JetBrainsProjectModelReader().read(project)
        val byId = snapshot.modules.associateBy { it.id }

        assertThat(byId.keys).containsExactlyInAnyOrder(":", ":app:androidApp", ":app:network", ":app:shared", ":core", ":server")
        assertThat(snapshot.addUnavailableReason(":server"))
            .isEqualTo(":server isn't a Kotlin Multiplatform module. Add supports KMP modules only; use Copy to get the declaration.")
        assertThat(snapshot.addUnavailableReason(":app:androidApp")).contains("isn't a Kotlin Multiplatform module")
        assertThat(snapshot.addUnavailableReason(":app:network")).isNull()
        assertThat(snapshot.addUnavailableReason(":app:shared")).isNull()
        assertThat(snapshot.addUnavailableReason(":core")).isNull()
        val network = byId.getValue(":app:network")
        assertThat(network.targets).containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.IOS)
        assertThat(network.sourceSets.map { it.name }).contains("commonMain", "androidMain", "iosMain", "iosArm64Main")
        assertThat(byId.getValue(":core").targets).containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.IOS, TargetFamily.JVM)
        assertThat(snapshot.existingAliases).contains("ktor-serverCore", "ktor-clientCore")
    }

    fun `test gradle path is relative to the build root`() {
        assertThat(JetBrainsProjectModelReader.gradlePath("/work/app", "/work/app")).isEqualTo(":")
        assertThat(JetBrainsProjectModelReader.gradlePath("/work/app/", "/work/app/feature/shared")).isEqualTo(":feature:shared")
    }
}
