package com.kmpdependencyresolver.providers

import com.kmpdependencyresolver.core.model.Candidate
import com.kmpdependencyresolver.core.search.CandidateMerger
import com.kmpdependencyresolver.core.search.ProviderFailure
import com.kmpdependencyresolver.core.search.ProviderResult
import com.kmpdependencyresolver.core.search.SearchProvider
import com.kmpdependencyresolver.core.search.SearchRequest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class AggregatedSearchResult(val candidates: List<Candidate>, val failures: List<ProviderFailure>)

class ProviderSearchCoordinator(
    private val providers: List<SearchProvider>,
    parallelism: Int = 3,
    private val timeoutMillis: Long = 15_000,
    private val merger: CandidateMerger = CandidateMerger(),
) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))

    fun search(request: SearchRequest): AggregatedSearchResult {
        val futures = providers.mapIndexed { index, provider ->
            executor.submit<ProviderResult> { provider.search(request) } to "provider-$index"
        }
        val results = mutableListOf<ProviderResult>()
        val failures = mutableListOf<ProviderFailure>()
        try {
            for ((future, fallbackId) in futures) {
                try {
                    val result = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
                    results += result
                    result.failure?.let(failures::add)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    failures += ProviderFailure(fallbackId, "Provider timed out")
                } catch (exception: java.util.concurrent.ExecutionException) {
                    failures += ProviderFailure(fallbackId, exception.cause?.message ?: "Provider failed")
                }
            }
        } catch (exception: InterruptedException) {
            futures.forEach { it.first.cancel(true) }
            Thread.currentThread().interrupt()
            throw CancellationException("Provider search cancelled").apply { initCause(exception) }
        }
        return AggregatedSearchResult(merger.merge(results), failures)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
