package com.kmpdependencyresolver.core.search

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

class CandidateRankerTest {
    @Test
    fun `ranking is exact then compatible stable preview unknown and incompatible`() {
        val candidates = listOf(
            candidate("incompatible", setOf(TargetFamily.MACOS), EvidenceKind.INCOMPATIBLE),
            candidate("unknown", emptySet(), EvidenceKind.UNKNOWN),
            candidate(
                "preview",
                setOf(TargetFamily.ANDROID, TargetFamily.IOS),
                EvidenceKind.VERIFIED,
                stable = false,
            ),
            candidate("stable", setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.CURATED),
            candidate("ktor-client-core", setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.VERIFIED),
        )

        val ranked = CandidateRanker().rank("ktor-client-core", moduleFixture(), candidates)

        assertThat(ranked.map { it.coordinates.artifact })
            .containsExactly("ktor-client-core", "stable", "preview", "unknown", "incompatible")
    }

    @Test
    fun `ranking uses coordinates as final stable tie breaker`() {
        val candidates = listOf(
            candidate("zeta", setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.VERIFIED),
            candidate("alpha", setOf(TargetFamily.ANDROID, TargetFamily.IOS), EvidenceKind.VERIFIED),
        )

        val ranked = CandidateRanker().rank("client", moduleFixture(), candidates)

        assertThat(ranked.map { it.coordinates.artifact }).containsExactly("alpha", "zeta")
    }

    private fun candidate(
        artifact: String,
        targets: Set<TargetFamily>,
        evidence: EvidenceKind,
        stable: Boolean = true,
    ) = Candidate(
        coordinates = Coordinates("io.ktor", artifact),
        displayName = artifact,
        versions = listOf(DependencyVersion(if (stable) "1.0.0" else "1.1.0-beta.1", stable)),
        supportedTargets = targets,
        evidence = evidence,
        provenance = setOf(Provenance("fixture")),
    )

    private fun moduleFixture() = ModuleModel(
        id = ":shared",
        sourceSets = listOf(
            SourceSetNode(
                "commonMain",
                setOf(TargetFamily.ANDROID, TargetFamily.IOS),
            ),
            SourceSetNode("androidMain", setOf(TargetFamily.ANDROID), setOf("commonMain")),
            SourceSetNode("iosMain", setOf(TargetFamily.IOS), setOf("commonMain")),
        ),
    )
}
