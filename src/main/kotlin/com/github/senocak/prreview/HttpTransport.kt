package com.github.senocak.prreview

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.SocketTimeoutException

internal const val CONNECT_TIMEOUT_MS = 10_000

/**
 * A review reads a whole diff and a model has to think about it before answering, so this is
 * generously above the platform's own default rather than tuned to it.
 */
internal const val READ_TIMEOUT_MS = 120_000

/** A failure the plugin can put in front of a user, already phrased. */
class PrReviewException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The one place HTTP is actually performed.
 *
 * Both clients talk to a self-hosted service that a corporate network can make slow or
 * unreachable, so the two things worth getting right once are the timeouts and the error
 * translation. A raw stack trace in a dialog is not an answer; the status code almost always is.
 *
 * Every call names the service it is for, and the failure it raises is already phrased in terms of
 * that name. A review is two calls to two different servers that can each fail in their own way,
 * and a message that said "Bitbucket Server / LiteLLM" for a failure raised by only one of them
 * leaves the user unable to tell which half to go and fix.
 */
internal object HttpTransport {
    private val LOG = Logger.getInstance(HttpTransport::class.java)

    const val BITBUCKET = "Bitbucket Server"
    const val LITELLM = "LiteLLM"

    private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

    /** @throws PrReviewException on any non-2xx response, already phrased for [service]. */
    fun get(
        url: String,
        service: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String = send(url, "GET", service, headers, readTimeoutMs) { request -> request.readString() }

    /** @throws PrReviewException on any non-2xx response, already phrased for [service]. */
    fun postJson(
        url: String,
        service: String,
        payload: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): String = send(url, "POST", service, headers, READ_TIMEOUT_MS) { request ->
        // doOutput is what turns the connection into a request with a body. `HttpRequests.post`
        // sets it, and it is set again here because writing to a connection that has not been is an
        // IOException raised before anything reaches the server — which looks exactly like a network
        // problem and is not one.
        request.connection.doOutput = true
        request.connection.outputStream.writer(Charsets.UTF_8).use { it.write(payload.toString()) }
        request.readString()
    }

    private fun send(
        url: String,
        method: String,
        service: String,
        headers: Map<String, String>,
        readTimeoutMs: Int,
        body: (HttpRequests.Request) -> String,
    ): String = try {
        // A POST is built as a POST, rather than as a generic request that a write turns into one.
        val builder = if (method == "POST") HttpRequests.post(url, JSON_CONTENT_TYPE) else HttpRequests.request(url)
        builder
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(readTimeoutMs)
            .tuner { connection ->
                if (method == "POST") {
                    connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
                }
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            }
            .connect { request -> body(request) }
    } catch (exception: IOException) {
        if (exception is HttpRequests.HttpStatusException) {
            throw PrReviewException(describeStatus(exception.statusCode, service, method, url), exception)
        }
        if (exception is SocketTimeoutException) {
            throw PrReviewException(
                "$service did not answer in time ($method $url). It may be slow, or unreachable from this network.",
                exception,
            )
        }
        LOG.warn("$method $url failed (${exception.javaClass.simpleName}).", exception)
        throw PrReviewException(
            "$service could not be reached ($method $url): ${exception.message ?: exception.javaClass.simpleName}",
            exception,
        )
    }

    /**
     * Phrases a failure that never reached a status.
     *
     * Takes no service name, deliberately: [send] has already phrased anything that reached a
     * server, in terms of the service that was actually called. A caller that had to name the
     * service here would be repeating the guess that made a 405 from the gateway indistinguishable
     * from a 405 from Bitbucket in the first place.
     */
    fun describe(exception: Exception): String = when (exception) {
        is SocketTimeoutException -> "The request did not answer in time. The server may be slow, or unreachable from this network."
        is PrReviewException -> exception.message ?: "The request failed."
        else -> "The request failed: ${exception.message ?: exception.javaClass.simpleName}"
    }

    private fun describeStatus(status: Int, service: String, method: String, url: String): String = when (status) {
        401 -> "$service rejected the access token (HTTP 401). Check the personal access token in the settings."
        403 -> "$service refused the request (HTTP 403). The token may lack permission to read this repository."
        404 -> "$service does not know this resource (HTTP 404). The request was $method $url. Check the base URL."
        405 -> "$service refused the method (HTTP 405): $method $url. The URL exists but does not accept $method — it is probably a health, docs or admin page rather than the API root."
        in 500..599 -> "$service is unavailable (HTTP $status). The request was $method $url."
        // 400 and 422 and 429 are the ones that mean "the request itself is wrong", and the status
        // alone says nothing about what. The request is named so it can be correlated with what
        // was configured.
        else -> "$service rejected the request (HTTP $status). The request was $method $url."
    }
}
