# KMP Dependency Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Marketplace-ready Android Studio and IntelliJ IDEA plugin that searches KMP and platform-specific dependencies, recommends a source set with evidence, copies dependency declarations, and safely previews and applies Kotlin DSL plus standard version-catalog edits.

**Architecture:** A pure Kotlin `core` module owns normalized metadata, compatibility, ranking, recipes, and structured change plans. A JVM `providers` module implements client-only klibs.io MCP, Maven Central, Google Maven, metadata, cache, and provider isolation behind injected transports. The `plugin` module adapts public JetBrains project/PSI APIs, renders the Swing tool window, and applies validated plans as one undoable write command.

**Tech Stack:** Kotlin/JVM 2.1.20, Java 21 bytecode, Gradle 9.5.1, IntelliJ Platform Gradle Plugin 2.18.1, IntelliJ Platform 2025.2.5 compile target, Java `HttpClient`, kotlinx.serialization 1.9.0, JUnit 5.12.2, AssertJ 3.27.3, MockWebServer 5.1.0, JetBrains Platform Test Framework and Plugin Verifier.

**Spec:** `docs/superpowers/specs/2026-09-12-kmp-dependency-resolver-design.md`

## Global Constraints

- The MVP supports Android Studio and IntelliJ IDEA on platform builds `252` through `262.*`; release CI verifies IC 2025.2.5, IC 2026.1.3, IC 2026.2.0.1, Android Studio 2025.2.3.9, and Android Studio 2025.3.1.6.
- The MVP edits only Kotlin DSL projects using the standard `gradle/libs.versions.toml` catalog.
- Search includes genuine multiplatform, partial-coverage, Android-only, JVM-only, iOS, JS, Wasm, and other platform-specific libraries.
- Add supports libraries, BOMs, and required companion plugins/processors; independent Gradle-plugin search is excluded.
- Every Add operation recommends and confirms a source set, renders a full diff, validates both resulting files, and applies through one native Undo command.
- Copy supports raw coordinates, Kotlin DSL, version-catalog entries, and a complete recipe without modifying files.
- The plugin is client-only, unauthenticated, HTTPS-only, and never transmits project names, project files, source code, or installed dependency lists.
- Compatibility labels are exactly `VERIFIED`, `CURATED`, `INFERRED`, `UNKNOWN`, and `INCOMPATIBLE`; the UI never says “guaranteed compatible.”
- Provider responses and remote recipes are untrusted, size-limited, timeout-limited, schema-validated data and cannot directly produce file writes.
- No automatic Gradle sync, Groovy support, custom catalogs, vulnerability scanning, or dependency upgrades are included in the MVP.
- The public repository and recipe namespace is `https://github.com/itsSiddharthGupta/KmpDependencyResolver`; the fixed manifest URL is `https://raw.githubusercontent.com/itsSiddharthGupta/KmpDependencyResolver/main/registry/manifest.json`.

## Planned File Structure

```text
KmpDependencyResolver/
├── build.gradle.kts                         # shared Kotlin/test conventions
├── settings.gradle.kts                      # repositories and three modules
├── gradle.properties                        # platform/build compatibility pins
├── gradle/libs.versions.toml                # build dependencies
├── core/
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/kmpdependencyresolver/core/
│       ├── model/                           # coordinates, targets, evidence, candidates
│       ├── compatibility/                   # source-set graph and recommendation
│       ├── search/                          # provider contract, merge, rank
│       ├── recipe/                          # declarative recipes and validation
│       └── change/                          # catalog/Gradle operations and planner
├── providers/
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/kmpdependencyresolver/providers/
│       ├── http/                            # bounded HTTPS transport
│       ├── cache/                           # local JSON response cache
│       ├── maven/                           # Central, Google, module metadata
│       ├── klibs/                           # stateless MCP client/provider
│       └── ProviderSearchCoordinator.kt
├── plugin/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/kmpdependencyresolver/plugin/
│       │   ├── project/                     # Gradle/KMP model adapter
│       │   ├── editing/                     # TOML/Kotlin PSI edits and atomic apply
│       │   ├── ui/                          # tool window, search, confirmation, diff
│       │   ├── settings/                    # endpoint/privacy settings
│       │   └── DependencyResolverService.kt
│       ├── main/resources/META-INF/plugin.xml
│       ├── main/resources/recipes/bundled-recipes.json
│       └── test/{kotlin,testData}/           # platform fixtures and light tests
├── docs/network-and-privacy.md
└── .github/workflows/verify.yml
```

---

### Task 1: Buildable Plugin Skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `providers/build.gradle.kts`
- Create: `plugin/build.gradle.kts`
- Create: `plugin/src/main/resources/META-INF/plugin.xml`
- Create: `core/src/test/kotlin/com/kmpdependencyresolver/core/BuildSmokeTest.kt`
- Create: `.gitignore`
- Create: Gradle wrapper files with Gradle 9.5.1

