# KMP Dependency Resolver Design

**Date:** 2026-09-12  
**Status:** Approved for implementation planning  
**Working product name:** KMP Dependency Resolver  
**Repository:** `KmpDependencyResolver`  
**Plugin ID:** `com.kmpdependencyresolver`

## Purpose

KMP Dependency Resolver is a client-only JetBrains Marketplace plugin for Android Studio and IntelliJ IDEA. It helps developers discover Kotlin Multiplatform, Compose Multiplatform, and platform-specific libraries; understand which project targets they support; and add them safely to Kotlin DSL projects that use the standard Gradle version catalog at `gradle/libs.versions.toml`.

The plugin does not claim that a dependency is guaranteed compatible. It reports the evidence behind each recommendation and asks the user to confirm the target module, source set, version, configuration, and complete file diff before making changes.

## Goals

- Search genuine multiplatform and platform-specific dependencies from one IDE tool window.
- Recommend the narrowest suitable KMP source set from the project's actual source-set hierarchy.
- Combine curated KMP knowledge with broad repository search.
- Add libraries, BOMs, and required companion plugins or processors as one reviewable recipe.
- Write deterministic, syntactically valid version-catalog and Kotlin DSL changes.
- Provide useful Copy actions without requiring project modification.
- Remain useful when an individual provider or the network is unavailable.
- Support Android Studio and IntelliJ IDEA on the current and immediately previous JetBrains platform generations at each release.

## Non-goals for the MVP

- Groovy Gradle scripts.
- Projects without `gradle/libs.versions.toml`.
- Custom-named or nonstandard version catalogs.
- Arbitrary Gradle script evaluation or rewriting.
- Independent discovery of Gradle plugins unrelated to a selected library.
- Automatic Gradle sync after applying changes.
- Dependency updates, vulnerability scanning, license enforcement, or build migration.
- A hosted proprietary backend.
- Claims of guaranteed source, binary, or runtime compatibility.

Unsupported project layouts remain searchable and copy-capable, but Add is disabled with a precise explanation.

## User Experience

### Tool window

The plugin contributes a **KMP Dependencies** tool window with:

- a dependency search field;
- a module selector defaulting to the active KMP module when possible;
- filters for target family, stable or preview versions, and result type;
- grouped results for Multiplatform, Platform-specific, Partial coverage, and Unknown or incompatible;
- provider availability and offline-cache status.

Each result shows the display name, canonical coordinate, latest stable version, supported target families, evidence badge, source provenance, and a short placement explanation.

### Result actions

**Add** opens a confirmation screen containing:

- target module;
- recommended source set, with other valid source sets selectable;
- selected version and stability;
- Gradle configuration such as `implementation`, `api`, or `ksp`;
- BOM handling where applicable;
- required or recommended companion plugins and processors;
- warnings and the evidence used for the recommendation.

**Copy** offers:

- raw `group:artifact:version` coordinates;
- a Kotlin DSL dependency declaration;
- version-catalog entries;
- the complete recipe, including plugin or processor declarations when applicable.

**Details** shows supported targets, available modules, provenance, release status, curated notes, alternatives, and compatibility warnings.

### Preview and application

After confirmation, the plugin creates an in-memory Change Plan and renders a side-by-side diff for `gradle/libs.versions.toml` and the selected module's `build.gradle.kts`. The plan:

- reuses semantically equivalent version and library aliases;
- creates deterministic kebab-case catalog keys when no equivalent exists;
- detects ambiguous aliases, incompatible existing versions, and concurrent file changes;
- treats each library, BOM, processor, and companion plugin change as a separately visible plan item.

No file changes occur until the user presses **Apply**. Application is one IDE write command so all touched files participate in a single native Undo operation. If either file changed after preview generation, Apply is rejected and the preview must be regenerated. Successful application suggests Gradle sync but does not trigger it automatically.

## Architecture

### Project Model Adapter

The adapter reads the IDE's imported Gradle and Kotlin project models and produces an internal model containing:

