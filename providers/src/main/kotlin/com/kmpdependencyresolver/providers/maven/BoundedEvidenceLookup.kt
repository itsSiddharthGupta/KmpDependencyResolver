package com.kmpdependencyresolver.providers.maven

import com.kmpdependencyresolver.core.model.Coordinates
import com.kmpdependencyresolver.core.model.EvidenceKind
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads publication evidence for several candidates concurrently within one time budget.
 *
 * Sequential `.module` lookups for 50 search hits took 20–40 s against Maven Central, which exceeded the
 * coordinator timeout and discarded the provider's whole result. Lookups that miss the budget degrade
 * to UNKNOWN evidence instead of failing the search.
 */
internal class BoundedEvidenceLookup(
    private val reader: PublicationEvidenceReader,
    private val parallelism: Int = 8,
    private val budgetMillis: Long = 8_000,
) {
    fun readAll(requests: List<Pair<Coordinates, String>>): List<PublicationEvidence> {
        if (requests.isEmpty()) return emptyList()
        val executor: ExecutorService = Executors.newFixedThreadPool(parallelism.coerceIn(1, requests.size)) { runnable ->
            Thread(runnable, "kmp-dependency-evidence").apply { isDaemon = true }
        }
        try {
            val futures = executor.invokeAll(
                requests.map { (coordinates, version) ->
                    Callable {
                        runCatching { reader.read(coordinates, version) }
                            .getOrElse { PublicationEvidence(coordinates, emptySet(), EvidenceKind.UNKNOWN, detail = it.message) }
                    }
                },
                budgetMillis,
                TimeUnit.MILLISECONDS,
            )
            return futures.mapIndexed { index, future ->
                if (future.isCancelled) {
                    PublicationEvidence(requests[index].first, emptySet(), EvidenceKind.UNKNOWN, detail = "Publication metadata lookup timed out")
                } else {
                    future.get()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