**Interfaces:**
- Produces: three Gradle modules `:core`, `:providers`, `:plugin`; plugin ID `com.kmpdependencyresolver`; test command `./gradlew test`.

- [x] **Step 1: Write the build smoke test**

```kotlin
package com.kmpdependencyresolver.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class BuildSmokeTest {
    @Test fun `test runtime uses Java 21 or newer`() {
        assertEquals(true, Runtime.version().feature() >= 21)
    }
}
```

- [x] **Step 2: Create the version catalog and module settings**

Define versions `kotlin=2.1.20`, `intellijPlatform=2.18.1`, `serialization=1.9.0`, `junit=5.12.2`, `assertj=3.27.3`, and `mockWebServer=5.1.0`. Include `:core`, `:providers`, and `:plugin`; use Maven Central, Gradle Plugin Portal, and `intellijPlatform.defaultRepositories()`.

- [x] **Step 3: Configure the modules**

Apply Kotlin/JVM to all modules, Kotlin serialization to `providers`, and `org.jetbrains.intellij.platform` only to `plugin`. Set JVM toolchain and Kotlin bytecode target to 21. Make `providers` depend on `core`; make `plugin` depend on both. Configure the plugin against IntelliJ IDEA Community 2025.2.5 with bundled `com.intellij.java`, `com.intellij.gradle`, `org.jetbrains.kotlin`, and `org.toml.lang` plugins plus the Platform test framework.

- [x] **Step 4: Declare the plugin descriptor**

```xml
<idea-plugin>
  <id>com.kmpdependencyresolver</id>
  <name>KMP Dependency Resolver</name>
  <vendor>Sid</vendor>
  <description>Discover and safely add Kotlin Multiplatform dependencies.</description>
  <idea-version since-build="252" until-build="262.*"/>
  <depends>com.intellij.modules.platform</depends>
  <depends>com.intellij.modules.java</depends>
  <depends>com.intellij.gradle</depends>
  <depends>org.jetbrains.kotlin</depends>
  <depends optional="true">org.toml.lang</depends>
</idea-plugin>
```

- [x] **Step 5: Generate the wrapper and prove the skeleton**

Run: `gradle wrapper --gradle-version 9.5.1`

Run: `./gradlew clean test verifyPluginProjectConfiguration buildPlugin`

Expected: smoke test passes, configuration verification passes, and `plugin/build/distributions/KMP-Dependency-Resolver-0.1.0.zip` exists.

- [x] **Step 6: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle core providers plugin gradlew gradlew.bat
git commit -m "build: scaffold dependency resolver plugin"
```

---

### Task 2: Domain Model and Source-Set Recommendation

**Files:**
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/model/DependencyModels.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/model/ProjectModels.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/compatibility/SourceSetRecommender.kt`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/compatibility/SourceSetRecommenderTest.kt`

**Interfaces:**
- Produces: `Coordinates`, `TargetFamily`, `EvidenceKind`, `Candidate`, `SourceSetNode`, `ModuleModel`, `PlacementRecommendation`, and `SourceSetRecommender.recommend(candidate, module)`.

- [ ] **Step 1: Write failing placement tests**

Cover: all targets → `commonMain`; Android only → `androidMain`; iOS device and simulator → `iosMain`; Android+JVM partial coverage → a matching custom intermediate source set; unknown coverage → warning; no covering node → incompatible.

```kotlin
@Test fun `common artifact is placed in commonMain`() {
    val result = recommender.recommend(candidate(ANDROID, IOS, JVM), moduleFixture())
    assertThat(result.sourceSetName).isEqualTo("commonMain")
    assertThat(result.evidence).isEqualTo(EvidenceKind.VERIFIED)
}
```

- [ ] **Step 2: Run tests and confirm the missing types failure**

Run: `./gradlew :core:test --tests '*SourceSetRecommenderTest'`

Expected: compilation fails because the domain types do not exist.

- [ ] **Step 3: Implement immutable domain types**

```kotlin
data class Coordinates(val group: String, val artifact: String) {
    val notation: String get() = "$group:$artifact"
}

enum class TargetFamily { ANDROID, JVM, IOS, MACOS, LINUX, MINGW, JS, WASM }
enum class EvidenceKind { VERIFIED, CURATED, INFERRED, UNKNOWN, INCOMPATIBLE }

data class SourceSetNode(
    val name: String,
    val descendantTargets: Set<TargetFamily>,
    val dependsOn: Set<String> = emptySet(),
)

data class PlacementRecommendation(
    val sourceSetName: String?,
    val evidence: EvidenceKind,
    val explanation: String,
    val requiresOverride: Boolean,
)
```

- [ ] **Step 4: Implement deterministic recommendation**

Filter source sets whose descendant targets are nonempty and fully contained in candidate coverage. Choose the valid node with the largest descendant-target count, then shortest distance from `commonMain`, then lexicographic name. Preserve `UNKNOWN`; return `INCOMPATIBLE` when verified coverage has no valid node.

- [ ] **Step 5: Run core tests**

Run: `./gradlew :core:test`

Expected: all placement tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat: recommend KMP dependency source sets"
```

