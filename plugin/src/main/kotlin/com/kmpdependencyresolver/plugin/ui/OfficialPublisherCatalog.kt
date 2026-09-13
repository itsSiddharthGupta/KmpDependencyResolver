package com.kmpdependencyresolver.plugin.ui

data class OfficialPublisher(
    val name: String,
    val groupPrefixes: Set<String>,
)

class OfficialPublisherCatalog(
    publishers: List<OfficialPublisher> = DEFAULT_PUBLISHERS,
) {
    private val publishers = publishers.flatMap { publisher ->
        publisher.groupPrefixes.map { prefix -> prefix to publisher.name }
    }.sortedByDescending { it.first.length }

    fun publisherFor(group: String): String? = publishers.firstOrNull { (prefix, _) ->
        group == prefix || group.startsWith("$prefix.")
    }?.second

    private companion object {
        val DEFAULT_PUBLISHERS = listOf(
            OfficialPublisher("Ktor", setOf("io.ktor")),
            OfficialPublisher("Kotlin", setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")),
            OfficialPublisher("Compose Multiplatform", setOf("org.jetbrains.compose")),
            OfficialPublisher("AndroidX", setOf("androidx")),
            OfficialPublisher("Google", setOf("com.google.android", "com.google.firebase")),
            OfficialPublisher("Cash App", setOf("app.cash")),
            OfficialPublisher("Square", setOf("com.squareup")),
            OfficialPublisher("Coil", setOf("io.coil-kt")),
        )
    }
}
