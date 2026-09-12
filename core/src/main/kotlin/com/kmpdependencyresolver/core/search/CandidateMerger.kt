package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind

class CandidateMerger {
    fun merge(results: List<ProviderResult>): List<Candidate> = results
        .flatMap(ProviderResult::candidates)
        .groupBy(Candidate::coordinates)
        .map { (_, candidates) -> mergeCandidates(candidates) }
        .sortedBy { it.coordinates.notation }

    private fun mergeCandidates(candidates: List<Candidate>): Candidate {
        val versions = candidates
            .flatMap(Candidate::versions)
            .groupBy(DependencyVersion::value)
            .map { (value, occurrences) ->
                DependencyVersion(value, stable = occurrences.any(DependencyVersion::stable))
            }
            .sortedWith(::compareVersions)

        return Candidate(
            coordinates = candidates.first().coordinates,
            displayName = candidates.map(Candidate::displayName).minOrNull().orEmpty(),
            versions = versions,
            supportedTargets = candidates.flatMap(Candidate::supportedTargets).toSet(),
            evidence = candidates.minBy { evidenceRank(it.evidence) }.evidence,
            provenance = candidates.flatMap(Candidate::provenance).toSet(),
        )
    }

    private fun compareVersions(left: DependencyVersion, right: DependencyVersion): Int {
        if (left.stable != right.stable) return if (left.stable) -1 else 1
        return -compareVersionValues(left.value, right.value)
    }

    private fun compareVersionValues(left: String, right: String): Int {
        val leftParts = VERSION_PART.findAll(left).map(MatchResult::value).toList()
        val rightParts = VERSION_PART.findAll(right).map(MatchResult::value).toList()
        val size = maxOf(leftParts.size, rightParts.size)

        for (index in 0 until size) {
            val leftPart = leftParts.getOrNull(index) ?: "0"
            val rightPart = rightParts.getOrNull(index) ?: "0"
            val comparison = comparePart(leftPart, rightPart)
            if (comparison != 0) return comparison
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun comparePart(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1
            rightNumber != null -> -1
            else -> left.compareTo(right, ignoreCase = true)
        }
    }

    private fun evidenceRank(evidence: EvidenceKind): Int = when (evidence) {
        EvidenceKind.VERIFIED -> 0
        EvidenceKind.CURATED -> 1
        EvidenceKind.INFERRED -> 2
        EvidenceKind.UNKNOWN -> 3
        EvidenceKind.INCOMPATIBLE -> 4
    }

    private companion object {
        val VERSION_PART = Regex("[0-9]+|[A-Za-z]+")
    }
}