---

### Task 3: Search Contracts, Deduplication, and Ranking

**Files:**
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/search/SearchProvider.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/search/CandidateMerger.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/search/CandidateRanker.kt`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/search/CandidateMergerTest.kt`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/search/CandidateRankerTest.kt`

**Interfaces:**
- Consumes: `Candidate`, `Coordinates`, `TargetFamily`, `EvidenceKind` from Task 2.
- Produces: `SearchRequest`, `ProviderResult`, `SearchProvider.search(request)`, `CandidateMerger.merge(results)`, and `CandidateRanker.rank(query, module, candidates)`.

- [ ] **Step 1: Write failing merge and ranking tests**

Assert that identical coordinates from klibs and Central merge into one candidate, union provenance, keep the newest stable version, and prefer `VERIFIED` over `CURATED`. Assert ranking order: exact artifact match, compatible stable candidate, compatible preview, unknown, incompatible.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :core:test --tests '*Candidate*Test'`

Expected: compilation fails on missing search contracts.

- [ ] **Step 3: Implement provider contracts**

```kotlin
data class SearchRequest(
    val query: String,
    val requiredTargets: Set<TargetFamily>,
    val includePreRelease: Boolean = false,
)

data class ProviderFailure(val providerId: String, val message: String)
data class ProviderResult(
    val providerId: String,
    val candidates: List<Candidate>,
    val failure: ProviderFailure? = null,
    val retrievedAtEpochMillis: Long,
    val fromCache: Boolean,
)

fun interface SearchProvider {
    fun search(request: SearchRequest): ProviderResult
}
```

- [ ] **Step 4: Implement merge and stable ranking keys**

Use `Coordinates` as the deduplication key. Merge only facts with provenance, order semantic versions through a tested tolerant comparator, and score evidence as verified 4, curated 3, inferred 2, unknown 1, incompatible 0. Keep deterministic coordinate ordering as the final tie-breaker.

- [ ] **Step 5: Run tests**

Run: `./gradlew :core:test`

Expected: all tests pass and repeated shuffled inputs yield identical output ordering.

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat: merge and rank dependency search results"
```

---

### Task 4: Declarative Recipe Registry

**Files:**
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/recipe/RecipeModels.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/recipe/RecipeValidator.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/recipe/JsonRecipeRegistry.kt`
- Create: `plugin/src/main/resources/recipes/bundled-recipes.json`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/recipe/RecipeValidatorTest.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/recipe/JsonRecipeRegistryTest.kt`

**Interfaces:**
- Produces: `DependencyRecipe`, `CompanionPlugin`, `ProcessorRequirement`, `BomRule`, `RecipeRegistry.find(coordinates)`, and `RecipeValidator.validate(recipe)`.

- [ ] **Step 1: Write failing schema and safety tests**

Test valid recipes for Kotlin serialization, Room+KSP, and Compose BOM. Reject unknown operation kinds, non-HTTPS documentation URLs, absolute paths, raw Gradle script text, duplicate recipe IDs, payloads over 1 MiB, and unsupported schema versions.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :core:test :providers:test --tests '*Recipe*Test'`

Expected: compilation fails on missing recipe types.

- [ ] **Step 3: Implement the closed recipe model**

```kotlin
data class DependencyRecipe(
    val id: String,
    val coordinate: Coordinates,
    val preferredConfiguration: String,
    val bom: BomRule? = null,
    val companionPlugins: List<CompanionPlugin> = emptyList(),
    val processors: List<ProcessorRequirement> = emptyList(),
    val constraints: List<VersionConstraint> = emptyList(),
    val documentationUrl: String,
)

interface RecipeRegistry {
    fun find(coordinates: Coordinates): List<DependencyRecipe>
}
```

- [ ] **Step 4: Implement strict JSON loading and bundled recipes**

Use kotlinx.serialization with `ignoreUnknownKeys=false`; read at most 1 MiB before decoding. Add reviewed bundled recipes for Kotlin serialization, Room with KSP, SQLDelight, Compose BOM, Ktor, and Coil. Every recipe includes an HTTPS source URL and only structured operations.

- [ ] **Step 5: Run tests**

Run: `./gradlew :core:test :providers:test`

Expected: valid recipes load; every unsafe fixture is rejected with a specific validation code.

- [ ] **Step 6: Commit**

```bash
git add core/src providers/src plugin/src/main/resources/recipes
git commit -m "feat: add safe dependency recipe registry"
```

---

### Task 5: Bounded HTTP Transport and Local Cache

