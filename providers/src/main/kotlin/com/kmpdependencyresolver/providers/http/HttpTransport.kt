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

open class HttpTransportException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A non-2xx response. Callers that probe optional files (such as `.module`) treat 404 as "absent". */
class HttpStatusException(val status: Int, message: String) : HttpTransportException("HTTP_STATUS", message)