- modules and their build files;
- Kotlin, Gradle, and Android Gradle Plugin versions when available;
- repositories;
- targets, compilations, and hierarchical source sets;
- existing dependencies and configurations;
- the standard `libs` version catalog and its aliases.

JetBrains and Android Studio APIs are confined to this adapter and the UI layer. Core search, compatibility, ranking, and change-planning code uses IDE-independent data classes.

### Search Providers

Providers implement a common asynchronous interface and return normalized candidates plus provenance. The MVP uses:

1. **klibs.io:** KMP discovery, target support, Maven coordinates, license, and project activity. The client uses klibs.io's public read-only interface and sends only the user's search expression and filters.
2. **Maven Central:** canonical coordinates, versions, POMs, Gradle Module Metadata, Kotlin tooling metadata, and publication timestamps through Sonatype's documented search and repository endpoints.
3. **Google Maven:** AndroidX and Google artifacts through the repository's published master/group indexes and artifact metadata.
4. **Gradle Plugin Portal repository:** plugin marker artifacts and versions needed by curated companion recipes. The MVP does not expose independent plugin search.
5. **Recipe Registry:** a bundled declarative catalog with optional HTTPS updates for relationships repository metadata cannot reliably express.

Provider failures are isolated. Searches are debounced, cancellable, concurrent, deduplicated by canonical coordinate, and cached locally with provider-specific expiry. Cached data records its provider and retrieval time.

### Metadata and Compatibility Engine

The engine merges candidates and assigns target coverage and evidence. Evidence levels are:

- **Verified:** Gradle Module Metadata or Kotlin tooling metadata proves the relevant target publications exist.
- **Curated:** a reviewed recipe supplies information absent from repository metadata.
- **Inferred:** artifact naming or related publications imply support without definitive metadata.
- **Unknown:** available metadata cannot establish support.
- **Incompatible:** metadata or a curated constraint establishes a mismatch.

Repository publication metadata takes precedence over conflicting curated target claims. Curated compatibility constraints may still add a warning for relationships that publication metadata cannot represent, such as a compiler-plugin version pairing.

For a selected module, the engine maps artifact target coverage onto the imported KMP source-set graph. It recommends the highest shared source set whose descendant targets are all supported. If that is not `commonMain`, it recommends the closest compatible intermediate or platform source set. Android-only and JVM-only results remain visible and are normally placed in `androidMain` and `jvmMain` respectively. Unknown results may be added only after an explicit warning. Incompatible results are copyable; Add requires an explicit override and never appears as recommended.

### Recipe Registry

Recipes are versioned declarative data. They may describe:

- preferred modules and deprecated replacements;
- BOM/platform declarations;
- required processors such as KSP artifacts;
- required or recommended Gradle plugins;
- Kotlin, Compose, AGP, or processor version constraints;
- suitable Gradle configuration and source-set restrictions;
- explanatory links and provenance.

Recipes cannot contain executable code, arbitrary Gradle text, or arbitrary file paths. Remote recipe updates must use HTTPS, match the strict schema, stay within a size limit, and pass integrity verification against metadata shipped with a plugin release or a subsequently trusted catalog manifest. Invalid remote data is discarded and the bundled registry remains active.

### Change Planner and Applicator

The Change Planner accepts the project model, chosen candidate, placement, configuration, and applicable recipes. It emits structured catalog and Kotlin DSL operations rather than text fragments. Separate renderers produce both the preview and applied edits from the same operations.

The catalog editor uses TOML PSI when available and a structure-preserving parser fallback covered by golden tests. The Kotlin DSL editor uses Kotlin/Gradle PSI and supports dependency calls inside KMP `sourceSets` dependency blocks plus supported top-level dependency blocks required by a recipe. It preserves unrelated formatting and comments.

The Applicator validates file modification stamps and reparses the proposed result before starting an IDE write command. Any failed validation prevents all writes. Both files are then updated in one undoable command.

### IDE and UI Layer

The UI layer owns the tool window, search state, filters, details, confirmation, diff preview, notifications, settings, and native Undo integration. Long-running network and metadata work runs outside the UI thread and is cancellable when the query or selected module changes.

## Search and Ranking

