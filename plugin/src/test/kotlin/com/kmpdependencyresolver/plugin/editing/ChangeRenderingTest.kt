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

    fun `test creates a missing source set dependency block inside sourceSets`() {
        val original = """plugins {
}
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.core)
        }
    }
}
"""

        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false)),
        )

        assertThat(rendered).contains("commonMain.dependencies {")
        assertThat(rendered).contains("implementation(libs.ktor.client.core)")
        assertThat(PsiTreeUtil.findChildOfType(KtPsiFactory(project).createFile("build.gradle.kts", rendered), PsiErrorElement::class.java)).isNull()
    }

    fun `test differing catalog entry under an existing key is an alias collision`() {
        val original = "[versions]\nktor = \"3.0.0\"\n\n[libraries]\nktor-client-core = { module = \"io.ktor:ktor-client-core\", version.ref = \"ktor\" }\n"
        val same = CatalogPlanRenderer().render(
            original,
            listOf(CatalogOperation.PutLibrary("ktor-client-core", Coordinates("io.ktor", "ktor-client-core"), "ktor")),
        )

        assertThat(same).isEqualTo(original)
        val error = runCatching {
            CatalogPlanRenderer().render(
                original,
                listOf(CatalogOperation.PutLibrary("ktor-client-core", Coordinates("org.other", "ktor-client-core"), "ktor")),
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RenderException::class.java)
        assertThat((error as RenderException).code).isEqualTo("ALIAS_COLLISION")
        val versionError = runCatching {
            CatalogPlanRenderer().render(original, listOf(CatalogOperation.PutVersion("ktor", "3.1.0")))
        }.exceptionOrNull() as? RenderException
        assertThat(versionError?.code).isEqualTo("ALIAS_COLLISION")
    }

    fun `test KMP KSP processors go into a top level dependencies block per target`() {
        val original = """plugins {
    alias(libs.plugins.kotlin.multiplatform)
}
kotlin {
    androidTarget()
    iosArm64()
    sourceSets {
        commonMain.dependencies {
        }
    }
}
"""
        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(
                GradleOperation.AddPluginAlias("ksp", false),
                GradleOperation.AddDependency("commonMain", "implementation", "room-runtime", false),
                GradleOperation.AddTopLevelDependency("kspAndroid", "room-compiler"),
                GradleOperation.AddTopLevelDependency("kspIosArm64", "room-compiler"),
            ),
        )

        assertThat(rendered).endsWith("""
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
}
""")
        assertThat(rendered).contains("        commonMain.dependencies {\n            implementation(libs.room.runtime)\n        }")
        assertThat(rendered).doesNotContain("ksp(libs")
        assertThat(KotlinDslPlanRenderer().render(rendered, listOf(GradleOperation.AddTopLevelDependency("kspAndroid", "room-compiler"))))
            .isEqualTo(rendered)
        assertThat(PsiTreeUtil.findChildOfType(KtPsiFactory(project).createFile("build.gradle.kts", rendered), PsiErrorElement::class.java)).isNull()
    }

    fun `test existing top level dependencies block is reused and nested ones are ignored`() {
        val original = """kotlin {
    sourceSets {
        commonMain.dependencies {
        }
    }
}

dependencies {
    debugImplementation(libs.leakcanary)
}
"""
        val rendered = KotlinDslPlanRenderer().render(original, listOf(GradleOperation.AddTopLevelDependency("kspAndroid", "room-compiler")))

        assertThat(rendered).isEqualTo("""kotlin {
    sourceSets {
        commonMain.dependencies {
        }
    }
}

dependencies {
    debugImplementation(libs.leakcanary)
    add("kspAndroid", libs.room.compiler)
}
""")
    }

    fun `test braces and declarations in comments and literals are ignored`() {
        val original = """kotlin {
    sourceSets {
        // commonMain.dependencies { implementation(libs.ktor.client.core) }
        /* androidMain.dependencies {
           implementation(libs.ktor.client.core) */
        val label = "{ not a block"
        val raw = ${"\"\"\""}{ { ${"\"\"\""}
        val brace = '{'
        commonMain.dependencies {
        }
    }
}
"""
        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false)),
        )

        assertThat(rendered).contains("        commonMain.dependencies {\n            implementation(libs.ktor.client.core)\n        }")
        assertThat(rendered.lines().count { it.contains("implementation(libs.ktor.client.core)") }).isEqualTo(3)
    }

    fun `test source set names use word boundaries and duplicates are per source set`() {
        val original = """kotlin {
    sourceSets {
        iosArm64Main.dependencies {
            implementation(libs.ktor.client.core)
        }
        armMain.dependencies {
        }
    }
}
"""
        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(GradleOperation.AddDependency("armMain", "implementation", "ktor-client-core", false)),
        )

        assertThat(rendered).contains("        armMain.dependencies {\n            implementation(libs.ktor.client.core)\n        }")
        assertThat(rendered).contains("        iosArm64Main.dependencies {\n            implementation(libs.ktor.client.core)\n        }")
    }

    fun `test root companion plugin alias is added with apply false only when a plugins block exists`() {
        val root = """plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}
"""
        val renderer = KotlinDslPlanRenderer()

        assertThat(renderer.renderRoot(root, listOf(GradleOperation.AddRootPluginAlias("ksp"))))
            .isEqualTo("""plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
}
""")
        val already = "plugins {\n    alias(libs.plugins.ksp) apply false\n}\n"
        assertThat(renderer.renderRoot(already, listOf(GradleOperation.AddRootPluginAlias("ksp")))).isEqualTo(already)
        assertThat(renderer.renderRoot("allprojects { }\n", listOf(GradleOperation.AddRootPluginAlias("ksp")))).isEqualTo("allprojects { }\n")
    }

    fun `test Kotlin JVM module dependencies go into the top level block`() {
        val serverScript = Files.readString(Paths.get(testDataPath, "projects/kmp-with-jvm-server/server/build.gradle.kts"))
        val operations = listOf(
            GradleOperation.AddPluginAlias("ksp", false),
            GradleOperation.AddDependency("main", "implementation", "ktor-bom", true),
            GradleOperation.AddDependency("main", "implementation", "ktor-server-content-negotiation", false),
            GradleOperation.AddDependency("main", "ksp", "room-compiler", false),
        )

        val rendered = KotlinDslPlanRenderer().render(serverScript, operations)

        assertThat(rendered).contains("    alias(libs.plugins.ktor)\n    alias(libs.plugins.ksp)\n}")
        assertThat(rendered).endsWith(
            """    testImplementation(libs.kotlin.testJunit)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.content.negotiation)
    ksp(libs.room.compiler)
}""",
        )
        assertThat(KotlinDslPlanRenderer().render(rendered, operations)).isEqualTo(rendered)
        assertThat(PsiTreeUtil.findChildOfType(KtPsiFactory(project).createFile("build.gradle.kts", rendered), PsiErrorElement::class.java)).isNull()
    }

    fun `test Kotlin JVM module without a dependencies block gets one`() {
        val rendered = KotlinDslPlanRenderer().render(
            "plugins {\n    kotlin(\"jvm\")\n}\n",
            listOf(GradleOperation.AddDependency("main", "implementation", "logback", false)),
        )

        assertThat(rendered).isEqualTo("plugins {\n    kotlin(\"jvm\")\n}\n\ndependencies {\n    implementation(libs.logback)\n}\n")
    }

    fun `test existing source set dependency with spacing is not added twice`() {
        val original = "kotlin {\n    sourceSets {\n        commonMain.dependencies {\n            implementation( libs.ktor.client.core )\n        }\n    }\n}\n"

        val rendered = KotlinDslPlanRenderer().render(
            original,
            listOf(GradleOperation.AddDependency("commonMain", "implementation", "ktor-client-core", false)),
        )

        assertThat(rendered).isEqualTo(original)
    }

    private fun fixture(name: String): String = Files.readString(Paths.get(testDataPath, "editing", name))
}
