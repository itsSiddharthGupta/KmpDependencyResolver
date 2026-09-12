package com.kmpdependencyresolver.providers.http

import java.net.URI

data class HttpRequestSpec(
    val uri: URI,
    val method: String = "GET",
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val expectedContentTypes: Set<String>,
    val maxBytes: Int = 2 * 1024 * 1024,
)

data class HttpPayload(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

interface HttpTransport {
    fun execute(request: HttpRequestSpec): HttpPayload
}

class HttpTransportException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
