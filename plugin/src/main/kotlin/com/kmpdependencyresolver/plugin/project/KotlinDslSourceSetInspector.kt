package com.kmpdependencyresolver.plugin.project

import com.intellij.psi.util.PsiTreeUtil
import com.kmpdependencyresolver.core.model.SourceSetNode
import com.kmpdependencyresolver.core.model.TargetFamily
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

data class KotlinDslInspection(
    val targets: Set<TargetFamily>,
    val sourceSets: List<SourceSetNode>,
    val pluginAliases: Set<String>,
    val configurations: Set<String>,
    val copyOnlyReasons: List<String>,
)

class KotlinDslSourceSetInspector {
    fun inspect(file: KtFile): KotlinDslInspection {
        val text = file.text
        val callNames = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .mapNotNull { it.calleeExpression?.text }.toSet()
        val targets = callNames.mapNotNull(::targetForCall).toSet()
        val names = SOURCE_SET.findAll(text).map { it.groupValues[1] }.toMutableSet()
        if (targets.isNotEmpty()) names += "commonMain"
        targets.forEach { family ->
            when (family) {
                TargetFamily.ANDROID -> names += "androidMain"
                TargetFamily.JVM -> names += "jvmMain"
                TargetFamily.IOS -> if (names.any { it == "iosMain" }) Unit else Unit
                else -> Unit
            }
        }
        val explicitEdges = linkedMapOf<String, MutableSet<String>>()
        DEPENDS_ON.findAll(text).forEach { match ->
            explicitEdges.getOrPut(match.groupValues[1]) { linkedSetOf() } += match.groupValues[2]
        }
        VAL_DEPENDS_ON.findAll(text).forEach { match ->
            explicitEdges.getOrPut(match.groupValues[1]) { linkedSetOf() } += match.groupValues[2]
        }
        if ("commonMain" in names) {
            names.filter { it != "commonMain" && it.endsWith("Main") }.forEach { name ->
                val parent = if (name.startsWith("ios") && name != "iosMain" && "iosMain" in names) "iosMain" else "commonMain"
                explicitEdges.getOrPut(name) { linkedSetOf() }.add(parent)
            }
        }
        val sourceSets = names.sorted().map { name ->
            SourceSetNode(name, descendants(name, targets), explicitEdges[name].orEmpty())
        }
        val plugins = PLUGIN_ALIAS.findAll(text).map { it.groupValues[1].replace('.', '-') }.toSet()
        val configurations = CONFIGURATION.findAll(text).map { it.groupValues[1] }.toSet()
        val reasons = if (DYNAMIC_SOURCE_SET.containsMatchIn(text))
            listOf("Dynamic source-set construction cannot be mapped confidently.") else emptyList()
        return KotlinDslInspection(targets, sourceSets, plugins, configurations, reasons)
    }

    private fun targetForCall(call: String): TargetFamily? = when (call) {
        "androidTarget", "android" -> TargetFamily.ANDROID
        "jvm" -> TargetFamily.JVM
        "ios", "iosArm64", "iosX64", "iosSimulatorArm64" -> TargetFamily.IOS
        "macosArm64", "macosX64" -> TargetFamily.MACOS
        "linuxArm64", "linuxX64" -> TargetFamily.LINUX
        "mingwX64" -> TargetFamily.MINGW
        "js" -> TargetFamily.JS
        "wasmJs", "wasmWasi" -> TargetFamily.WASM
        else -> null
    }

    private fun descendants(name: String, all: Set<TargetFamily>): Set<TargetFamily> = when {
        name == "commonMain" -> all
        name.startsWith("android") -> setOf(TargetFamily.ANDROID)
        name.startsWith("jvm") -> setOf(TargetFamily.JVM)
        name.startsWith("ios") -> setOf(TargetFamily.IOS)
        name.startsWith("macos") -> setOf(TargetFamily.MACOS)
        name.startsWith("linux") -> setOf(TargetFamily.LINUX)
        name.startsWith("mingw") -> setOf(TargetFamily.MINGW)
        name.startsWith("js") -> setOf(TargetFamily.JS)
        name.startsWith("wasm") -> setOf(TargetFamily.WASM)
        else -> emptySet()
    }

    private companion object {
        val SOURCE_SET = Regex("\\b([A-Za-z][A-Za-z0-9]*Main)\\b")
        val DEPENDS_ON = Regex("([A-Za-z][A-Za-z0-9]*Main)(?:\\.get\\(\\))?\\.dependsOn\\(([A-Za-z][A-Za-z0-9]*Main)\\)")
        val VAL_DEPENDS_ON = Regex("val\\s+([A-Za-z][A-Za-z0-9]*Main)\\s+by\\s+(?:creating|getting)\\s*\\{[^}]*dependsOn\\(([A-Za-z][A-Za-z0-9]*Main)\\)", RegexOption.DOT_MATCHES_ALL)
        val PLUGIN_ALIAS = Regex("alias\\(libs\\.plugins\\.([A-Za-z0-9_.]+)\\)")
        val CONFIGURATION = Regex("\\b(implementation|api|compileOnly|runtimeOnly)\\s*\\(")
        val DYNAMIC_SOURCE_SET = Regex("sourceSets\\s*\\{[\\s\\S]*?(forEach|for \\()")
    }
}