**Files:**
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/http/HttpTransport.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/http/JdkHttpTransport.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/cache/SearchCache.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/cache/FileSearchCache.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/http/JdkHttpTransportTest.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/cache/FileSearchCacheTest.kt`

**Interfaces:**
- Produces: `HttpTransport.execute(HttpRequestSpec): HttpPayload`, `SearchCache.get(key, now)`, and `SearchCache.put(entry)`.

- [ ] **Step 1: Write failing transport security tests**

Using MockWebServer, verify 5-second connect and 10-second request timeouts, 2 MiB response limit, gzip handling, cancellation, user agent, and rejection of HTTP URLs, cross-host redirects, and non-JSON content types where JSON is required.

- [ ] **Step 2: Write failing cache tests**

Verify provider/query/target filters form the key; fresh and stale entries are distinguished; corrupt files are ignored; writes replace atomically; a 50 MiB LRU ceiling evicts oldest entries.

- [ ] **Step 3: Implement the transport contract**

```kotlin
data class HttpRequestSpec(
    val uri: URI,
    val method: String = "GET",
    val body: ByteArray? = null,
    val expectedContentTypes: Set<String>,
    val maxBytes: Int = 2 * 1024 * 1024,
)

data class HttpPayload(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)
interface HttpTransport { fun execute(request: HttpRequestSpec): HttpPayload }
```

- [ ] **Step 4: Implement the cache under an injected directory**

Store one schema-versioned JSON envelope per SHA-256 key. Write to a sibling temporary file and atomically move it over the target. Keep provider TTL in the entry and expose stale data without silently labeling it fresh.

- [ ] **Step 5: Run provider tests**

Run: `./gradlew :providers:test`

Expected: all transport and cache tests pass without external network access.

- [ ] **Step 6: Commit**

```bash
git add providers/src
git commit -m "feat: add secure HTTP transport and search cache"
```

---

### Task 6: Maven Central, Google Maven, and Publication Metadata

**Files:**
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/maven/MavenCentralProvider.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/maven/GoogleMavenProvider.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/maven/GradleModuleMetadataParser.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/maven/KotlinToolingMetadataParser.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/maven/MavenCentralProviderTest.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/maven/GoogleMavenProviderTest.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/maven/PublicationMetadataTest.kt`
- Create: recorded fixtures under `providers/src/test/resources/maven/`

**Interfaces:**
- Consumes: `HttpTransport`, `SearchCache`, `SearchProvider`, and domain candidates.
- Produces: `PublicationEvidenceReader.read(coordinates, version)` and two provider implementations.

- [ ] **Step 1: Record sanitized contract fixtures**

Include Central search plus `.module` and tooling metadata for kotlinx.serialization, Ktor, AndroidX lifecycle, Coil, an Android-only artifact, and a deliberately malformed publication. Include Google `master-index.xml`, group index, and `maven-metadata.xml` fixtures.

- [ ] **Step 2: Write failing provider and metadata tests**

Assert exact query URL encoding, stable/pre-release classification, canonical root publication mapping, target-family extraction, provider provenance, 24-hour search TTL, and graceful candidate-level failure for malformed metadata.

- [ ] **Step 3: Implement Central and Google discovery**

Central uses `https://search.maven.org/solrsearch/select` and `https://repo1.maven.org/maven2/`. Google uses `https://dl.google.com/dl/android/maven2/master-index.xml`, group indexes, and artifact metadata. Both return normalized candidates without guessing target coverage.

- [ ] **Step 4: Implement evidence extraction**

Map Gradle attributes and Kotlin target names to `TargetFamily`; map `iosArm64`, `iosX64`, and `iosSimulatorArm64` to IOS while retaining raw names in provenance details. Prefer `.module`, supplement with `-kotlin-tooling-metadata.json`, and label unsupported or contradictory payloads `UNKNOWN` rather than verified.

- [ ] **Step 5: Run tests**

Run: `./gradlew :providers:test --tests '*maven*'`

Expected: fixture-only tests pass and malformed metadata does not fail the provider result.

- [ ] **Step 6: Commit**

```bash
git add providers/src
git commit -m "feat: search Maven repositories and verify KMP targets"
```

---

### Task 7: klibs.io MCP Provider and Provider Isolation

**Files:**
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/klibs/McpClient.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/klibs/KlibsProvider.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/ProviderSearchCoordinator.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/klibs/KlibsProviderTest.kt`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/ProviderSearchCoordinatorTest.kt`
- Create: recorded fixtures under `providers/src/test/resources/klibs/`

**Interfaces:**
- Consumes: Task 3 search contracts and Task 5 transport/cache.
- Produces: `McpClient.callTool(name, arguments)`, `KlibsProvider`, and `ProviderSearchCoordinator.search(request): AggregatedSearchResult`.

- [ ] **Step 1: Capture MCP fixtures**

Record stateless Streamable HTTP responses for `initialize`, `tools/list`, `searchProjects`, and `getLatestVersion` from `https://api.klibs.io/mcp`. Sanitize request IDs and retain protocol/content-type headers.

- [ ] **Step 2: Write failing MCP and isolation tests**

Verify protocol initialization, tool capability lookup rather than assumed positional fields, search target filters, mapping of package coordinates and supported targets, 6-hour cache TTL, and useful results when klibs, Central, or Google throws or times out.

- [ ] **Step 3: Implement the minimal stateless MCP client**

