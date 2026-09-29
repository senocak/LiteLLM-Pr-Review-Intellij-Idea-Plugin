package com.github.senocak.prreview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Talks to a LiteLLM proxy using the OpenAI Chat Completions shape.
 *
 * Only `/v1/chat/completions` is used. A proxy in front of an Anthropic model still speaks this
 * dialect, so the alternative would add a second wire format to maintain for no extra reach.
 */
object LiteLLMClient {
    /**
     * Ceiling on the review input, in characters.
     *
     * Roughly 15k tokens, which every model the plugin is aimed at has room for alongside a
     * review of that size. The tail is never a changed line: [buildReviewDiff] gives up unchanged
     * context long before this, and only drops changes when a pull request is larger than this and
     * larger than a human would read.
     */
    const val MAX_DIFF_CHARACTERS = 60_000

    /**
     * A review of a large change is long. The models this plugin is aimed at all accept at least
     * 4096 output tokens, and a truncated review is worse than a slow one.
     */
    const val MAX_OUTPUT_TOKENS = 4_096

    const val SYSTEM_PROMPT = """You are a senior engineer reviewing a pull request.

You are given the lines a pull request added, removed and changed, each with its line number in the \
file. Review only what is given to you, and never invent a line that is not in it.

Your job is to find the added and changed lines that have a **better version** — somewhere the code \
can be written more correctly, more safely, or faster — and to hand the author that version so they \
can decide about it. A finding without a concrete replacement is not a finding: "this could be \
cleaner" and "consider extracting this" are noise in a review, and a comment the author cannot act \
on costs them more than it saves.

What is worth a finding, in this order:
- **Correctness**: logic errors, null and boundary handling, off-by-one, race conditions, resource \
leaks, error paths that swallow or misreport a failure.
- **Security**: injection, authentication and authorisation gaps, secrets in code, unsafe \
deserialisation, logging values that should not be logged.
- **Performance**: work repeated inside a loop or a request, an N+1 query, a needless allocation in \
a hot path, an unbounded collection, a blocking call on a path that should not block. Only raise \
these when the cost is real for how the code is used — not for a one-line method called once.
- **Syntax and language use**: a deprecated or wrong API, a language feature that does what the \
code does by hand, an exception swallowed with an empty catch, a resource not closed on a path \
that can throw.
- **Tests**: behaviour that the change introduces and the change does not test.

Deleted lines (`-`) are context, not something to improve: you cannot rewrite code that is gone. \
You may comment on a deletion when removing it leaves something broken or loses behaviour \
altogether, and then say so without a replacement.

Rules:
- Cite each finding as `path/to/File.ext:line`, using the line number exactly as given. Never \
estimate or recompute a line number.
- Mark severity as **Blocking**, **Should fix**, or **Nit**.
- Give the replacement as a code block, in the language of the file, ready to paste. Keep it to \
the lines that change; do not rewrite the whole function.
- Prefer the smallest change that fixes the problem. Do not propose a different design, a new \
dependency, or a refactor of code the pull request did not touch.
- Say why in one or two sentences, naming the failure or the cost. Not "this is bad practice" but \
"this reads the file once per row, so a 10,000-row import is 10,000 reads".
- If nothing here has a better version, say so in one line and stop. That is a real answer and a \
useful one — do not invent findings to fill the space.
- Answer in Markdown, one `##` section per finding, in the order the files appear above.

The reviewer may add notes of their own before asking for a review. Those notes are the reviewer's \
words about the pull request, not code and not findings. Use them to understand what the change is \
meant to do, which files are generated or vendored and should be left alone, and which conventions \
this project holds itself to — a note saying a file is generated is a reason not to raise a finding \
about it. You cannot open a file, a ticket, a specification or a link that a note mentions. If \
something you would need to judge a line is only described there, say plainly that you cannot check \
it rather than assuming what it says, and do not raise a finding you cannot support from the lines \
you were given."""

