package com.kmpdependencyresolver.providers.http

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.zip.GZIPOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JdkHttpTransportTest {
    @Test
    fun `rejects non HTTPS before sending`() {
        var sent = false
        val transport = JdkHttpTransport(sender = HttpSender { _, _ -> sent = true; response() })

        assertThatThrownBy {
            transport.execute(HttpRequestSpec(URI("http://example.com/data"), expectedContentTypes = setOf("application/json")))
        }.isInstanceOf(HttpTransportException::class.java).extracting("code").isEqualTo("HTTPS_REQUIRED")
        assertThat(sent).isFalse()
    }

    @Test
    fun `sets bounded request headers and decodes gzip JSON`() {
        var captured: HttpRequest? = null
        val compressed = gzip("{\"ok\":true}".encodeToByteArray())
        val transport = JdkHttpTransport(sender = HttpSender { request, _ ->
            captured = request
            response(body = compressed, headers = mapOf("content-type" to listOf("application/json"), "content-encoding" to listOf("gzip")))
        })

        val payload = transport.execute(
            HttpRequestSpec(URI("https://example.com/data"), expectedContentTypes = setOf("application/json")),
        )

        assertThat(payload.body.decodeToString()).isEqualTo("{\"ok\":true}")
        assertThat(captured!!.timeout().orElseThrow().seconds).isEqualTo(10)
        assertThat(captured!!.headers().firstValue("User-Agent").orElseThrow()).startsWith("KMP-Dependency-Resolver/")
        assertThat(captured!!.headers().firstValue("Accept-Encoding").orElseThrow()).isEqualTo("gzip")
        assertThat(HttpRequestSpec(URI("https://example.com/data"), expectedContentTypes = setOf("application/json")).maxBytes)
            .isEqualTo(2 * 1024 * 1024)
    }

    @Test
    fun `default client bounds connection time and never follows redirects`() {
        val client = defaultHttpClient()

        assertThat(client.connectTimeout().orElseThrow().seconds).isEqualTo(5)
        assertThat(client.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER)
    }

    @Test
    fun `restores interruption when request is cancelled`() {
        val transport = JdkHttpTransport(sender = HttpSender { _, _ -> throw InterruptedException("cancelled") })
        val request = HttpRequestSpec(URI("https://example.com/data"), expectedContentTypes = setOf("application/json"))

        try {
            assertThatThrownBy { transport.execute(request) }
                .isInstanceOf(HttpTransportException::class.java).extracting("code").isEqualTo("REQUEST_CANCELLED")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `rejects redirects wrong content and oversized bodies`() {
        val request = HttpRequestSpec(URI("https://example.com/data"), expectedContentTypes = setOf("application/json"), maxBytes = 4)

        assertCode("REDIRECT_REJECTED", request, response(status = 302, headers = mapOf("location" to listOf("https://other.example/data"))))
        assertCode("UNEXPECTED_CONTENT_TYPE", request, response(headers = mapOf("content-type" to listOf("text/html"))))
        assertCode("PAYLOAD_TOO_LARGE", request, response(body = "12345".encodeToByteArray()))
    }

    private fun assertCode(code: String, request: HttpRequestSpec, response: HttpResponse<ByteArrayInputStream>) {
        val transport = JdkHttpTransport(sender = HttpSender { _, _ -> response })
        assertThatThrownBy { transport.execute(request) }
            .isInstanceOf(HttpTransportException::class.java).extracting("code").isEqualTo(code)
    }

    private fun response(
        status: Int = 200,
        body: ByteArray = "{}".encodeToByteArray(),
        headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json")),
    ): HttpResponse<ByteArrayInputStream> = object : HttpResponse<ByteArrayInputStream> {
        override fun statusCode() = status
        override fun headers() = HttpHeaders.of(headers) { _, _ -> true }
        override fun body() = ByteArrayInputStream(body)
        override fun request(): HttpRequest = throw UnsupportedOperationException()
        override fun previousResponse(): Optional<HttpResponse<ByteArrayInputStream>> = Optional.empty()
        override fun sslSession() = Optional.empty<javax.net.ssl.SSLSession>()
        override fun uri() = URI("https://example.com/data")
        override fun version() = java.net.http.HttpClient.Version.HTTP_2
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }
}
