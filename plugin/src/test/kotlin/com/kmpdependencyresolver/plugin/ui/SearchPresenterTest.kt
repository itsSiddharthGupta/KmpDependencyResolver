package com.kmpdependencyresolver.plugin.ui

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.model.TargetFamily
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.SearchRequest
import com.kmpdependencyresolver.providers.AggregatedSearchResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchPresenterTest {
    @Test
    fun `debounces cancels superseded search and publishes latest grouped state`() {
        val debounce = ManualTasks()
        val worker = ManualTasks()
        val ui = ManualTasks()
        val states = mutableListOf<SearchUiState>()
        val gateway = DependencySearchGateway { request ->
            AggregatedSearchResult(
                listOf(
                    candidate("verified", EvidenceKind.VERIFIED, setOf(TargetFamily.IOS, TargetFamily.JVM)),
                    candidate("unknown", EvidenceKind.UNKNOWN, emptySet()),
                    candidate("bad", EvidenceKind.INCOMPATIBLE, setOf(TargetFamily.ANDROID)),
                ),
                listOf(ProviderFailure("google-maven", "offline")),
            )
        }
        val presenter = SearchPresenter(gateway, debounce, worker, ui, activeModule = { ":shared" }, states::add)

        presenter.onQueryChanged("kto", setOf(TargetFamily.IOS), includePreRelease = false)
        presenter.onQueryChanged("ktor", setOf(TargetFamily.IOS), includePreRelease = false)

        assertThat(debounce.delays).containsExactly(300L, 300L)
        assertThat(debounce.cancelled).isEqualTo(1)
        debounce.runLatest(); worker.runLatest(); ui.runLatest()
        val state = states.last()
        assertThat(state.query).isEqualTo("ktor")
        assertThat(state.moduleId).isEqualTo(":shared")
        assertThat(state.groups[ResultGroup.RECOMMENDED]).extracting<String> { it.candidate.displayName }.containsExactly("verified")
        assertThat(state.groups[ResultGroup.UNKNOWN]).hasSize(1)
        assertThat(state.groups[ResultGroup.INCOMPATIBLE]).hasSize(1)
        assertThat(state.providerMessages).containsExactly("google-maven: offline")
        assertThat(state.results.first().evidenceLabel).isIn("Verified", "Unknown", "Incompatible")
    }

    @Test
    fun `add resolves a visible candidate and active module`() {
        val tasks = ManualTasks()
        val additions = mutableListOf<Pair<Candidate, String?>>()
        val result = candidate("ktor", EvidenceKind.VERIFIED, setOf(TargetFamily.JVM))
        val presenter = SearchPresenter(
            DependencySearchGateway { AggregatedSearchResult(listOf(result), emptyList()) },
            tasks, tasks, tasks, { ":shared" }, {},
            onAdd = { candidate, module -> additions += candidate to module },
        )

        presenter.onQueryChanged("ktor", emptySet(), false)
        tasks.runLatest(); tasks.runLatest(); tasks.runLatest()
        presenter.add("sample:ktor")

        assertThat(additions).containsExactly(result to ":shared")
    }

    @Test
    fun `official publisher results form the first section without hiding compatibility`() {
        val tasks = ManualTasks()
        val states = mutableListOf<SearchUiState>()
        val official = candidate("ktor-client-core", EvidenceKind.VERIFIED, setOf(TargetFamily.JVM), "io.ktor")
        val community = candidate("ktorfit-lib", EvidenceKind.VERIFIED, setOf(TargetFamily.JVM), "de.jensklingenberg.ktorfit")
        val presenter = SearchPresenter(
            DependencySearchGateway { AggregatedSearchResult(listOf(community, official), emptyList()) },
            tasks, tasks, tasks, { ":shared" }, states::add,
        )

        presenter.onQueryChanged("ktor", setOf(TargetFamily.JVM), false)
        tasks.runLatest(); tasks.runLatest(); tasks.runLatest()

        val state = states.last()
        assertThat(state.results.map { it.candidate.coordinates.notation })
            .containsExactly("io.ktor:ktor-client-core", "de.jensklingenberg.ktorfit:ktorfit-lib")
        assertThat(state.sections[PublisherSection.OFFICIAL]).containsExactly(state.results.first())
        assertThat(state.sections[PublisherSection.COMMUNITY]).containsExactly(state.results.last())
        assertThat(state.results.first().publisherName).isEqualTo("Ktor")
        assertThat(state.results.first().group).isEqualTo(ResultGroup.RECOMMENDED)
    }

    @Test
    fun `official publisher matching does not accept a group prefix lookalike`() {
        val catalog = OfficialPublisherCatalog()

        assertThat(catalog.publisherFor("io.ktor")).isEqualTo("Ktor")
        assertThat(catalog.publisherFor("io.ktor.client")).isEqualTo("Ktor")
        assertThat(catalog.publisherFor("io.ktormalicious")).isNull()
    }

    private fun candidate(
        name: String,
        evidence: EvidenceKind,
        targets: Set<TargetFamily>,
        group: String = "sample",
    ) = Candidate(
        Coordinates(group, name), name, listOf(DependencyVersion("1.0", true)), targets, evidence,
        setOf(Provenance("fixture")),
    )
}

private class ManualTasks : TaskScheduler, UiDispatcher {
    private data class Task(val block: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    val delays = mutableListOf<Long>()
    var cancelled = 0
    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        delays += delayMillis
        val queued = Task(task); tasks += queued
        return Cancellable { if (!queued.cancelled) { queued.cancelled = true; cancelled++ } }
    }
    override fun dispatch(task: () -> Unit) { tasks += Task(task) }
    fun runLatest() = tasks.last { !it.cancelled }.block()
}