Ranking uses, in order:

1. exact display-name, artifact, and coordinate matches;
2. compatibility with the selected module's actual target graph;
3. stable releases over previews unless the user enables preview preference;
4. curated recommendations and maintained projects;
5. verified evidence over curated, inferred, and unknown evidence;
6. text relevance and provider quality signals.

Ranking never silently removes incompatible or unknown results when they match the query. It places them in a separate group and explains the limitation.

## Security and Privacy

- The plugin has no dedicated backend and requires no account or credentials.
- It makes unauthenticated read-only HTTPS requests to enabled providers.
- Settings and Marketplace documentation enumerate endpoints and explain that search terms are transmitted.
- Project names, source code, file contents, and the installed dependency list are not transmitted.
- Network payloads are untrusted and subject to schemas, byte limits, timeouts, redirect restrictions, and defensive parsing.
- Remote recipes are data-only and cannot execute scripts.
- No provider response can directly mutate project files; every change passes through the structured planner, preview, validation, and explicit Apply action.

## Failure Handling

- A failed provider displays a nonblocking status and does not suppress other results.
- Offline mode searches unexpired or stale-marked cache entries and bundled recipes.
- Rate limits use bounded exponential backoff and honor server retry guidance.
- Stale cache results show their retrieval time.
- Malformed metadata lowers confidence or removes only the affected candidate.
- Ambiguous project models and unsupported Gradle layouts are copy-only.
- Alias or version conflicts stop planning and present actionable choices; the MVP does not silently upgrade existing dependencies.
- Concurrent file changes invalidate the preview.
- Validation failures occur before the write transaction, leaving project files unchanged.
- Unexpected failures produce a concise user message and detailed IDE log entry without logging source contents.

## Testing Strategy

### Core tests

- Unit tests for normalization, merging, ranking, stability selection, target coverage, source-set placement, recipes, and alias generation.
- Property-style tests for alias collisions, malformed metadata, and adversarial provider payloads.
- Provider contract tests using recorded sanitized responses so normal CI is deterministic and offline.
- Golden-file tests for exact TOML and Kotlin DSL previews and edits.

### IDE integration tests

- Imported project and source-set detection.
- TOML and Kotlin PSI edits with comment and formatting preservation.
- One-step Undo across all modified files.
- Stale-preview rejection.
- Search cancellation and UI-thread safety.
- Unsupported-layout copy-only behavior.

### Project fixtures

Fixtures cover hierarchical KMP, Compose Multiplatform, Android Multiplatform Library, Android-only and JVM modules, iOS target families, JS and Wasm, custom intermediate source sets, BOMs, KSP, serialization, partial target coverage, alias collisions, and existing-version conflicts.

### Compatibility and release validation

CI builds the plugin and runs JetBrains Plugin Verifier against the current and immediately previous IntelliJ platform generations supported by IntelliJ IDEA and Android Studio. The exact IDE builds are pinned in the release branch and advanced deliberately after compatibility tests pass.

## MVP Acceptance Criteria

The MVP is ready for Marketplace review when:

- Android Studio and IntelliJ IDEA can install and open the plugin on the declared platform range.
- A user can search KMP and platform-specific libraries and see source provenance and confidence.
- Search remains useful when any one provider is unavailable.
- The plugin recommends and explains a valid source set for every supported fixture.
- Add can plan libraries, BOMs, and required companion plugins/processors.
- Copy produces raw coordinates, Kotlin DSL, catalog entries, and complete recipes.
- Every mutation is previewed, explicitly confirmed, syntactically validated, and reversible with one Undo.
- Concurrent changes and unsupported layouts cannot be overwritten.
- Test fixtures never produce syntactically invalid TOML or Kotlin DSL.
- Marketplace documentation accurately states network endpoints, transmitted data, limitations, and evidence semantics.

## Planned Evolution After the MVP

Potential later work includes opt-in Gradle resolution validation, custom catalogs, independent plugin search, dependency updates, vulnerability data, richer health signals, and Groovy DSL support. These are separate features and do not shape the MVP architecture beyond the provider and planner extension points already defined.
