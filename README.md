# KMP Dependency Resolver

KMP Dependency Resolver is a client-only JetBrains plugin for finding Kotlin Multiplatform, Compose Multiplatform, and platform-specific libraries. It recommends a source set from publication evidence, then lets you copy declarations or review and apply a version-catalog plus Kotlin DSL change.

## Supported environments

- IntelliJ IDEA and Android Studio builds `252` through `262.*`.
- Kotlin Multiplatform projects using `build.gradle.kts`.
- The standard Gradle version catalog at `gradle/libs.versions.toml`.
- Common, Android, JVM/Desktop, iOS, macOS, Linux, MinGW, JS, and Wasm target families.

Groovy builds, custom catalog locations, and arbitrary Gradle script transformations are Copy-only in the MVP. Compatibility is evaluated from available metadata; it is not a guarantee that a library will work in every project.

## Install

Marketplace publication is planned for the `0.1.0` release. To install a local build:

1. Run `./gradlew :plugin:buildPlugin` with JDK 21.
2. In IntelliJ IDEA or Android Studio, open **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk**.
3. Choose the ZIP under `plugin/build/distributions/`.

To run an isolated development IDE, use `./gradlew :plugin:runIde`.

## Search and evidence

Open **View → Tool Windows → KMP Dependencies**, select a Gradle module and optional target filter, then search by library name or coordinates. Results are grouped as:

- **Recommended:** verified or curated coverage includes the requested targets.
- **Compatible:** available evidence is compatible but not strong enough for the top group.
- **Unknown:** target coverage could not be established; Add requires explicit acknowledgement and source-set selection.
- **Incompatible:** known coverage does not satisfy the project placement; Add requires typing `Add despite known incompatibility`.

Search uses enabled sources from klibs.io, Maven Central, and Google Maven. Reviewed recipes cover required plugins, processors, and BOMs. Provider failure is isolated, so remaining providers and valid local data stay usable.

## Add and Copy

Select a result and choose **Add…** or press Enter. Confirm the version, source set, configuration, and companion recipe items. The plugin shows native Original/Proposed diffs for every changed file. Apply performs one validated, atomic IDE write command, so one Undo restores both the catalog and build file. Gradle sync is suggested, never started automatically.

Choose **Copy** or press Ctrl/Cmd+C for one of four non-mutating forms:

- raw `group:artifact:version` coordinate;
- Kotlin DSL dependency call;
- `[versions]` and `[libraries]` catalog entries;
- a complete recipe including plugin, processor, and source-set declarations when applicable.

## Privacy and settings

Provider toggles, offline mode, pre-release defaults, remote recipe updates, fixed endpoint display, and cache removal are under **Settings/Preferences → Tools → KMP Dependency Resolver**. See [Network and Privacy](docs/network-and-privacy.md) for the exact hosts and data boundaries.

## Development

Requirements: JDK 21 and an internet connection for platform dependency resolution.

```bash
./gradlew test
./gradlew verifyPluginProjectConfiguration
./gradlew :plugin:buildPlugin
./gradlew :plugin:verifyPlugin
```

The implementation and acceptance plan is in [docs/superpowers/plans/2026-09-12-kmp-dependency-resolver-implementation.md](docs/superpowers/plans/2026-09-12-kmp-dependency-resolver-implementation.md).

## Issues

Please report reproducible problems at [GitHub Issues](https://github.com/itsSiddharthGupta/KmpDependencyResolver/issues). Include the IDE build, plugin version, dependency coordinates, evidence label, and diagnostic code. Do not attach proprietary source files or credentials.
