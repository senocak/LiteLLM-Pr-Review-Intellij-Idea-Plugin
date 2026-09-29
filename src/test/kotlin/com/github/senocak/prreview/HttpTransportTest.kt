package com.github.senocak.prreview

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The transport against a real socket, because what is being checked here is what goes on the wire.
 *
 * A 405 is a statement about the verb, so asserting on a mock of the request builder would have
 * asserted that the plugin called the method it was asked to call. These tests make the server
 * answer 405 to anything but the right verb, which is the only way to know the review is a POST.
 */
class HttpTransportTest {
    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    /** What the server was actually asked, as opposed to what the plugin believes it asked. */
    private val received = mutableListOf<Exchange>()

    data class Exchange(
        val method: String,
        val path: String,
        val contentType: String?,
        val accept: String?,
        val authorization: String?,
        val body: String,
    )

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Every route answers, including the ones only used to provoke a status: a handler that
        // records and returns nothing makes the client wait out the two-minute read timeout.
        server.createContext("/v1/chat/completions") { exchange ->
            record(exchange)
            respond(exchange, 200, """{"choices":[{"message":{"content":"Looks good."}}]}""")
        }
        server.createContext("/v1/models") { exchange ->
            record(exchange)
            respond(exchange, 200, """{"data":[{"id":"gpt-4o"}]}""")
        }
        server.createContext("/v1/guarded") { exchange ->
            record(exchange)
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.createContext("/v1/missing") { exchange ->
            record(exchange)
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        // Routes that exist but accept one verb only, the way a health, docs or admin page does.
        server.createContext("/health") { exchange -> onlyAllows(exchange, "GET", """{"status":"alive"}""") }
        server.createContext("/v1/post/only") { exchange -> onlyAllows(exchange, "POST", "{}") }
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    private fun onlyAllows(exchange: HttpExchange, allowed: String, body: String) {
        record(exchange)
        if (exchange.requestMethod == allowed) {
            respond(exchange, 200, body)
        } else {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
        }
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun record(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        received += Exchange(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            accept = exchange.requestHeaders.getFirst("Accept"),
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            body = body,
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun completion() = JsonObject().apply {
        addProperty("model", "gpt-4o")
        add("messages", com.google.gson.JsonArray())
    }

    @Test
    fun `a review is sent as a post`() {
        val response = HttpTransport.postJson(
            "$baseUrl/chat/completions",
            HttpTransport.LITELLM,
            completion(),
            mapOf("Authorization" to "Bearer sk-test"),
        )
        val exchange = received.single()
        assertEquals("POST", exchange.method)
        assertEquals("/v1/chat/completions", exchange.path)
        assertTrue(response.isNotEmpty())
    }

    @Test
    fun `a json post declares its content type`() {
        // The bug this pins down. With no declared body type the request is not a JSON request as
        // far as the gateway is concerned, and the failure that comes back does not mention it.
        HttpTransport.postJson("$baseUrl/chat/completions", HttpTransport.LITELLM, completion())
        val exchange = received.single()
        assertEquals("application/json; charset=utf-8", exchange.contentType)
    }

    @Test
    fun `a json post carries its body`() {
        HttpTransport.postJson("$baseUrl/chat/completions", HttpTransport.LITELLM, completion())
        assertTrue(received.single().body.contains("\"model\":\"gpt-4o\""))
    }

    @Test
    fun `a key is sent when there is one and omitted when there is not`() {
        HttpTransport.postJson(
            "$baseUrl/chat/completions",
            HttpTransport.LITELLM,
            completion(),
            mapOf("Authorization" to "Bearer sk-test"),
        )
        HttpTransport.postJson("$baseUrl/chat/completions", HttpTransport.LITELLM, completion())
        assertEquals(listOf("Bearer sk-test", null), received.map { it.authorization })
    }

    @Test
    fun `a model list is fetched with a get`() {
        val response = HttpTransport.get("$baseUrl/models", HttpTransport.LITELLM)
        val exchange = received.single()
        assertEquals("GET", exchange.method)
        assertEquals("/v1/models", exchange.path)
        assertTrue(response.contains("gpt-4o"))
    }

    @Test
    fun `a 405 names the service, the verb and the url`() {
        // The report this exists for: a 405 arrived as "Bitbucket Server / LiteLLM rejected the
        // request", which named two services and told the user nothing about which one, or about
        // which request, was at fault.
        val thrown = runCatching {
            HttpTransport.postJson(
                "http://127.0.0.1:${server.address.port}/health/chat/completions",
                HttpTransport.LITELLM,
                completion(),
            )
        }.exceptionOrNull()

        val message = thrown?.message.orEmpty()
        assertTrue(thrown is PrReviewException)
        assertTrue("should name the service: $message", message.contains("LiteLLM"))
        assertTrue("should not blame Bitbucket: $message", !message.contains("Bitbucket"))
        assertTrue("should name the verb: $message", message.contains("POST"))
        assertTrue("should name the url: $message", message.contains("/health/chat/completions"))
    }

    @Test
    fun `a 405 explains what a 405 means`() {
        // A GET at a POST-only route: the plugin's verb is wrong, which is what a base URL pointed
        // at a health or admin page produces.
        val message = runCatching {
            HttpTransport.get("$baseUrl/post/only", HttpTransport.LITELLM)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("should say the url exists: $message", message.contains("exists"))
        assertTrue("should name the verb: $message", message.contains("GET"))
    }

    @Test
    fun `a 401 blames the token and not the urls`() {
        val message = runCatching {
            HttpTransport.get("$baseUrl/guarded", HttpTransport.BITBUCKET)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("should name the service: $message", message.contains("Bitbucket Server"))
        assertTrue("should mention the token: $message", message.contains("access token"))
    }

    @Test
    fun `a 404 asks for the base url`() {
        val message = runCatching {
            HttpTransport.get("$baseUrl/missing", HttpTransport.BITBUCKET)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("should ask for the base url: $message", message.contains("base URL"))
        assertTrue("should name the url: $message", message.contains("/v1/missing"))
    }

    @Test
    fun `a connection refused is reported against the service that was called`() {
        // Port 1 is not listening. The message has to name LiteLLM here, or a reviewer cannot tell
        // a stopped proxy from a stopped Bitbucket.
        val message = runCatching {
            HttpTransport.get("http://127.0.0.1:1/v1/models", HttpTransport.LITELLM)
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("should name the service: $message", message.contains("LiteLLM"))
    }

    @Test
    fun `a failure that never reached a server is phrased without a service`() {
        // What the catch blocks in the panel now have to cope with, and what they must not
        // re-attribute: a failure the transport has not already named.
        val message = HttpTransport.describe(IllegalStateException("no project"))
        assertTrue(message.contains("no project"))
    }
}
