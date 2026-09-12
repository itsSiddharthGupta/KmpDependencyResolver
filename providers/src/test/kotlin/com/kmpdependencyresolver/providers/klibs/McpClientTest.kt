package com.kmpdependencyresolver.providers.klibs

import com.kmpdependencyresolver.providers.http.HttpPayload
import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.util.ArrayDeque
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class McpClientTest {
    @Test
    fun `initializes then discovers tools by name and calls a tool`() {
        val transport = QueueTransport(
            response("initialize.json"), response("tools-list.json"), response("search-projects.json"),
        )
        val client = StreamableHttpMcpClient(transport)

        client.initialize()
        assertThat(client.listTools()).contains("searchProjects", "getLatestVersion")
        val result = client.callTool("searchProjects", buildJsonObject { put("query", "serialization") })

        assertThat(result["projects"]).isNotNull()
        assertThat(transport.requests).allMatch { it.uri.toString() == "https://api.klibs.io/mcp" }
        assertThat(transport.requests).allMatch { it.maxBytes == 2 * 1024 * 1024 }
        assertThat(transport.requests).allMatch { it.headers["Accept"] == "application/json, text/event-stream" }
        assertThat(transport.bodies()).anyMatch { it.contains("\"method\":\"initialize\"") }
        assertThat(transport.bodies()).anyMatch { it.contains("\"name\":\"searchProjects\"") }
    }

    @Test
    fun `accepts one SSE result and rejects server initiated requests`() {
        val sse = resource("search-projects.sse")
        val transport = QueueTransport(HttpPayload(200, mapOf("content-type" to listOf("text/event-stream")), sse))
        val client = StreamableHttpMcpClient(transport)

        assertThat(client.callTool("searchProjects", buildJsonObject { put("query", "x") })["projects"]).isNotNull()

        val serverRequest = """{"jsonrpc":"2.0","id":9,"method":"ping","params":{}}""".encodeToByteArray()
        val rejecting = StreamableHttpMcpClient(QueueTransport(HttpPayload(200, emptyMap(), serverRequest)))
        assertThatThrownBy { rejecting.callTool("searchProjects", buildJsonObject { put("query", "x") }) }
            .isInstanceOf(McpProtocolException::class.java).extracting("code").isEqualTo("SERVER_REQUEST_REJECTED")
    }

    private fun response(name: String) = HttpPayload(200, mapOf("content-type" to listOf("application/json")), resource(name))
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/klibs/$name")).use { it.readAllBytes() }
}

private class QueueTransport(vararg responses: HttpPayload) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequestSpec>()
    override fun execute(request: HttpRequestSpec): HttpPayload {
        requests += request
        return queue.removeFirst()
    }
    fun bodies() = requests.map { it.body!!.decodeToString() }
}
