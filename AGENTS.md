# Project Memory

## Product

KMP Dependency Resolver is a client-only JetBrains plugin for Android Studio and IntelliJ IDEA. It searches Kotlin Multiplatform, Compose Multiplatform, and platform-specific libraries, explains target evidence, offers four Copy formats, and previews safe dependency changes before applying them as one undoable IDE command.

The public repository is `https://github.com/itsSiddharthGupta/KmpDependencyResolver`. The plugin ID is `com.kmpdependencyresolver`; the initial version is `0.1.0`.

## Current State

- Tasks 1–14 in `docs/superpowers/plans/2026-09-12-kmp-dependency-resolver-implementation.md` are implemented except for the complete interactive IDE smoke matrix.
- `main` tracks `origin/main`. The Marketplace release-gate commit is `be53532`; result readability and official-publisher grouping were added in `89a7feb`.
- GitHub Actions run `34762961656` passed clean tests, packaging, project-configuration verification, and Plugin Verifier against all five pinned IDE distributions. It uploaded verification reports and an unsigned plugin ZIP.
- IntelliJ IDEA 2025.2.5 sandbox startup and K2 plugin loading passed. The user confirmed search, the revised result cards and official/community ordering, Add, and all four Copy actions work. One-step Undo, offline behavior, and the remaining IDE matrix still need manual confirmation because automation cannot attach to the Gradle-launched macOS sandbox process.
- Search results now show artifact/version, coordinates, compatibility/evidence, targets/providers, and a wrapped description. A conservative, boundary-safe publisher catalog places known official groups such as `io.ktor` above community results.
- The hierarchical smoke fixture now configures as a real Gradle project: its Android module declares namespace/compile SDK and its custom iOS hierarchy resolves `commonMain` correctly.
- Sandbox logs currently report a JetBrains `SlowOperations` warning because Add reads the project model from the EDT. Add succeeds, but move that snapshot work off the EDT before Marketplace release.
- Broader UI improvements are deliberately deferred until after the release-critical smoke matrix and EDT warning are resolved. Preserve them as follow-up work rather than expanding the `0.1.0` gate.
- The working tree was clean after the initial repository push.

## Architecture

- `core`: IDE-independent models, source-set recommendation, search merge/ranking, recipes, and structured change planning.
- `providers`: bounded HTTPS transport, local cache, klibs.io MCP, Maven Central, Google Maven, publication metadata, provider isolation, and signed recipe updates.
- `plugin`: JetBrains project/PSI adapters, preview and atomic application, Swing tool window, confirmation/diff UI, settings, and diagnostics.
- Supported edits are deliberately limited to Kotlin DSL and the standard `gradle/libs.versions.toml` catalog.

The approved design is in `docs/superpowers/specs/2026-09-12-kmp-dependency-resolver-design.md`. Preserve its evidence semantics: `VERIFIED`, `CURATED`, `INFERRED`, `UNKNOWN`, and `INCOMPATIBLE`. Never claim guaranteed compatibility.

## Release and Security

- Supported platform builds are `252` through `262.*`.
- Plugin Verifier pins IntelliJ IDEA Community 2025.2.5, unified IntelliJ IDEA 2026.1.3 and 2026.2.0.1, Android Studio 2025.2.3.9, and Android Studio 2025.3.1.6.
- The committed recipe catalog and manifest use an Ed25519 signature. GitHub contains the `RECIPE_ED25519_PRIVATE_KEY` Actions secret; no private signing key belongs in the repository.
- Marketplace publication additionally requires `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and `PUBLISH_TOKEN` repository secrets. They were not configured at the time of this note.
- Do not publish merely because Plugin Verifier passes. Complete and record the interactive matrix in `CHANGELOG.md` first.

## Verification Commands

Use JDK 21. The full release gate is:

```bash
./gradlew clean test verifyPluginProjectConfiguration :plugin:buildPlugin :plugin:verifyPlugin
```

The unsigned local distribution is produced at:

```text
plugin/build/distributions/KMP-Dependency-Resolver-0.1.0.zip
```

Before claiming completion, inspect `git status --short`, run `git diff --check`, and scan production code for restricted JetBrains APIs:

```bash
rg -n '(^|\.)impl\.|ApiStatus\.Internal|IntellijInternalApi' core providers plugin
```

## Remaining Manual Gate

On one pinned IntelliJ IDEA build and both pinned Android Studio builds:

1. Install the built ZIP and open a disposable copy of `plugin/src/test/testData/projects/hierarchical-kmp`.
2. Confirm the **KMP Dependencies** tool window opens and search for `ktor`.
3. Verify all four Copy formats.
4. Preview and apply an Add to `commonMain`; confirm both proposed file diffs, then use one native Undo and verify both files return byte-for-byte to their starting state.
5. Enable offline mode and confirm cached or bundled results remain usable without provider requests.
6. Record the exact IDE version/build and pass/fail evidence in `CHANGELOG.md`. A pending row is not a pass.