Send JSON-RPC 2.0 requests over HTTPS Streamable HTTP with `Accept: application/json, text/event-stream`; support JSON and single-result SSE payloads; reject server requests, notifications requiring action, payloads over 2 MiB, and unexpected tool result schemas.

- [ ] **Step 4: Implement klibs mapping and aggregation**

Use `searchProjects` for discovery and `getLatestVersion` only when search lacks version data. Merge all completed providers through `CandidateMerger`; return a failure entry per failed provider. Execute providers on an injected bounded executor and cancel outstanding work when the caller cancels.

- [ ] **Step 5: Run tests**

Run: `./gradlew :providers:test`

Expected: all provider tests pass offline; failure of each provider in turn still returns candidates from the others.

- [ ] **Step 6: Commit**

```bash
git add providers/src
git commit -m "feat: search klibs and isolate provider failures"
```

---

### Task 8: JetBrains Project and KMP Model Adapter

**Files:**
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/project/ProjectModelReader.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/project/JetBrainsProjectModelReader.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/project/KotlinDslSourceSetInspector.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/project/ProjectModelReaderTest.kt`
- Create: `plugin/src/test/testData/projects/hierarchical-kmp/`
- Create: `plugin/src/test/testData/projects/compose-multiplatform/`
- Create: `plugin/src/test/testData/projects/unsupported-groovy/`

**Interfaces:**
- Produces: `ProjectModelReader.read(project): ProjectSnapshot` and `ProjectSnapshot.addCapability(moduleId): AddCapability`.

- [ ] **Step 1: Create minimal project fixtures**

Each supported fixture contains `settings.gradle.kts`, `gradle/libs.versions.toml`, and module `build.gradle.kts`. The hierarchical fixture defines `commonMain`, `iosMain`, `iosArm64Main`, `iosSimulatorArm64Main`, `androidMain`, and `jvmMain`. The unsupported fixture uses `build.gradle` and must be copy-only.

- [ ] **Step 2: Write failing light-platform tests**

Assert module IDs, active build file, standard catalog path, plugin/Kotlin/AGP versions, target families, `dependsOn` edges, existing aliases, configurations, and precise copy-only reasons.

- [ ] **Step 3: Implement public-API project discovery**

Use `ModuleManager`, `ProjectFileIndex`, and public External System/Gradle APIs to locate linked Gradle modules and build files. Do not import `impl`, `internal`, or `@ApiStatus.Internal` classes. Resolve the catalog only at `<root>/gradle/libs.versions.toml`.

- [ ] **Step 4: Implement Kotlin DSL PSI inspection**

Use Kotlin PSI call expressions to identify `kotlin {}`, target calls, `sourceSets {}`, named source sets, `dependsOn(...)`, and dependency blocks. Constant calls such as `iosArm64()` map directly. Also recognize the common literal `listOf(iosArm64(), iosSimulatorArm64()).forEach { ... }` form without executing Gradle code. Other dynamic loops may supplement names from IDE source roots but become copy-only if their hierarchy cannot be established confidently.

- [ ] **Step 5: Run plugin tests and API checks**

Run: `./gradlew :plugin:test verifyPluginProjectConfiguration`

Run: `rg -n '(^|\.)impl\.|ApiStatus\.Internal|IntellijInternalApi' plugin/src/main`

Expected: fixture tests pass and the restricted-API search returns no matches.

- [ ] **Step 6: Commit**

```bash
git add plugin/src
git commit -m "feat: inspect KMP project source-set models"
```

---

### Task 9: Structured Change Planner

**Files:**
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/change/ChangeOperations.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/change/AliasGenerator.kt`
- Create: `core/src/main/kotlin/com/kmpdependencyresolver/core/change/ChangePlanner.kt`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/change/AliasGeneratorTest.kt`
- Test: `core/src/test/kotlin/com/kmpdependencyresolver/core/change/ChangePlannerTest.kt`

**Interfaces:**
- Consumes: candidates, placement recommendations, recipes, and catalog/module snapshots.
- Produces: `ChangePlanner.plan(selection, project): PlanResult`; sealed `CatalogOperation`; sealed `GradleOperation`; `ChangePlan`; `PlanConflict`.

- [ ] **Step 1: Write failing planner tests**

Cover direct library, equivalent alias reuse, shared version reuse, deterministic kebab-case alias, conflicting existing version, alias collision, BOM `platform(...)`, Room library+compiler+KSP plugin, serialization plugin, unsupported configuration, unknown override, and incompatible override.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :core:test --tests '*change*'`

Expected: compilation fails on missing change operations.

- [ ] **Step 3: Implement the closed operation model**

```kotlin
sealed interface CatalogOperation {
    data class PutVersion(val alias: String, val value: String) : CatalogOperation
    data class PutLibrary(val alias: String, val coordinates: Coordinates, val versionAlias: String?) : CatalogOperation
    data class PutPlugin(val alias: String, val pluginId: String, val versionAlias: String) : CatalogOperation
}

sealed interface GradleOperation {
    data class AddDependency(val sourceSet: String, val configuration: String, val alias: String, val platform: Boolean) : GradleOperation
    data class AddPluginAlias(val alias: String, val applyFalse: Boolean) : GradleOperation
}
```

