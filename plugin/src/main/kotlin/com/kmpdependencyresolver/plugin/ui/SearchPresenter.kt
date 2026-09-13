package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.AggregatedSearchResult

fun interface DependencySearchGateway { fun search(request: SearchRequest): AggregatedSearchResult }
fun interface Cancellable { fun cancel() }
fun interface TaskScheduler { fun schedule(delayMillis: Long, task: () -> Unit): Cancellable }
fun interface UiDispatcher { fun dispatch(task: () -> Unit) }

enum class ResultGroup { RECOMMENDED, COMPATIBLE, UNKNOWN, INCOMPATIBLE }

data class SearchResultItem(
    val candidate: Candidate,
    val group: ResultGroup,
    val evidenceLabel: String,
    val freshnessLabel: String?,
)

data class SearchUiState(
    val query: String = "",
    val moduleId: String? = null,
    val searching: Boolean = false,
    val results: List<SearchResultItem> = emptyList(),
    val groups: Map<ResultGroup, List<SearchResultItem>> = ResultGroup.entries.associateWith { emptyList() },
    val providerMessages: List<String> = emptyList(),
)

class SearchPresenter(
    private val gateway: DependencySearchGateway,
    private val debounceScheduler: TaskScheduler,
    private val worker: TaskScheduler,
    private val ui: UiDispatcher,
    private val activeModule: () -> String?,
    private val onState: (SearchUiState) -> Unit,
    private val onAdd: (Candidate, String?) -> Unit = { _, _ -> },
) {
    private var generation = 0L
    private var pendingDebounce: Cancellable? = null
    private var pendingSearch: Cancellable? = null
    private var state = SearchUiState()

    fun onQueryChanged(
        query: String,
        requiredTargets: Set<TargetFamily>,
        includePreRelease: Boolean,
        moduleId: String? = null,
    ) {
        val currentGeneration = ++generation
        pendingDebounce?.cancel()
        pendingSearch?.cancel()
        val selectedModule = moduleId ?: activeModule()
        publish(SearchUiState(query, selectedModule, searching = query.isNotBlank()))
        if (query.isBlank()) return
        pendingDebounce = debounceScheduler.schedule(300) {
            pendingSearch = worker.schedule(0) {
                val result = gateway.search(SearchRequest(query, requiredTargets, includePreRelease))
                ui.dispatch {
                    if (generation != currentGeneration) return@dispatch
                    val items = result.candidates.map { candidate -> item(candidate, requiredTargets) }
                        .sortedWith(compareBy({ it.group.ordinal }, { it.candidate.coordinates.notation }))
                    publish(
                        SearchUiState(
                            query, selectedModule, false, items,
                            ResultGroup.entries.associateWith { group -> items.filter { it.group == group } },
                            result.failures.map { "${it.providerId}: ${it.message}" }.sorted(),
                        ),
                    )
                }
            }
        }
    }

    fun cancel() {
        generation++
        pendingDebounce?.cancel()
        pendingSearch?.cancel()
    }

    fun add(candidateId: String) {
        state.results.firstOrNull { it.candidate.coordinates.notation == candidateId }
            ?.let { onAdd(it.candidate, state.moduleId) }
    }

    private fun publish(next: SearchUiState) {
        state = next
        onState(next)
    }

    private fun item(candidate: Candidate, required: Set<TargetFamily>): SearchResultItem {
        val group = when {
            candidate.evidence == EvidenceKind.INCOMPATIBLE -> ResultGroup.INCOMPATIBLE
            candidate.evidence == EvidenceKind.UNKNOWN || candidate.supportedTargets.isEmpty() -> ResultGroup.UNKNOWN
            candidate.supportedTargets.containsAll(required) && candidate.evidence in setOf(EvidenceKind.VERIFIED, EvidenceKind.CURATED) -> ResultGroup.RECOMMENDED
            else -> ResultGroup.COMPATIBLE
        }
        val evidence = candidate.evidence.name.lowercase().replaceFirstChar(Char::uppercase)
        val details = candidate.provenance.mapNotNull { it.detail }.joinToString(" ").lowercase()
        val freshness = when {
            "stale" in details -> "Stale cache"
            "cache" in details -> "Cached"
            else -> null
        }
        return SearchResultItem(candidate, group, evidence, freshness)
    }
}
