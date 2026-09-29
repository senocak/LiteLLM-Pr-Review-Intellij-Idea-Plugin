package com.github.senocak.prreview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressIndicator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One pull request, reduced to the fields the tool window shows and the model is told about.
 */
data class PullRequest(
    val id: Int,
    val title: String,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val link: String?,
    /**
     * When Bitbucket last saw this pull request change, as it sent it.
     *
     * Not decoration: this is what tells the plugin that a diff it already downloaded belongs to an
     * older commit, which is the difference between a reviewer reading the current change and a
     * reviewer reading the one they read twenty minutes ago.
     */
    val updatedOn: String?,
) {
    /** `PROJ-42  Fix the thing  author · feature/x → main` */
    fun toListRow(): String {
        val meta = buildString {
            if (author.isNotBlank()) {
                append(author)
                append(" · ")
            }
            if (sourceBranch.isNotBlank() || targetBranch.isNotBlank()) {
                append(sourceBranch)
                append(" → ")
                append(targetBranch)
            }
        }
        return buildString {
            append("#")
            append(id)
            append("  ")
            append(title)
            if (meta.isNotBlank()) {
                append("   ")
                append(meta)
            }
        }
    }
}

/**
 * The two REST calls the plugin makes, against Bitbucket Server / Data Center API 1.0.
 *
 * Deliberately plain: no retries, no ETag caching, no pagination state carried between calls.
 * A PR list is re-read on demand and a diff is fetched once per review, so the only thing that
 * needs care is not lying to the user when a page of results is not the whole list.
 */
object BitbucketServerClient {
    /**
     * Page size Bitbucket is asked for. The server caps this at 100; a page this size is a
     * reasonable trade between round trips and how long a very busy repository takes to list.
     */
    private const val PAGE_SIZE = 50

    /**
     * Hard stop on paging. A repository with tens of thousands of open pull requests should not
     * be able to hold the tool window up indefinitely, and a reviewer is not going to scroll past
     * a thousand of them anyway.
     */
    private const val MAX_PAGES = 20

    private val MAX_PULL_REQUESTS = PAGE_SIZE * MAX_PAGES

    /**
     * Every open pull request, following the server's own paging until it says there is no more.
     *
     * @throws PrReviewException on a non-2xx response, or when the response is not the JSON
     *   that was asked for.
     */
    fun openPullRequests(
        settings: PluginSettings,
        coordinates: BitbucketCoordinates,
        indicator: ProgressIndicator,
    ): List<PullRequest> {
        val headers = headers(settings, "application/json")
        val collected = mutableListOf<PullRequest>()
        var start = 0
        repeat(MAX_PAGES) {
            indicator.checkCanceled()
            indicator.text2 = if (start == 0) {
                "Fetching open pull requests from ${coordinates.displayName}…"
            } else {
                "Fetching open pull requests from ${coordinates.displayName} (${collected.size} so far)…"
            }
            val body = HttpTransport.get(
                pullRequestsUrl(settings.bitbucketBaseUrl, coordinates, start),
                HttpTransport.BITBUCKET,
                headers,
            )
            collected += parsePullRequests(body)
            if (isLastPage(body) || collected.size >= MAX_PULL_REQUESTS) return collected
            start += PAGE_SIZE
        }
        return collected
    }

    /**
     * The unified diff of a pull request, exactly as the server renders it.
     *
     * @throws PrReviewException on a non-2xx response, or when the diff is empty, which is what a brand new pull request
     *   returns and which would otherwise reach the model as a review of nothing.
     */
    fun diff(settings: PluginSettings, coordinates: BitbucketCoordinates, pullRequestId: Int): String {
        val diff = HttpTransport.get(
            diffUrl(settings.bitbucketBaseUrl, coordinates, pullRequestId),
            HttpTransport.BITBUCKET,
            headers(settings, "text/plain"),
        )
        if (diff.isBlank()) {
            throw PrReviewException(
                "Pull request #$pullRequestId has no diff. It may have just been opened, " +
                    "or every change in it may have been reverted.",
            )
        }
        return diff
    }

    internal fun pullRequestsUrl(baseUrl: String, coordinates: BitbucketCoordinates, start: Int): String =
        "${baseUrl.trimEnd('/')}/rest/api/1.0/projects/${encode(coordinates.projectKey)}" +
            "/repos/${encode(coordinates.repoSlug)}/pull-requests?state=OPEN&limit=$PAGE_SIZE&start=$start"

    internal fun diffUrl(baseUrl: String, coordinates: BitbucketCoordinates, pullRequestId: Int): String =
        "${baseUrl.trimEnd('/')}/rest/api/1.0/projects/${encode(coordinates.projectKey)}" +
            "/repos/${encode(coordinates.repoSlug)}/pull-requests/$pullRequestId.diff"

    /**
     * Encodes one path segment.
     *
     * `URLEncoder` is a form encoder and turns a space into `+`, which is a literal plus inside a
     * path, so that one is put back.
     */
    private fun encode(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    private fun headers(settings: PluginSettings, accept: String): Map<String, String> = buildMap {
        put("Accept", accept)
        // Bitbucket Server accepts a personal access token as a bearer token. The base URL is
        // expected to be a plain host, so a token has to be cleared before the header is set.
        settings.bitbucketToken.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    internal fun parsePullRequests(body: String): List<PullRequest> {
        val root = parseObject(body)
        val values = root.getAsJsonArray("values") ?: return emptyList()
        return values.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val json = element.asJsonObject
            val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
                ?: return@mapNotNull null
            val author = json.objectOrNull("author")
            PullRequest(
                id = id,
                title = json.string("title").ifBlank { "(no title)" },
                author = author?.string("displayName").orEmpty().ifBlank { author?.string("name").orEmpty() },
                sourceBranch = json.objectOrNull("fromRef")?.string("displayId").orEmpty(),
                targetBranch = json.objectOrNull("toRef")?.string("displayId").orEmpty(),
                updatedOn = json.string("updatedOn").ifBlank { null },
                link = json.objectOrNull("links")
                    ?.getAsJsonArray("self")
                    ?.firstOrNull()
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.string("href")
                    ?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Bitbucket pages with `isLastPage`; the flag is absent on some older versions, where an empty
     * `values` means the same thing. Both readings stop the loop rather than requesting a page
     * that cannot exist.
     */
    internal fun isLastPage(body: String): Boolean {
        val root = parseObject(body)
        val flag = root.get("isLastPage")?.takeIf { it.isJsonPrimitive }
        if (flag != null) return flag.asBoolean
        return root.getAsJsonArray("values")?.size() == 0
    }

    private fun parseObject(body: String): JsonObject = try {
        JsonParser.parseString(body).asJsonObject
    } catch (exception: Exception) {
        throw PrReviewException("Bitbucket Server returned a response that is not JSON.", exception)
    }

    private fun JsonObject.string(field: String): String =
        get(field)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.objectOrNull(field: String): JsonObject? =
        get(field)?.takeIf { it.isJsonObject }?.asJsonObject
}
