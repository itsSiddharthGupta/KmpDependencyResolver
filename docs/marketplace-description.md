# KMP Dependency Resolver

Find Kotlin Multiplatform, Compose Multiplatform, and platform-specific dependencies without leaving IntelliJ IDEA or Android Studio.

The plugin searches enabled public services—klibs.io, Maven Central, and Google Maven—and combines their coordinates, versions, publication metadata, and target evidence. Reviewed recipes can add required Gradle plugins, processors, or BOM declarations. Remote recipe updates are authenticated and can be disabled.

For supported Kotlin DSL projects using `gradle/libs.versions.toml`, **Add** recommends a real source set, asks you to confirm structured choices, and shows native Original/Proposed diffs before one atomic, undoable edit. **Copy** provides raw coordinates, Kotlin DSL, catalog entries, or a complete recipe without changing the project.

Evidence is explicit:

- **Recommended/Compatible** results have useful target evidence.
- **Unknown** results require source-set selection and acknowledgement.
- **Incompatible** results require the exact phrase `Add despite known incompatibility`.

KMP Dependency Resolver is client-only. It has no account, backend, analytics, or telemetry. Network access is limited to fixed HTTPS endpoints for klibs.io, Maven Central, Google Maven, Gradle Plugin Portal metadata, and signed recipe files on GitHub. Project names, paths, source code, build files, and installed dependency lists are not transmitted. See the repository privacy document for exact hosts and controls.

The MVP edits only Kotlin DSL projects with the standard Gradle version catalog. Groovy and nonstandard catalog layouts remain Copy-only. Metadata-based compatibility is guidance, not a guarantee.
