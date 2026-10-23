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
        return -VersionOrder.compare(left.value, right.value)
    }

    private fun evidenceRank(evidence: EvidenceKind): Int = when (evidence) {
        EvidenceKind.VERIFIED -> 0
        EvidenceKind.CURATED -> 1
        EvidenceKind.INFERRED -> 2
        EvidenceKind.UNKNOWN -> 3
        EvidenceKind.INCOMPATIBLE -> 4
    }

}

/** Orders version strings numerically by segment (`1.10.0` > `1.9.0`); used to pick and sort versions consistently. */
object VersionOrder : Comparator<String> {
    private val VERSION_PART = Regex("[0-9]+|[A-Za-z]+")

    override fun compare(left: String, right: String): Int {
        val leftParts = VERSION_PART.findAll(left).map(MatchResult::value).toList()
        val rightParts = VERSION_PART.findAll(right).map(MatchResult::value).toList()
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = comparePart(leftParts.getOrNull(index) ?: "0", rightParts.getOrNull(index) ?: "0")
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
}

/**
 * The version Copy, the result row and Add preselect: the newest stable version, or the newest version
 * of any kind when pre-releases were requested.
 */
fun defaultVersion(candidate: Candidate, includePreRelease: Boolean): String? {
    val versions = candidate.versions
    return if (includePreRelease) versions.map { it.value }.maxWithOrNull(VersionOrder)
    else versions.filter { it.stable }.map { it.value }.maxWithOrNull(VersionOrder) ?: versions.map { it.value }.maxWithOrNull(VersionOrder)
}
