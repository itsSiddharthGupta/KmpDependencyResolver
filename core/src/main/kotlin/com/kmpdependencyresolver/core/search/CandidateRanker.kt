package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.compatibility.SourceSetRecommender
import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.ModuleModel

class CandidateRanker(
    private val recommender: SourceSetRecommender = SourceSetRecommender(),
) {
    fun rank(query: String, module: ModuleModel, candidates: List<Candidate>): List<Candidate> {
        val normalizedQuery = query.trim().lowercase()
        return candidates.sortedWith(
            compareByDescending<Candidate> { exactMatch(it, normalizedQuery) }
                .thenByDescending { compatibilityRank(it, module) }
                .thenByDescending { candidate -> candidate.versions.any { it.stable } }
                .thenByDescending { evidenceRank(it.evidence) }
                .thenBy { it.coordinates.notation },
        )
    }

    private fun exactMatch(candidate: Candidate, query: String): Boolean =
        candidate.coordinates.artifact.lowercase() == query ||
            candidate.coordinates.notation.lowercase() == query ||
            candidate.displayName.lowercase() == query

    private fun compatibilityRank(candidate: Candidate, module: ModuleModel): Int {
        val recommendation = recommender.recommend(candidate, module)
        return when {
            recommendation.sourceSetName != null -> 2
            recommendation.evidence == EvidenceKind.UNKNOWN -> 1
            else -> 0
        }
    }

    private fun evidenceRank(evidence: EvidenceKind): Int = when (evidence) {
        EvidenceKind.VERIFIED -> 4
        EvidenceKind.CURATED -> 3
        EvidenceKind.INFERRED -> 2
        EvidenceKind.UNKNOWN -> 1
        EvidenceKind.INCOMPATIBLE -> 0
    }
}
