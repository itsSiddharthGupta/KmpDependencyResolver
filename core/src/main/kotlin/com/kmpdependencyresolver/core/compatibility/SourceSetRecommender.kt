package com.kmpdependencyresolver.core.compatibility

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.ModuleModel
import com.kmpdependencyresolver.core.model.PlacementRecommendation
import com.kmpdependencyresolver.core.model.SourceSetNode

class SourceSetRecommender {
    fun recommend(candidate: Candidate, module: ModuleModel): PlacementRecommendation {
        if (candidate.evidence == EvidenceKind.UNKNOWN || candidate.supportedTargets.isEmpty()) {
            return PlacementRecommendation(
                sourceSetName = null,
                evidence = EvidenceKind.UNKNOWN,
                explanation = "Target coverage could not be established from available metadata.",
                requiresOverride = true,
            )
        }

        val nodesByName = module.sourceSets.associateBy(SourceSetNode::name)
        val compatible = module.sourceSets
            .asSequence()
            .filter { it.descendantTargets.isNotEmpty() }
            .filter { candidate.supportedTargets.containsAll(it.descendantTargets) }
            .sortedWith(
                compareByDescending<SourceSetNode> { it.descendantTargets.size }
                    .thenBy { distanceFromRoot(it, nodesByName, emptySet()) }
                    .thenBy(SourceSetNode::name),
            )
            .firstOrNull()

        if (compatible == null) {
            return PlacementRecommendation(
                sourceSetName = null,
                evidence = EvidenceKind.INCOMPATIBLE,
                explanation = "Verified target coverage has no compatible source set in module ${module.id}.",
                requiresOverride = true,
            )
        }

        return PlacementRecommendation(
            sourceSetName = compatible.name,
            evidence = candidate.evidence,
            explanation = "${compatible.name} is the broadest source set whose targets are supported.",
            requiresOverride = candidate.evidence == EvidenceKind.INCOMPATIBLE,
        )
    }

    private fun distanceFromRoot(
        node: SourceSetNode,
        nodesByName: Map<String, SourceSetNode>,
        visited: Set<String>,
    ): Int {
        if (node.name in visited || node.dependsOn.isEmpty()) return 0

        return 1 + node.dependsOn
            .mapNotNull(nodesByName::get)
            .minOfOrNull { distanceFromRoot(it, nodesByName, visited + node.name) }
            .orEmptyDistance()
    }

    private fun Int?.orEmptyDistance(): Int = this ?: 0
}
