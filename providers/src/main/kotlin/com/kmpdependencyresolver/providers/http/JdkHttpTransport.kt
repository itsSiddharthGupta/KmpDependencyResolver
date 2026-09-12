package com.kmpdependencyresolver.providers.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

fun interface HttpSender {
    fun send(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<InputStream>,
    ): HttpResponse<out InputStream>
}

class JdkHttpTransport(
    private val sender: HttpSender = defaultSender(),
) : HttpTransport {
    override fun execute(request: HttpRequestSpec): HttpPayload {
        if (!request.uri.scheme.equals("https", ignoreCase = true)) {
            throw HttpTransportException("HTTPS_REQUIRED", "Only HTTPS endpoints are allowed.")
        }

        val requestBuilder = HttpRequest.newBuilder(request.uri)
            .timeout(Duration.ofSeconds(10))
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "KMP-Dependency-Resolver/0.1.0")
        request.headers.forEach(requestBuilder::header)
        val httpRequest = requestBuilder
            .method(
                request.method,
                request.body?.let(HttpRequest.BodyPublishers::ofByteArray)
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()

        val response = try {
            sender.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw HttpTransportException("REQUEST_CANCELLED", "HTTP request was cancelled.", exception)
        } catch (exception: Exception) {
            throw HttpTransportException("REQUEST_FAILED", "HTTP request failed.", exception)
        }

        if (response.statusCode() in 300..399) {
            response.body().close()
            throw HttpTransportException("REDIRECT_REJECTED", "HTTP redirects are not followed.")
        }

        val contentType = response.headers().firstValue("content-type").orElse("")
            .substringBefore(';').trim().lowercase()
        if (request.expectedContentTypes.none { it.equals(contentType, ignoreCase = true) }) {
            response.body().close()
            throw HttpTransportException("UNEXPECTED_CONTENT_TYPE", "Unexpected content type: $contentType")
        }

        val encoded = response.body()
        val decoded = if (response.headers().firstValue("content-encoding").orElse("")
                .equals("gzip", ignoreCase = true)
        ) GZIPInputStream(encoded) else encoded

        val bytes = decoded.use { readBounded(it, request.maxBytes) }
        return HttpPayload(response.statusCode(), response.headers().map(), bytes)
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw HttpTransportException("REQUEST_CANCELLED", "HTTP response reading was cancelled.")
            }
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw HttpTransportException("PAYLOAD_TOO_LARGE", "HTTP payload exceeds $maxBytes bytes.")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        fun defaultSender(): HttpSender {
            val client = defaultHttpClient()
            return HttpSender(client::send)
        }
    }
}

internal fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()