- [ ] **Step 4: Implement equivalence, aliasing, and conflicts**

Normalize catalog aliases to Gradle accessor semantics before comparing. Reuse a library only when group, artifact, and resolved version are equivalent. Never change an existing version as a side effect. Generate aliases from group-independent artifact names, suffix with a minimal stable group token on collision, and return a conflict when ambiguity remains.

- [ ] **Step 5: Run core tests**

Run: `./gradlew :core:test`

Expected: all planner tests pass and plan operation ordering is deterministic.

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat: plan catalog and Kotlin DSL dependency changes"
```

---

### Task 10: Preview Renderers and Atomic PSI Application

**Files:**
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/editing/CatalogPlanRenderer.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/editing/KotlinDslPlanRenderer.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/editing/ChangePreviewService.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/editing/PsiChangeApplicator.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/editing/ChangeRenderingTest.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/editing/PsiChangeApplicatorTest.kt`
- Create: golden files under `plugin/src/test/testData/editing/`

**Interfaces:**
- Consumes: `ChangePlan` from Task 9 and project files from Task 8.
- Produces: `ChangePreviewService.preview(plan, snapshot): ChangePreview` and `PsiChangeApplicator.apply(preview): ApplyResult`.

- [ ] **Step 1: Write golden tests before renderers**

Add before/after files for commonMain, androidMain, custom intermediate source set, BOM, Room+KSP, serialization plugin, preserved comments, sorted and unsorted catalogs, and missing dependency blocks. Assert byte-for-byte expected output and successful TOML/Kotlin PSI parse.

- [ ] **Step 2: Write atomicity and Undo tests**

Assert one Apply updates both files, one Undo restores both, stale document modification stamps reject Apply, read-only files reject before writes, and an invalid rendered file leaves both originals unchanged.

- [ ] **Step 3: Implement structure-preserving rendering**

Locate TOML tables through TOML PSI when the optional plugin is present; otherwise use a tested table-aware parser that preserves untouched ranges. Locate Kotlin insertion anchors through Kotlin PSI, create dependency/plugin expressions with `KtPsiFactory`, and run `CodeStyleManager.reformat` only on inserted nodes.

- [ ] **Step 4: Implement preview validation**

Render both complete proposed texts from structured operations, parse them in nonphysical PSI files, reject syntax errors, store original SHA-256 plus document modification stamps, and generate unified diff hunks for the UI.

- [ ] **Step 5: Implement one undoable application**

Use `WriteCommandAction.writeCommandAction(project).withName("Add KMP dependency").run<RuntimeException> { ... }`. Recheck stamps and hashes inside the command, replace only validated document ranges, commit documents through `PsiDocumentManager`, and return paths changed.

- [ ] **Step 6: Run editing tests**

Run: `./gradlew :plugin:test --tests '*editing*'`

Expected: all golden, validation, atomicity, stale-state, and Undo tests pass.

- [ ] **Step 7: Commit**

```bash
git add plugin/src
git commit -m "feat: preview and atomically apply dependency edits"
```

---

### Task 11: Search Tool Window and Copy Actions

**Files:**
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/DependencyResolverService.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/DependencyToolWindowFactory.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/SearchPresenter.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/SearchPanel.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/ResultCellRenderer.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/CopyFormatter.kt`
- Modify: `plugin/src/main/resources/META-INF/plugin.xml`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/ui/SearchPresenterTest.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/ui/CopyFormatterTest.kt`

**Interfaces:**
- Consumes: provider coordinator, ranker, model reader, recommender, and recipes.
- Produces: project service `DependencyResolverService`; `SearchPresenter.onQueryChanged`; `CopyFormatter.format(selection, CopyKind)`.

- [ ] **Step 1: Write presenter state tests**

Use fake providers and a fake executor. Assert 300 ms debounce, cancellation of superseded queries, module default from active editor, target/stability filters, four result groups, deterministic evidence labels, partial provider status, offline/stale labels, and UI state updates on the EDT abstraction.

- [ ] **Step 2: Write exact Copy tests**

Assert these four outputs for a versioned library: `group:artifact:version`; `implementation("group:artifact:version")`; `[versions]` plus `[libraries]` catalog text; and complete recipe text containing catalog, plugin/processor, and source-set declarations.

- [ ] **Step 3: Implement the project service and presenter**

The service constructs providers with endpoint settings and a cache under `PathManager.getSystemPath()/kmp-dependency-resolver/cache`. The presenter owns immutable `SearchUiState`, schedules provider work off the EDT, and publishes only the latest query generation.

- [ ] **Step 4: Implement the Swing tool window**

