package com.kmpdependencyresolver.plugin.project

import com.intellij.psi.util.PsiTreeUtil
import com.kmpdependencyresolver.core.model.SourceSetNode
import com.kmpdependencyresolver.core.model.TargetFamily
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

data class KotlinDslInspection(
    val targets: Set<TargetFamily>,
    val sourceSets: List<SourceSetNode>,
    val pluginAliases: Set<String>,
    val configurations: Set<String>,
    val copyOnlyReasons: List<String>,
    /** Kotlin target names compiled by each main source set, e.g. `commonMain` -> `[android, iosArm64]`. */
    val sourceSetTargetNames: Map<String, List<String>> = emptyMap(),
)

/**
 * Reads the Kotlin Multiplatform shape of a `build.gradle.kts` without executing it.
 *
 * Only target calls inside the top-level `kotlin { }` block count, so the AGP `android { }` extension
 * is not mistaken for an Android target. When the build declares no manual `dependsOn` edges, the
 * Kotlin default hierarchy template is modelled (`iosMain`, `appleMain`, `nativeMain`, …); intermediate
 * source sets cover the union of their children's targets.
 */
class KotlinDslSourceSetInspector {
    fun inspect(file: KtFile): KotlinDslInspection {
        val text = file.text
        val kotlinLambdas = PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)
            .filter { it.calleeExpression?.text == "kotlin" && lambdaOf(it) != null }
            .filter { call -> PsiTreeUtil.getParentOfType(call, KtCallExpression::class.java)?.let { !isKotlinBlock(it) } ?: true }
            .mapNotNull(::lambdaOf)
        val sourceSetsLambdas = kotlinLambdas.flatMap { lambda ->
            PsiTreeUtil.findChildrenOfType(lambda, KtCallExpression::class.java)
                .filter { it.calleeExpression?.text == "sourceSets" }
                .mapNotNull(::lambdaOf)
        }
        val declaredTargets = kotlinLambdas.flatMap { lambda ->
            PsiTreeUtil.findChildrenOfType(lambda, KtCallExpression::class.java)
                .filter { call -> sourceSetsLambdas.none { PsiTreeUtil.isAncestor(it, call, false) } }
                .filter { call -> (call.parent as? KtQualifiedExpression)?.selectorExpression != call }
                .mapNotNull(::declaredTarget)
        }.distinctBy { it.name }
        val targets = declaredTargets.map { it.family }.toSet()
        val sourceSetText = sourceSetsLambdas.joinToString("\n") { it.text }

        val explicitEdges = linkedMapOf<String, MutableSet<String>>()
        (DEPENDS_ON.findAll(sourceSetText) + VAL_DEPENDS_ON.findAll(sourceSetText)).forEach { match ->
            explicitEdges.getOrPut(match.groupValues[1]) { linkedSetOf() } += match.groupValues[2]
        }
        val useTemplate = targets.isNotEmpty() &&
            (explicitEdges.isEmpty() || "applyDefaultHierarchyTemplate" in text)

        val leafFamilies = linkedMapOf<String, TargetFamily>()
        val edges = linkedMapOf<String, MutableSet<String>>()
        fun edge(child: String, parent: String) { edges.getOrPut(child) { linkedSetOf() } += parent }

        if (targets.isNotEmpty()) edges.getOrPut("commonMain") { linkedSetOf() }
        declaredTargets.forEach { target ->
            val main = "${target.name}Main"
            leafFamilies[main] = target.family
            val parent = if (useTemplate) TEMPLATE_GROUP[target.family]?.let { "${it}Main" } ?: "commonMain" else null
            if (parent != null) edge(main, parent)
        }
        if (useTemplate) {
            TEMPLATE_GROUP.values.toSet().forEach { group ->
                if (edges.keys.none { child -> edges[child]?.contains("${group}Main") == true }) return@forEach
                var current = group
                while (true) {
                    val parent = TEMPLATE_PARENT[current] ?: break
                    edge("${current}Main", "${parent}Main")
                    current = parent
                }
                edge("${current}Main", "commonMain")
            }
        }
        explicitEdges.forEach { (child, parents) -> parents.forEach { edge(child, it) } }
        SOURCE_SET.findAll(sourceSetText).map { it.groupValues[1] }.forEach { edges.getOrPut(it) { linkedSetOf() } }
        if ("commonMain" in edges) {
            edges.forEach { (name, parents) -> if (name != "commonMain" && parents.isEmpty()) parents += "commonMain" }
        }

        val families = linkedMapOf<String, MutableSet<TargetFamily>>()
        fun propagate(name: String, family: TargetFamily, visited: MutableSet<String> = linkedSetOf()) {
            if (!visited.add(name)) return
            families.getOrPut(name) { linkedSetOf() } += family
            edges[name].orEmpty().forEach { propagate(it, family, visited) }
        }
        edges.keys.forEach { name -> (leafFamilies[name] ?: familyByPrefix(name))?.let { propagate(name, it) } }
        if ("commonMain" in edges) families.getOrPut("commonMain") { linkedSetOf() } += targets

        val targetNames = linkedMapOf<String, MutableSet<String>>()
        fun propagateName(name: String, target: String, visited: MutableSet<String> = linkedSetOf()) {
            if (!visited.add(name)) return
            targetNames.getOrPut(name) { sortedSetOf() } += target
            edges[name].orEmpty().forEach { propagateName(it, target, visited) }
        }
        declaredTargets.forEach { propagateName("${it.name}Main", it.name) }

