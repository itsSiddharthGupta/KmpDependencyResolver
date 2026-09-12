# Sanitized Maven contract fixtures

These reduced fixtures retain only fields used by the resolver. Their shapes were recorded from:

- Maven Central's documented Solr search response.
- Gradle Module Metadata published for kotlinx.serialization, Ktor, and Coil.
- Kotlin tooling metadata published with kotlinx.coroutines.
- Google Maven's master index, group index, and artifact `maven-metadata.xml`.

The `android-only.module` and malformed payload are deliberate boundary fixtures.