Use stable Swing/JetBrains components: `SearchTextField`, `ComboBox`, `JBList`, `JBLabel`, and `JBScrollPane`. Add accessible names and keyboard actions: Enter opens confirmation, Ctrl/Cmd+C opens Copy choices, and Escape cancels the active search. Do not depend on experimental UI APIs.

- [ ] **Step 5: Register the tool window**

```xml
<extensions defaultExtensionNs="com.intellij">
  <toolWindow id="KMP Dependencies"
              anchor="right"
              factoryClass="com.kmpdependencyresolver.plugin.ui.DependencyToolWindowFactory"/>
</extensions>
```

- [ ] **Step 6: Run tests and inspect in a sandbox IDE**

Run: `./gradlew :plugin:test`

Run: `./gradlew :plugin:runIde`

Expected: the tool window opens, search is keyboard-accessible, provider failures are nonblocking, and all Copy variants reach the system clipboard exactly as tested.

- [ ] **Step 7: Commit**

```bash
git add plugin/src
git commit -m "feat: add dependency search and copy tool window"
```

---

### Task 12: Add Confirmation, Diff, Apply, and Diagnostics

**Files:**
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/AddDependencyDialog.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/ChangePreviewDialog.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/DiffRequestFactory.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/UserFacingError.kt`
- Modify: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/ui/SearchPresenter.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/ui/AddDependencyFlowTest.kt`

**Interfaces:**
- Consumes: recommendation, recipe, planner, preview service, applicator.
- Produces: end-to-end `SearchPresenter.add(candidateId)` flow and deterministic user-facing error mapping.

- [ ] **Step 1: Write end-to-end flow tests with fakes**

Assert default recommended source set, selectable valid alternatives, configuration/version selection, individual companion items, explicit UNKNOWN warning, explicit INCOMPATIBLE override, complete two-file preview, Apply disabled on conflicts, successful paths notification, sync suggestion, and stale-preview recovery.

- [ ] **Step 2: Implement confirmation state**

Populate source-set choices from compatibility results, not free text. Require a checkbox for UNKNOWN and the phrase `Add despite known incompatibility` for INCOMPATIBLE. Required recipe items cannot be deselected; recommended items can.

- [ ] **Step 3: Implement native diff preview**

Create one diff chain per changed file using IntelliJ diff content factories. Label originals and proposed content, display planner warnings above the diff, and expose Apply only when preview validation succeeds.

- [ ] **Step 4: Wire Apply and Undo behavior**

Call the applicator once, show one success notification listing relative paths, and offer the IDE's Gradle refresh action as a link without invoking it. On stale preview, return to confirmation and regenerate from a fresh project snapshot.

- [ ] **Step 5: Map actionable diagnostics**

Define stable error codes for unsupported project, ambiguous source sets, catalog conflict, version conflict, read-only file, invalid proposed syntax, stale preview, provider unavailable, cache corrupt, and unexpected failure. User messages omit stack traces; IDE logs include the code and exception but never file content.

- [ ] **Step 6: Run the complete test suite**

Run: `./gradlew test`

Expected: all end-to-end fake flows and prior tests pass.

- [ ] **Step 7: Commit**

```bash
git add plugin/src
git commit -m "feat: confirm preview and apply dependency recipes"
```

---

### Task 13: Endpoint Settings, Remote Recipe Updates, and Privacy

**Files:**
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/settings/ResolverSettings.kt`
- Create: `plugin/src/main/kotlin/com/kmpdependencyresolver/plugin/settings/ResolverConfigurable.kt`
- Create: `providers/src/main/kotlin/com/kmpdependencyresolver/providers/recipe/RemoteRecipeUpdater.kt`
- Create: `docs/network-and-privacy.md`
- Create: `registry/manifest.json`
- Create: `registry/catalog-v1.json`
- Modify: `plugin/src/main/resources/META-INF/plugin.xml`
- Test: `providers/src/test/kotlin/com/kmpdependencyresolver/providers/recipe/RemoteRecipeUpdaterTest.kt`
- Test: `plugin/src/test/kotlin/com/kmpdependencyresolver/plugin/settings/ResolverSettingsTest.kt`

**Interfaces:**
- Produces: persisted provider enablement, fixed endpoint display, offline mode, cache clear action, and `RemoteRecipeUpdater.update(manifestUri)`.

- [ ] **Step 1: Write settings and updater tests**

Assert defaults enable klibs/Central/Google and remote recipes; offline mode performs no HTTP calls; disabling a provider removes it from the coordinator; cache clear removes only the plugin cache; remote updates require HTTPS, schema match, declared byte length, and SHA-256 match; invalid updates preserve the bundled/last-good catalog.

- [ ] **Step 2: Implement persistent settings**

Use `PersistentStateComponent<ResolverSettings.State>`. Display endpoint hostnames as read-only values; expose provider toggles, offline mode, preview-release preference, remote recipe toggle, and Clear Cache. Do not accept arbitrary endpoint URLs in the MVP.

- [ ] **Step 3: Implement signed-by-manifest catalog integrity**

The bundled manifest contains schema version, a catalog URL restricted to `https://github.com/itsSiddharthGupta/KmpDependencyResolver/releases/download/`, maximum byte length, and SHA-256 digest. Fetch the manifest only from `https://raw.githubusercontent.com/itsSiddharthGupta/KmpDependencyResolver/main/registry/manifest.json`, validate it against a bundled Ed25519 public-key signature, then fetch and hash the catalog before atomic cache replacement. On any failure, retain the last valid catalog and report `CURATED_CATALOG_STALE` nonblockingly. Commit the human-readable `registry/catalog-v1.json`; release CI signs it and uploads the immutable asset referenced by the manifest.

