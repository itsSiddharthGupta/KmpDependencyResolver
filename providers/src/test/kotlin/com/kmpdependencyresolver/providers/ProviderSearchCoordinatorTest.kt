package com.kmpdependencyresolver.providers

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.DependencyVersion
import com.kmpdependencyresolver.core.model.EvidenceKind
import com.kmpdependencyresolver.core.model.Provenance
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderSearchCoordinatorTest {
    @Test
    fun `one throwing and one reported failure do not hide useful candidates`() {
        val good = SearchProvider { result("central", listOf(candidate("io.ktor", "ktor-client-core"))) }
        val throwing = SearchProvider { error("klibs unavailable") }
        val failed = SearchProvider { result("google", emptyList(), ProviderFailure("google", "timeout")) }

        ProviderSearchCoordinator(listOf(good, throwing, failed), timeoutMillis = 1_000).use { coordinator ->
            val result = coordinator.search(SearchRequest("ktor", emptySet()))

            assertThat(result.candidates).extracting<String> { it.coordinates.notation }
                .containsExactly("io.ktor:ktor-client-core")
            assertThat(result.failures).hasSize(2)
            assertThat(result.failures).anyMatch { it.message.contains("klibs unavailable") }
            assertThat(result.failures).anyMatch { it.providerId == "google" }
        }
    }

    @Test
    fun `duplicates from completed providers are merged`() {
        val first = SearchProvider { result("central", listOf(candidate("sample", "library", "1.0"))) }
        val second = SearchProvider { result("klibs", listOf(candidate("sample", "library", "1.1"))) }

        ProviderSearchCoordinator(listOf(first, second)).use { coordinator ->
            val result = coordinator.search(SearchRequest("library", emptySet()))

            assertThat(result.candidates).hasSize(1)
            assertThat(result.candidates.single().versions).extracting<String> { it.value }.containsExactly("1.1", "1.0")
        }
    }

    @Test
    fun `timed out provider is cancelled without discarding completed results`() {
        val good = SearchProvider { result("central", listOf(candidate("io.ktor", "ktor-client-core"))) }
        val slow = SearchProvider {
            Thread.sleep(10_000)
            result("slow", emptyList())
        }

        ProviderSearchCoordinator(listOf(good, slow), timeoutMillis = 25).use { coordinator ->
            val result = coordinator.search(SearchRequest("ktor", emptySet()))

            assertThat(result.candidates).hasSize(1)
            assertThat(result.failures).anyMatch { it.message == "Provider timed out" }
        }
    }

    private fun result(id: String, candidates: List<Candidate>, failure: ProviderFailure? = null) =
        ProviderResult(id, candidates, failure, 1, false)

    private fun candidate(group: String, artifact: String, version: String = "1.0") = Candidate(
        Coordinates(group, artifact), artifact, listOf(DependencyVersion(version, true)), emptySet(),
        EvidenceKind.VERIFIED, setOf(Provenance("fixture")),
    )
}