    /**
     * Asks the configured model to review a pull request's changes.
     *
     * @param files the pull request's changed files, parsed from its diff. Sent as a change listing
     *   rather than a raw diff, so every added and modified line reaches the model with the line
     *   number its review will have to cite.
     * @param model the model to ask. Taken from the tool window's drop-down when there is one, and
     *   from the saved setting otherwise, so the tool window can change the model for a review
     *   without the review depending on the settings page having been reopened first.
     * @throws PrReviewException on a non-2xx response, or when the gateway answers with
     *   something that is not a completion.
     */
    internal fun review(
        settings: PluginSettings,
        pullRequest: PullRequest,
        files: List<DiffFile>,
        notes: String = "",
        model: String = settings.model,
    ): String {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_completion_tokens", MAX_OUTPUT_TOKENS)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                add(message("system", SYSTEM_PROMPT))
                add(message("user", userMessage(pullRequest, buildReviewDiff(files), notes)))
            })
        }
        val headers = buildMap {
            // Optional. A LiteLLM proxy on a developer laptop is often unauthenticated, while the
            // same proxy behind the corporate gateway usually has LITELLM_MASTER_KEY switched on,
            // so the key is sent only when one has actually been configured.
            settings.litellmApiKey.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        val response = HttpTransport.postJson(settings.litellmChatUrl, HttpTransport.LITELLM, payload, headers)
        return extractReview(response)
    }

    internal fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    /**
     * Asks the gateway which models it serves, so the settings page can offer a choice instead of
     * asking the user to know a name that changes with every provider release.
     *
     * @throws PrReviewException on a non-2xx response, or when the gateway answers with
     *   something that is not a model list.
     */
    fun models(litellmBaseUrl: String, apiKey: String): List<String> {
        val base = normalizeLiteLlmBaseUrl(litellmBaseUrl)
        require(base.isNotBlank()) { "The LiteLLM base URL is not set." }
        val headers = buildMap {
            apiKey.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return parseModels(HttpTransport.get("$base/models", HttpTransport.LITELLM, headers))
    }

    /**
     * Reads a `/models` response.
     *
     * A gateway serves a proxy, so the list is the set of names this installation can actually
     * route — including the aliases teams define in `config.yaml` — and a model typed by hand is
     * only as good as the user's memory of them. Both the OpenAI `{ "data": [...] }` object and a
     * bare array are accepted, the latter because some proxies answer with it.
     *
     * The order the gateway sends is kept rather than sorted: LiteLLM returns the aliases from
     * `config.yaml` in the order they were declared, and that is the order the user chose.
     * An empty list is a legitimate answer, not a failure — a gateway with nothing configured
     * returns one, and the caller has to keep the saved model usable in that case.
     */
    internal fun parseModels(response: String): List<String> {
        val root = try {
            JsonParser.parseString(response)
        } catch (exception: Exception) {
            throw PrReviewException("The LiteLLM gateway returned a response that is not JSON.", exception)
        }
        if (root.isJsonObject) {
            root.asJsonObject.getAsJsonObject("error")
                ?.string("message")
                ?.takeIf { it.isNotBlank() }
                ?.let { throw PrReviewException(it) }
        }
        val entries = if (root.isJsonArray) root.asJsonArray else root.asJsonObject.getAsJsonArray("data")
        if (entries == null) {
            throw PrReviewException("The LiteLLM gateway did not return a list of models.")
        }
        return entries.mapNotNull { entry ->
            val id = when {
                entry.isJsonObject -> entry.asJsonObject.string("id")
                entry.isJsonPrimitive -> entry.asString
                else -> null
            }
            id?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    /**
     * Frames the change listing with the context the model needs to judge it.
     *
     * The title and the branch names are in here because the changes on their own do not say what
     * the author was trying to do, and a reviewer who cannot tell that cannot tell whether the
     * implementation gets there.
     */
    internal fun userMessage(pullRequest: PullRequest, changes: String, notes: String = ""): String = buildString {
        append("Pull request #")
        append(pullRequest.id)
        append(": ")
        append(pullRequest.title)
        append("\n")
        if (pullRequest.author.isNotBlank()) {
            append("Author: ")
            append(pullRequest.author)
            append("\n")
        }
        if (pullRequest.sourceBranch.isNotBlank() || pullRequest.targetBranch.isNotBlank()) {
            append("Branches: ")
            append(pullRequest.sourceBranch)
            append(" -> ")
            append(pullRequest.targetBranch)
            append("\n")
        }
        if (notes.isNotBlank()) {
            append("\nReviewer's notes, in their own words, about this pull request:\n")
            append(notes.trim())
            append("\n")
        }
        append("\nEach line below is `line-number marker text`, where the marker is + for a line ")
        append("added, - for a line removed, and a space for a line of context that did not change.\n")
        append("\n")
        append(changes)
    }

    /**
     * Caps a diff at [limit], cutting on a line boundary.
     *
     * Cutting mid-hunk would leave the model a diff whose header claims lines it has not been
     * given, and a half-written `+` line is the one kind of truncation that makes a model
     * confidently review a line that does not exist.
     */
    internal fun truncateDiff(diff: String, limit: Int = MAX_DIFF_CHARACTERS): String {
        if (diff.length <= limit) return diff
        val lastNewline = diff.lastIndexOf('\n', startIndex = limit - 1)
        val kept = if (lastNewline > 0) diff.substring(0, lastNewline + 1) else diff.substring(0, limit)
        val omitted = diff.length - kept.length
        return kept +
            "\n... [truncated: $omitted of ${diff.length} characters of this diff were omitted to stay " +
            "inside the model's context window. Review what is above and say so in your answer.]"
    }

    /**
     * Pulls the answer out of a Chat Completions response.
     *
     * Three shapes are accepted because gateways disagree: the plain `message.content` string, a
     * content array of typed parts, and the older `choices[].text`. An error object is raised as a
     * failure rather than reported as an empty review, because "the gateway said your key is
     * wrong" arriving as a blank panel is the worst possible version of that answer.
     */
    internal fun extractReview(response: String): String {
        val root = try {
            JsonParser.parseString(response).asJsonObject
        } catch (exception: Exception) {
            throw PrReviewException("The LiteLLM gateway returned a response that is not JSON.", exception)
        }

        root.getAsJsonObject("error")
            ?.string("message")
            ?.takeIf { it.isNotBlank() }
            ?.let { throw PrReviewException(it) }

        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val message = choice?.getAsJsonObject("message")

        val text = when (val content = message?.get("content")) {
            null -> null
            is com.google.gson.JsonPrimitive -> content.asString
            is JsonArray -> content.joinToString("\n") { part ->
                if (part.isJsonObject) part.asJsonObject.string("text").orEmpty() else ""
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
            ?: choice?.string("text")?.takeIf { it.isNotBlank() }
            ?: throw PrReviewException("The model returned an empty review. Try again, or pick another model.")

        return stripWrappingFence(text)
    }

    /**
     * Unwraps a review that arrived wrapped in a single code fence.
     *
     * Models do this constantly when told to answer in Markdown, and leaving the fence in place
     * shows the user a backticked block that cannot be read. Only a fence around the *whole*
     * answer is removed, so fenced code inside the review survives.
     */
    internal fun stripWrappingFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.length < 6) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val infoString = trimmed.substring(3, firstNewline).trim()
        if (infoString.isNotEmpty() && !LANGUAGE_TAG.matches(infoString)) return trimmed
        val inner = trimmed.substring(firstNewline + 1, trimmed.length - 3).trim()
        return if (inner.isEmpty()) trimmed else inner
    }

    private val LANGUAGE_TAG = Regex("[A-Za-z0-9+#._-]{1,20}")

    private fun JsonObject.string(field: String): String? =
        get(field)?.takeIf { it.isJsonPrimitive }?.asString
}
