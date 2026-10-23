package com.kmpdependencyresolver.core.search

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.TargetFamily

data class SearchRequest(
    val query: String,
    val requiredTargets: Set<TargetFamily>,
    val includePreRelease: Boolean = false,
)

data class ProviderFailure(
    val providerId: String,
    val message: String,
)

data class ProviderResult(
    val providerId: String,
    val candidates: List<Candidate>,
    val failure: ProviderFailure? = null,
    val retrievedAtEpochMillis: Long,
    val fromCache: Boolean,
)

fun interface SearchProvider {
    fun search(request: SearchRequest): ProviderResult

    /** Stable identifier used when the coordinator has to report a failure on the provider's behalf. */
    val id: String get() = javaClass.simpleName
}
