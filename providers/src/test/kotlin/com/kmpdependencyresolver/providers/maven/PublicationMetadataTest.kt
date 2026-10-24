package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicationMetadataTest {
    @Test
    fun `module metadata maps canonical publication and target families`() {
        val evidence = GradleModuleMetadataParser().parse(resource("multiplatform.module"))

        assertThat(evidence.canonicalCoordinates)
            .isEqualTo(Coordinates("org.jetbrains.kotlinx", "kotlinx-serialization-json"))
        assertThat(evidence.supportedTargets).containsExactlyInAnyOrder(
            TargetFamily.ANDROID, TargetFamily.JVM, TargetFamily.IOS, TargetFamily.LINUX,
            TargetFamily.MACOS, TargetFamily.MINGW, TargetFamily.JS, TargetFamily.WASM,
        )
        assertThat(evidence.rawTargetNames).contains("ios_arm64", "ios_x64", "ios_simulator_arm64")
        assertThat(evidence.evidence).isEqualTo(EvidenceKind.VERIFIED)
    }

    @Test
    fun `AndroidX android variant published as jvm with android environment counts as Android`() {
        val module = """
            {"formatVersion":"1.1","component":{"group":"androidx.room","module":"room-runtime","version":"2.8.4"},
             "variants":[
               {"name":"androidApiElements-published","attributes":{"org.gradle.jvm.environment":"android","org.jetbrains.kotlin.platform.type":"jvm"}},
               {"name":"jvmApiElements-published","attributes":{"org.gradle.jvm.environment":"standard-jvm","org.jetbrains.kotlin.platform.type":"jvm"}},
               {"name":"iosArm64ApiElements-published","attributes":{"org.jetbrains.kotlin.native.target":"ios_arm64","org.jetbrains.kotlin.platform.type":"native"}}
             ]}
        """.trimIndent().encodeToByteArray()

        val evidence = GradleModuleMetadataParser().parse(module)

        assertThat(evidence.supportedTargets).containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.JVM, TargetFamily.IOS)
        assertThat(evidence.evidence).isEqualTo(EvidenceKind.VERIFIED)
    }

    @Test
    fun `repository reader accepts the media types Central and Google serve for module files`() {
        val requested = mutableListOf<Set<String>>()
        val transport = object : com.kmpdependencyresolver.providers.http.HttpTransport {
            override fun execute(request: com.kmpdependencyresolver.providers.http.HttpRequestSpec): com.kmpdependencyresolver.providers.http.HttpPayload {
                requested += request.expectedContentTypes
                return com.kmpdependencyresolver.providers.http.HttpPayload(200, emptyMap(), resource("multiplatform.module"))
            }
        }

        val evidence = RepositoryPublicationEvidenceReader(transport, "https://repo1.maven.org/maven2")
            .read(Coordinates("org.jetbrains.kotlinx", "kotlinx-serialization-json"), "1.11.0")

        assertThat(requested.first()).contains("application/vnd.org.gradle.module+json", "application/octet-stream", "application/json")
        assertThat(evidence.evidence).isEqualTo(EvidenceKind.VERIFIED)
        assertThat(evidence.supportedTargets).contains(TargetFamily.ANDROID, TargetFamily.IOS)
    }

    @Test
    fun `tooling metadata supplements families and retains raw Kotlin targets`() {
        val evidence = KotlinToolingMetadataParser().parse(resource("tooling-metadata.json"))

        assertThat(evidence.supportedTargets).contains(TargetFamily.IOS, TargetFamily.JVM, TargetFamily.JS, TargetFamily.WASM)
        assertThat(evidence.rawTargetNames).contains("ios_arm64", "ios_x64", "ios_simulator_arm64")
    }

    @Test
    fun `malformed metadata becomes unknown evidence`() {
        val evidence = GradleModuleMetadataParser().parse(resource("malformed.json"))

        assertThat(evidence.evidence).isEqualTo(EvidenceKind.UNKNOWN)
        assertThat(evidence.supportedTargets).isEmpty()
    }

    @Test
    fun `unsupported and contradictory attributes are not marked verified`() {
        val unsupported = """{"component":{"group":"sample","module":"library"},"variants":[{"attributes":{"org.jetbrains.kotlin.platform.type":"future"}}]}"""
        val contradictory = """{"component":{"group":"sample","module":"library"},"variants":[{"attributes":{"org.jetbrains.kotlin.platform.type":"jvm","org.jetbrains.kotlin.native.target":"ios_arm64"}}]}"""

        assertThat(GradleModuleMetadataParser().parse(unsupported.encodeToByteArray()).evidence)
            .isEqualTo(EvidenceKind.UNKNOWN)
        assertThat(GradleModuleMetadataParser().parse(contradictory.encodeToByteArray()).evidence)
            .isEqualTo(EvidenceKind.UNKNOWN)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/maven/$name")).use { it.readAllBytes() }
}
