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
    private val timeoutMillis: Long = 15_000,
    private val merger: CandidateMerger = CandidateMerger(),
) : AutoCloseable {
    // Unbounded: a provider abandoned after the deadline may still be finishing its HTTP call, and it
    // must not occupy a slot that the next query's providers need.
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "kmp-dependency-search").apply { isDaemon = true }
    }

    fun search(request: SearchRequest): AggregatedSearchResult {
        val futures = providers.map { provider ->
            executor.submit<ProviderResult> { provider.search(request) } to provider.id
        }
        val results = mutableListOf<ProviderResult>()
        val failures = mutableListOf<ProviderFailure>()
        // One shared deadline: waiting timeoutMillis per provider let three slow providers take 3x as long.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            for ((future, providerId) in futures) {
                try {
                    val result = future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    results += result
                    result.failure?.let(failures::add)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    failures += ProviderFailure(providerId, "Provider timed out")
                } catch (exception: java.util.concurrent.ExecutionException) {
                    failures += ProviderFailure(providerId, exception.cause?.message ?: "Provider failed")
                } catch (_: CancellationException) {
                    failures += ProviderFailure(providerId, "Provider cancelled")
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
