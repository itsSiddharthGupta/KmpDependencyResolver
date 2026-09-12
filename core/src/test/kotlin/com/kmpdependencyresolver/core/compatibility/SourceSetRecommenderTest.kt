package com.kmpdependencyresolver.core.compatibility

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.ModuleModel
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.SourceSetNode
import com.kmpdependencyresolver.core.model.TargetFamily
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceSetRecommenderTest {
    private val recommender = SourceSetRecommender()

    @Test
    fun `common artifact is placed in commonMain`() {
        val result = recommender.recommend(
            candidate(TargetFamily.ANDROID, TargetFamily.IOS, TargetFamily.JVM),
            moduleFixture(),
        )

        assertThat(result.sourceSetName).isEqualTo("commonMain")
        assertThat(result.evidence).isEqualTo(EvidenceKind.VERIFIED)
        assertThat(result.requiresOverride).isFalse()
    }

    @Test
    fun `android artifact is placed in androidMain`() {
        val result = recommender.recommend(candidate(TargetFamily.ANDROID), moduleFixture())

        assertThat(result.sourceSetName).isEqualTo("androidMain")
    }

    @Test
    fun `ios artifact is placed in shared iosMain`() {
        val result = recommender.recommend(candidate(TargetFamily.IOS), moduleFixture())

        assertThat(result.sourceSetName).isEqualTo("iosMain")
    }

    @Test
    fun `partial artifact uses closest matching intermediate source set`() {
        val result = recommender.recommend(
            candidate(TargetFamily.ANDROID, TargetFamily.JVM),
            moduleFixture(),
        )

        assertThat(result.sourceSetName).isEqualTo("clientMain")
    }

    @Test
    fun `unknown coverage requires an override without guessing a source set`() {
        val result = recommender.recommend(
            candidate(evidence = EvidenceKind.UNKNOWN),
            moduleFixture(),
        )

        assertThat(result.sourceSetName).isNull()
        assertThat(result.evidence).isEqualTo(EvidenceKind.UNKNOWN)
        assertThat(result.requiresOverride).isTrue()
        assertThat(result.explanation).contains("could not be established")
    }

    @Test
    fun `verified artifact with no covering source set is incompatible`() {
        val result = recommender.recommend(candidate(TargetFamily.MACOS), moduleFixture())

        assertThat(result.sourceSetName).isNull()
        assertThat(result.evidence).isEqualTo(EvidenceKind.INCOMPATIBLE)
        assertThat(result.requiresOverride).isTrue()
        assertThat(result.explanation).contains("no compatible source set")
    }

    private fun candidate(
        vararg targets: TargetFamily,
        evidence: EvidenceKind = EvidenceKind.VERIFIED,
    ) = Candidate(
        coordinates = Coordinates("example", "library"),
        displayName = "Example Library",
        versions = listOf(DependencyVersion("1.0.0", stable = true)),
        supportedTargets = targets.toSet(),
        evidence = evidence,
        provenance = setOf(Provenance("fixture")),
    )

    private fun moduleFixture() = ModuleModel(
        id = ":shared",
        sourceSets = listOf(
            SourceSetNode(
                name = "commonMain",
                descendantTargets = setOf(TargetFamily.ANDROID, TargetFamily.IOS, TargetFamily.JVM),
            ),
            SourceSetNode(
                name = "clientMain",
                descendantTargets = setOf(TargetFamily.ANDROID, TargetFamily.JVM),
                dependsOn = setOf("commonMain"),
            ),
            SourceSetNode(
                name = "iosMain",
                descendantTargets = setOf(TargetFamily.IOS),
                dependsOn = setOf("commonMain"),
            ),
            SourceSetNode(
                name = "androidMain",
                descendantTargets = setOf(TargetFamily.ANDROID),
                dependsOn = setOf("clientMain"),
            ),
            SourceSetNode(
                name = "jvmMain",
                descendantTargets = setOf(TargetFamily.JVM),
                dependsOn = setOf("clientMain"),
            ),
        ),
    )
}