- [ ] **Step 4: Write privacy documentation**

Document the exact hosts `api.klibs.io`, `search.maven.org`, `repo1.maven.org`, `dl.google.com`, `plugins.gradle.org`, `raw.githubusercontent.com`, and `github.com`. State that queries/filters go only to enabled search providers; project names, source code, files, and installed dependency lists are not transmitted; all caches are local and removable.

- [ ] **Step 5: Register Settings and run tests**

Run: `./gradlew test`

Expected: offline tests observe zero network calls, invalid catalogs never replace valid data, and settings persist across service recreation.

- [ ] **Step 6: Commit**

```bash
git add providers/src plugin/src docs/network-and-privacy.md registry
git commit -m "feat: add provider privacy and recipe update controls"
```

---

### Task 14: Compatibility Matrix, Marketplace Documentation, and Release Gate

**Files:**
- Create: `.github/workflows/verify.yml`
- Create: `README.md`
- Create: `CHANGELOG.md`
- Create: `docs/marketplace-description.md`
- Create: `plugin/src/main/resources/META-INF/pluginIcon.svg`
- Modify: `plugin/build.gradle.kts`
- Modify: `gradle.properties`
- Modify: `plugin/src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: the complete plugin.
- Produces: reproducible distribution, compatibility report, Marketplace text, and manual smoke-test record.

- [ ] **Step 1: Configure verification and reproducible packaging**

Set `sinceBuild=252`, `untilBuild=262.*`, version `0.1.0`, and Plugin Verifier IDEs IC 2025.2.5, IC 2026.1.3, IC 2026.2.0.1, AI 2025.2.3.9, and AI 2025.3.1.6. Configure `buildPlugin`, `verifyPlugin`, `verifyPluginProjectConfiguration`, and `signPlugin`; signing and publishing read only `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN` environment secrets.

- [ ] **Step 2: Create CI gates**

On pull requests and pushes, run `./gradlew clean test verifyPluginProjectConfiguration buildPlugin verifyPlugin`. Upload test reports, verifier reports, and the unsigned distribution. Add a separate manual `workflow_dispatch` release job that requires all gates, signs, and publishes; ordinary CI cannot publish.

- [ ] **Step 3: Write user and Marketplace documentation**

README sections: supported IDE/project formats, install/run-from-source, search/evidence semantics, Add/Copy workflow, limitations, privacy link, development commands, and issue reporting. Marketplace text must say client-only, list external services, explain UNKNOWN/INCOMPATIBLE overrides, and avoid compatibility guarantees.

- [ ] **Step 4: Run automated release gates**

Run: `./gradlew clean test verifyPluginProjectConfiguration buildPlugin verifyPlugin`

Expected: all tests pass, no compatibility errors for the pinned IDEs, and the distribution ZIP is produced.

- [ ] **Step 5: Perform manual IDE smoke matrix**

For one pinned IntelliJ IDEA and both pinned Android Studio releases: install the ZIP, open the hierarchical KMP fixture, search `ktor`, copy all four formats, preview an Add to `commonMain`, apply, Undo once, confirm both files restore, disable networking, and confirm cached/bundled results remain usable. Record each result with IDE build number in the release notes.

- [ ] **Step 6: Inspect the final diff and restricted APIs**

Run: `git diff main...HEAD --check`

Run: `rg -n '(^|\.)impl\.|ApiStatus\.Internal|IntellijInternalApi|guaranteed compatible' core providers plugin README.md docs`

Expected: no whitespace errors, no restricted API references, and “guaranteed compatible” appears only when explicitly denying such a claim in documentation.

- [ ] **Step 7: Commit**

```bash
git add .github README.md CHANGELOG.md docs plugin gradle.properties
git commit -m "chore: add Marketplace release verification"
```

---

## Final Acceptance Run

- [ ] Run `./gradlew clean test verifyPluginProjectConfiguration buildPlugin verifyPlugin` from a clean checkout.
- [ ] Confirm `git status --short` is empty.
- [ ] Confirm every requirement in `docs/superpowers/specs/2026-09-12-kmp-dependency-resolver-design.md` maps to Tasks 1–14.
- [ ] Confirm the distribution contains no signing keys, tokens, recorded live identifiers, project fixtures with personal paths, or network response data beyond sanitized contract fixtures.
- [ ] Confirm the manual IntelliJ IDEA and Android Studio smoke matrix is recorded for version `0.1.0`.
