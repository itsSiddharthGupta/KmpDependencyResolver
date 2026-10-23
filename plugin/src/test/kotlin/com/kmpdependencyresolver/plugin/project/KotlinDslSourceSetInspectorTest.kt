package com.kmpdependencyresolver.plugin.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kmpdependencyresolver.core.model.TargetFamily
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.psi.KtPsiFactory

class KotlinDslSourceSetInspectorTest : BasePlatformTestCase() {
    private fun inspect(script: String) =
        KotlinDslSourceSetInspector().inspect(KtPsiFactory(project).createFile("build.gradle.kts", script.trimIndent()))

    fun `test default hierarchy template models apple and native intermediates`() {
        val result = inspect(
            """
            kotlin {
                androidTarget()
                iosArm64()
                iosSimulatorArm64()
                macosArm64()
                linuxX64()
            }
            """,
        )
        val byName = result.sourceSets.associateBy { it.name }

        assertThat(byName["iosMain"]!!.descendantTargets).containsExactly(TargetFamily.IOS)
        assertThat(byName["iosMain"]!!.dependsOn).containsExactly("appleMain")
        assertThat(byName["appleMain"]!!.descendantTargets).containsExactlyInAnyOrder(TargetFamily.IOS, TargetFamily.MACOS)
        assertThat(byName["appleMain"]!!.dependsOn).containsExactly("nativeMain")
        assertThat(byName["nativeMain"]!!.descendantTargets).containsExactlyInAnyOrder(TargetFamily.IOS, TargetFamily.MACOS, TargetFamily.LINUX)
        assertThat(byName["nativeMain"]!!.dependsOn).containsExactly("commonMain")
        assertThat(byName["iosArm64Main"]!!.dependsOn).containsExactly("iosMain")
        assertThat(byName["androidMain"]!!.dependsOn).containsExactly("commonMain")
        assertThat(byName["commonMain"]!!.descendantTargets).hasSize(4)
        assertThat(byName["commonTest"]!!.descendantTargets).hasSize(4)
        assertThat(byName["iosTest"]!!.dependsOn).containsExactly("appleTest")
        assertThat(byName).doesNotContainKey("androidTest")
    }

    fun `test named target uses its custom source set name`() {
        val result = inspect(
            """
            kotlin {
                jvm("desktop")
                wasmJs { browser() }
                sourceSets {
                    val desktopMain by getting
                }
            }
            """,
        )
        val byName = result.sourceSets.associateBy { it.name }

        assertThat(byName["desktopMain"]!!.descendantTargets).containsExactly(TargetFamily.JVM)
        assertThat(byName["wasmJsMain"]!!.descendantTargets).containsExactly(TargetFamily.WASM)
        assertThat(byName).doesNotContainKey("jvmMain")
    }

    fun `test top level AGP android block is not a Kotlin target`() {
        val result = inspect(
            """
            android { namespace = "x" }
            kotlin {
                jvm()
            }
            """,
        )

        assertThat(result.targets).containsExactly(TargetFamily.JVM)
        assertThat(result.sourceSets.map { it.name }).doesNotContain("androidMain")
    }

    fun `test KMP modules always offer the standard configurations`() {
        val result = inspect("kotlin { jvm() }")

        assertThat(result.configurations).contains("implementation", "api", "compileOnly", "runtimeOnly")
        assertThat(result.copyOnlyReasons).isEmpty()
    }

    fun `test loops outside sourceSets do not make the module copy only`() {
        val outside = inspect(
            """
            kotlin {
                listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { } }
                sourceSets { commonMain.dependencies { } }
            }
            """,
        )
        val inside = inspect(
            """
            kotlin {
                jvm()
                sourceSets { listOf("a", "b").forEach { create(it + "Main") } }
            }
            """,
        )

        assertThat(outside.copyOnlyReasons).isEmpty()
        assertThat(inside.copyOnlyReasons).containsExactly("Dynamic source-set construction cannot be mapped confidently.")
    }

    fun `test module without targets is copy only`() {
        val result = inspect("plugins { alias(libs.plugins.kotlin.multiplatform) apply false }")

        assertThat(result.targets).isEmpty()
        assertThat(result.copyOnlyReasons).containsExactly("No Kotlin Multiplatform targets are declared in this module's kotlin { } block.")
    }
}