        val mainSourceSets = edges.keys.map { name -> SourceSetNode(name, families[name].orEmpty(), edges[name].orEmpty()) }
        val testSourceSets = if (targets.isEmpty()) emptyList() else mainSourceSets.mapNotNull { main ->
            val test = testName(main.name, declaredTargets) ?: return@mapNotNull null
            SourceSetNode(test, main.descendantTargets, main.dependsOn.mapNotNull { testName(it, declaredTargets) }.toSet())
        }
        val sourceSets = (mainSourceSets + testSourceSets).distinctBy { it.name }.sortedBy { it.name }

        val plugins = PLUGIN_ALIAS.findAll(text).map { it.groupValues[1].replace('.', '-') }.toSet()
        val usedConfigurations = CONFIGURATION.findAll(text).map { it.groupValues[1] }.toSet()
        val configurations = if (targets.isEmpty()) usedConfigurations else usedConfigurations + KMP_CONFIGURATIONS
        val reasons = buildList {
            if (targets.isEmpty()) add("No Kotlin Multiplatform targets are declared in this module's kotlin { } block.")
            if (DYNAMIC_SOURCE_SET.containsMatchIn(sourceSetText)) add("Dynamic source-set construction cannot be mapped confidently.")
        }
        return KotlinDslInspection(targets, sourceSets, plugins, configurations, reasons, targetNames.mapValues { it.value.toList() })
    }

    private data class DeclaredTarget(val name: String, val family: TargetFamily)

    private fun declaredTarget(call: KtCallExpression): DeclaredTarget? {
        val preset = call.calleeExpression?.text ?: return null
        val family = TARGET_PRESETS[preset] ?: return null
        val customName = (call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression)
            ?.takeUnless { it.hasInterpolation() }
            ?.entries?.joinToString("") { it.text }
            ?.takeIf { it.isNotBlank() }
        val name = customName ?: if (family == TargetFamily.ANDROID) "android" else preset
        return DeclaredTarget(name, family)
    }

    private fun testName(main: String, targets: List<DeclaredTarget>): String? {
        if (!main.endsWith("Main")) return null
        val prefix = main.removeSuffix("Main")
        // Android test source-set names differ between androidTarget() and the AGP KMP library plugin.
        if (targets.any { it.family == TargetFamily.ANDROID && it.name == prefix }) return null
        return "${prefix}Test"
    }

    private fun isKotlinBlock(call: KtCallExpression) = call.calleeExpression?.text == "kotlin" && lambdaOf(call) != null

    private fun lambdaOf(call: KtCallExpression): KtLambdaExpression? =
        call.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: call.valueArguments.lastOrNull()?.getArgumentExpression() as? KtLambdaExpression

    private fun familyByPrefix(name: String): TargetFamily? = PREFIX_FAMILIES.entries.firstOrNull { name.startsWith(it.key) }?.value

    private companion object {
        val TARGET_PRESETS = mapOf(
            "androidTarget" to TargetFamily.ANDROID, "android" to TargetFamily.ANDROID, "androidLibrary" to TargetFamily.ANDROID,
            "jvm" to TargetFamily.JVM,
            "ios" to TargetFamily.IOS, "iosArm64" to TargetFamily.IOS, "iosX64" to TargetFamily.IOS, "iosSimulatorArm64" to TargetFamily.IOS,
            "macosArm64" to TargetFamily.MACOS, "macosX64" to TargetFamily.MACOS,
            "linuxArm64" to TargetFamily.LINUX, "linuxX64" to TargetFamily.LINUX,
            "mingwX64" to TargetFamily.MINGW,
            "js" to TargetFamily.JS,
            "wasmJs" to TargetFamily.WASM, "wasmWasi" to TargetFamily.WASM,
        )

        /** Default hierarchy template: the group each target family joins, and each group's parent. */
        val TEMPLATE_GROUP = mapOf(
            TargetFamily.IOS to "ios", TargetFamily.MACOS to "macos", TargetFamily.LINUX to "linux", TargetFamily.MINGW to "mingw",
        )
        val TEMPLATE_PARENT = mapOf("ios" to "apple", "macos" to "apple", "apple" to "native", "linux" to "native", "mingw" to "native")

        val PREFIX_FAMILIES = linkedMapOf(
            "android" to TargetFamily.ANDROID, "jvm" to TargetFamily.JVM, "ios" to TargetFamily.IOS, "macos" to TargetFamily.MACOS,
            "linux" to TargetFamily.LINUX, "mingw" to TargetFamily.MINGW, "js" to TargetFamily.JS, "wasm" to TargetFamily.WASM,
        )
        val KMP_CONFIGURATIONS = setOf("implementation", "api", "compileOnly", "runtimeOnly")

        val SOURCE_SET = Regex("\\b([A-Za-z][A-Za-z0-9]*Main)\\b")
        val DEPENDS_ON = Regex("([A-Za-z][A-Za-z0-9]*Main)(?:\\.get\\(\\))?\\.dependsOn\\(\\s*([A-Za-z][A-Za-z0-9]*Main)(?:\\.get\\(\\))?\\s*\\)")
        val VAL_DEPENDS_ON = Regex("val\\s+([A-Za-z][A-Za-z0-9]*Main)\\s+by\\s+(?:creating|getting)\\s*\\{[^}]*dependsOn\\(\\s*([A-Za-z][A-Za-z0-9]*Main)(?:\\.get\\(\\))?\\s*\\)")
        val PLUGIN_ALIAS = Regex("alias\\(libs\\.plugins\\.([A-Za-z0-9_.]+)\\)")
        val CONFIGURATION = Regex("\\b(implementation|api|compileOnly|runtimeOnly)\\s*\\(")
        val DYNAMIC_SOURCE_SET = Regex("\\b(forEach|for\\s*\\()")
    }
}
