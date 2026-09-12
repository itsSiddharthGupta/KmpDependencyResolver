package com.kmpdependencyresolver.providers.klibs

import com.kmpdependencyresolver.providers.http.HttpRequestSpec
import com.kmpdependencyresolver.providers.http.HttpTransport
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface McpClient {
    fun initialize()
    fun listTools(): Set<String>
    fun callTool(name: String, arguments: JsonObject): JsonObject
}

class McpProtocolException(val code: String, message: String) : IllegalStateException(message)

class StreamableHttpMcpClient(
    private val transport: HttpTransport,
    private val endpoint: URI = URI.create("https://api.klibs.io/mcp"),
) : McpClient {
    private val ids = AtomicLong()

    override fun initialize() {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "kmp-dependency-resolver"); put("version", "0.1.0") })
            },
        ).getValue("result").jsonObject
        if (result["protocolVersion"]?.jsonPrimitive?.content != PROTOCOL_VERSION ||
            result["capabilities"]?.jsonObject?.containsKey("tools") != true
        ) throw McpProtocolException("UNSUPPORTED_SERVER", "klibs MCP does not expose the required tool capability")
    }

    override fun listTools(): Set<String> = request("tools/list", buildJsonObject {})
        .getValue("result").jsonObject.getValue("tools").jsonArray
        .map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()

    override fun callTool(name: String, arguments: JsonObject): JsonObject {
        val envelope = request(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", arguments) },
        )
        val result = envelope.getValue("result").jsonObject
        if (result["isError"]?.jsonPrimitive?.content == "true") {
            throw McpProtocolException("TOOL_ERROR", "klibs MCP tool reported an error")
        }
        val textBlocks = result.getValue("content").jsonArray
            .map(JsonElement::jsonObject)
            .filter { it["type"]?.jsonPrimitive?.content == "text" }
        if (textBlocks.size != 1) throw McpProtocolException("UNEXPECTED_TOOL_RESULT", "Expected one text result")
        return runCatching { json.parseToJsonElement(textBlocks.single().getValue("text").jsonPrimitive.content).jsonObject }
            .getOrElse { throw McpProtocolException("UNEXPECTED_TOOL_RESULT", "Tool text is not a JSON object") }
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }.toString().encodeToByteArray()
        val response = transport.execute(
            HttpRequestSpec(
                endpoint, method = "POST", body = body,
                headers = mapOf("Accept" to "application/json, text/event-stream"),
                expectedContentTypes = setOf("application/json", "text/event-stream"),
                maxBytes = 2 * 1024 * 1024,
            ),
        )
        val decoded = response.body.decodeToString()
        val jsonText = if (decoded.lineSequence().any { it.startsWith("data:") }) {
            val data = decoded.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
            if (data.size != 1) throw McpProtocolException("UNEXPECTED_SSE", "Expected one SSE result")
            data.single()
        } else decoded
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { throw McpProtocolException("INVALID_JSON_RPC", "Response is not JSON-RPC") }
        if (root.containsKey("method")) throw McpProtocolException("SERVER_REQUEST_REJECTED", "Server-initiated MCP requests are not supported")
        if (root["id"]?.jsonPrimitive?.content != id.toString() || root["jsonrpc"]?.jsonPrimitive?.content != "2.0") {
            throw McpProtocolException("INVALID_JSON_RPC", "Mismatched JSON-RPC response")
        }
        if (root.containsKey("error")) throw McpProtocolException("REMOTE_ERROR", root.getValue("error").toString())
        return root
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        val json = Json { ignoreUnknownKeys = true }
    }
}
