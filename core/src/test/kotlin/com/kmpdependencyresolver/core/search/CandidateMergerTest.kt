package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.TargetFamily
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandidateMergerTest {
    @Test
    fun `same coordinates merge versions targets evidence and provenance`() {
        val coordinate = Coordinates("io.ktor", "ktor-client-core")
        val results = listOf(
            providerResult(
                "klibs",
                candidate(
                    coordinate,
                    versions = listOf(DependencyVersion("3.1.0", stable = true)),
                    targets = setOf(TargetFamily.IOS),
                    evidence = EvidenceKind.CURATED,
                ),
            ),
            providerResult(
                "central",
                candidate(
                    coordinate,
                    versions = listOf(
                        DependencyVersion("3.2.0-beta.1", stable = false),
                        DependencyVersion("3.1.1", stable = true),
                    ),
                    targets = setOf(TargetFamily.ANDROID, TargetFamily.JVM),
                    evidence = EvidenceKind.VERIFIED,
                ),
            ),
        )

        val merged = CandidateMerger().merge(results).single()

        assertThat(merged.versions.map(DependencyVersion::value))
            .containsExactly("3.1.1", "3.1.0", "3.2.0-beta.1")
        assertThat(merged.supportedTargets)
            .containsExactlyInAnyOrder(TargetFamily.ANDROID, TargetFamily.IOS, TargetFamily.JVM)
        assertThat(merged.evidence).isEqualTo(EvidenceKind.VERIFIED)
        assertThat(merged.provenance.map(Provenance::providerId))
            .containsExactlyInAnyOrder("central", "klibs")
    }

    @Test
    fun `merge order is deterministic for shuffled provider results`() {
        val alpha = providerResult("zeta", candidate(Coordinates("dev.z", "zeta")))
        val beta = providerResult("alpha", candidate(Coordinates("dev.a", "alpha")))

        val forward = CandidateMerger().merge(listOf(alpha, beta))
        val reversed = CandidateMerger().merge(listOf(beta, alpha))

        assertThat(forward).isEqualTo(reversed)
        assertThat(forward.map { it.coordinates.notation })
            .containsExactly("dev.a:alpha", "dev.z:zeta")
    }

    private fun providerResult(provider: String, candidate: Candidate) = ProviderResult(
        providerId = provider,
        candidates = listOf(candidate.copy(provenance = setOf(Provenance(provider)))),
        retrievedAtEpochMillis = 100L,
        fromCache = false,
    )

    private fun candidate(
        coordinates: Coordinates,
        versions: List<DependencyVersion> = listOf(DependencyVersion("1.0.0", stable = true)),
        targets: Set<TargetFamily> = setOf(TargetFamily.JVM),
        evidence: EvidenceKind = EvidenceKind.VERIFIED,
    ) = Candidate(
        coordinates = coordinates,
        displayName = coordinates.artifact,
        versions = versions,
        supportedTargets = targets,
        evidence = evidence,
        provenance = setOf(Provenance("fixture")),
    )
}
