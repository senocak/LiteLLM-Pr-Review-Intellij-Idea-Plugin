package com.github.senocak.prreview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitbucketResponseParsingTest {
    private val PAGE = """
        {
          "size": 2,
          "limit": 50,
          "isLastPage": true,
          "values": [
            {
              "id": 42,
              "title": "Fix the thing",
              "state": "OPEN",
              "author": { "name": "jdoe", "displayName": "Jane Doe" },
              "fromRef": { "displayId": "feature/thing" },
              "toRef": { "displayId": "main" },
              "links": { "self": [ { "href": "https://bitbucket.company.com/projects/PROJ/repos/repo/pull-requests/42" } ] }
            },
            {
              "id": 43,
              "title": "Another one",
              "author": { "name": "asmith" },
              "fromRef": { "displayId": "feature/other" },
              "toRef": { "displayId": "main" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a page of pull requests is read in full`() {
        val pullRequests = BitbucketServerClient.parsePullRequests(PAGE)
        assertEquals(2, pullRequests.size)
        assertEquals(42, pullRequests[0].id)
        assertEquals("Fix the thing", pullRequests[0].title)
        assertEquals("Jane Doe", pullRequests[0].author)
        assertEquals("feature/thing", pullRequests[0].sourceBranch)
        assertEquals("main", pullRequests[0].targetBranch)
        assertEquals(
            "https://bitbucket.company.com/projects/PROJ/repos/repo/pull-requests/42",
            pullRequests[0].link,
        )
    }

    @Test
    fun `a pull request with only a login still has an author to show`() {
        // displayName is optional in the API; showing nothing at all would make two rows identical.
        assertEquals("asmith", BitbucketServerClient.parsePullRequests(PAGE)[1].author)
    }

    @Test
    fun `a missing link is left absent rather than invented`() {
        // The "Open in browser" affordance is built from this, so a fabricated URL would open
        // something other than the pull request.
        assertNull(BitbucketServerClient.parsePullRequests(PAGE)[1].link)
    }

    @Test
    fun `an untitled pull request still renders a row`() {
        val page = """{"values":[{"id":7,"title":"","author":{},"fromRef":{},"toRef":{}}]}"""
        val pullRequest = BitbucketServerClient.parsePullRequests(page).single()
        assertEquals("(no title)", pullRequest.title)
        assertEquals("", pullRequest.author)
        assertEquals("", pullRequest.sourceBranch)
    }

    @Test
    fun `when the pull request last changed is read off the wire`() {
        // This is what tells the plugin a diff it already has belongs to a commit that has since
        // been superseded, so a missing timestamp here is a silent staleness bug.
        val page = """{"values":[{"id":7,"title":"t","updatedOn":"2026-09-28T10:05:00.123456+0300"}]}"""
        assertEquals("2026-09-28T10:05:00.123456+0300", BitbucketServerClient.parsePullRequests(page).single().updatedOn)
    }

    @Test
    fun `a pull request with no timestamp is left absent rather than invented`() {
        val page = """{"values":[{"id":7,"title":"t"}]}"""
        assertNull(BitbucketServerClient.parsePullRequests(page).single().updatedOn)
    }

    @Test
    fun `an entry with no id is skipped rather than half-built`() {
        // The id is the only handle the diff call has, so an entry without one is unusable.
        val page = """{"values":[{"title":"no id"},{"id":9,"title":"has an id"}]}"""
        assertEquals(listOf(9), BitbucketServerClient.parsePullRequests(page).map { it.id })
    }

    @Test
    fun `an empty page is an empty list, not a failure`() {
        assertEquals(emptyList<PullRequest>(), BitbucketServerClient.parsePullRequests("""{"values":[]}"""))
        assertEquals(emptyList<PullRequest>(), BitbucketServerClient.parsePullRequests("""{"size":0}"""))
    }

    @Test
    fun `the server's own last-page flag is honoured`() {
        assertTrue(BitbucketServerClient.isLastPage("""{"isLastPage":true,"values":[{"id":1}]}"""))
        assertTrue(!BitbucketServerClient.isLastPage("""{"isLastPage":false,"values":[{"id":1}]}"""))
    }

    @Test
    fun `an absent flag falls back to the emptiness of the page`() {
        // Older Bitbucket versions omit isLastPage, where a short page is the end of the list.
        assertTrue(BitbucketServerClient.isLastPage("""{"values":[]}"""))
        assertTrue(!BitbucketServerClient.isLastPage("""{"values":[{"id":1}]}"""))
    }

    @Test
    fun `a response that is not JSON fails loudly`() {
        // An HTML login page from a proxy that swallowed the Authorization header looks exactly
        // like this, and it must not be read as a page of pull requests.
        val thrown = runCatching { BitbucketServerClient.parsePullRequests("<html>Sign in</html>") }
        assertTrue(thrown.exceptionOrNull() is PrReviewException)
    }

    @Test
    fun `the pull request list url carries the state and the paging window`() {
        val coordinates = BitbucketCoordinates("host", "PROJ", "repo", "origin", "url")
        assertEquals(
            "https://b/rest/api/1.0/projects/PROJ/repos/repo/pull-requests?state=OPEN&limit=50&start=0",
            BitbucketServerClient.pullRequestsUrl("https://b", coordinates, 0),
        )
        assertTrue(
            BitbucketServerClient.pullRequestsUrl("https://b/", coordinates, 100).endsWith("&start=100"),
        )
    }

    @Test
    fun `the diff url points at the one-shot diff endpoint`() {
        val coordinates = BitbucketCoordinates("host", "PROJ", "repo", "origin", "url")
        assertEquals(
            "https://b/rest/api/1.0/projects/PROJ/repos/repo/pull-requests/42.diff",
            BitbucketServerClient.diffUrl("https://b", coordinates, 42),
        )
    }

    @Test
    fun `a repository name needing encoding is encoded as a path segment`() {
        // A space has to become %20 and not "+", which is a literal plus in a path: the form
        // encoder would produce a request for a repository that does not exist.
        val coordinates = BitbucketCoordinates("host", "MY PROJ", "my repo", "origin", "url")
        val url = BitbucketServerClient.pullRequestsUrl("https://b", coordinates, 0)
        assertTrue(url.contains("/projects/MY%20PROJ/repos/my%20repo/"))
        assertTrue(!url.contains("+"))
    }
}

class LiteLLMResponseTest {
    /** Built with Gson so the test says nothing about how Kotlin quotes a string. */
    private fun completion(content: String): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            add("content", JsonPrimitive(content))
        }
        val choice = JsonObject().apply { add("message", message) }
        return JsonObject()
            .apply { add("choices", JsonArray().apply { add(choice) }) }
            .toString()
    }

    @Test
    fun `a plain string content is the answer`() {
        assertEquals("Looks good.", LiteLLMClient.extractReview(completion("Looks good.")))
        assertEquals("Contains a \"quote\" and a \\ backslash.", LiteLLMClient.extractReview(completion("Contains a \"quote\" and a \\ backslash.")))
    }

    @Test
    fun `a typed content array is joined`() {
        // Reasoning models behind a gateway return the OpenAI shape, not a bare string.
        val response = """
            {"choices":[{"message":{"content":[
              {"type":"text","text":"One."},
              {"type":"text","text":"Two."}
            ]}}]}
        """.trimIndent()
        assertEquals("One.\nTwo.", LiteLLMClient.extractReview(response))
    }

    @Test
    fun `the older completions shape is accepted`() {
        assertEquals("Hi", LiteLLMClient.extractReview("""{"choices":[{"text":"Hi"}]}"""))
    }

    @Test
    fun `an error object is raised rather than shown as an empty review`() {
        // A gateway saying the key is wrong must not arrive as a blank panel, which looks
        // identical to a model that had nothing to say.
        val thrown = runCatching { LiteLLMClient.extractReview("""{"error":{"message":"no key provided"}}""") }
        assertEquals("no key provided", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `an empty answer is a failure, not a blank review`() {
        val thrown = runCatching { LiteLLMClient.extractReview(completion("")) }
        assertTrue(thrown.exceptionOrNull() is PrReviewException)
    }

    @Test
    fun `a response that is not JSON fails loudly`() {
        assertTrue(runCatching { LiteLLMClient.extractReview("not json") }.exceptionOrNull() is PrReviewException)
    }

    @Test
    fun `a whole-answer code fence is unwrapped`() {
        // Models wrap Markdown answers in a fence constantly when told to use Markdown, and
        // leaving it in place shows the user a backticked block they cannot read.
        val fenced = "```markdown\n## Findings\n\n- **Blocking** one thing.\n```"
        assertEquals("## Findings\n\n- **Blocking** one thing.", LiteLLMClient.stripWrappingFence(fenced))
    }

    @Test
    fun `fenced code inside a review survives`() {
        val review = "Here is the fix:\n\n```kotlin\nval x = 1\n```\n\nThat is all."
        assertEquals(review, LiteLLMClient.stripWrappingFence(review))
    }

    @Test
    fun `an unterminated fence is left as written`() {
        val review = "```kotlin\nval x = 1"
        assertEquals(review, LiteLLMClient.stripWrappingFence(review))
    }
}

class DiffTruncationTest {
    private fun diffOf(size: Int) = buildString {
        while (length < size) append("+a line of the diff\n")
    }

    @Test
    fun `a diff under the cap is untouched`() {
        val diff = diffOf(1_000)
        assertEquals(diff, LiteLLMClient.truncateDiff(diff))
    }

    @Test
    fun `a diff over the cap is cut on a line boundary`() {
        // Cutting mid-hunk would leave the model a diff header claiming lines it was never given,
        // and a trailing partial line reads as a corrupted hunk rather than as a short one.
        val diff = diffOf(5_000)
        val truncated = LiteLLMClient.truncateDiff(diff, limit = 1_000)
        val body = truncated.substringBefore("\n... [truncated:")
        assertTrue("body should end on a line boundary", body.endsWith("\n"))
        assertTrue("body should be a prefix of the original diff", diff.startsWith(body))
    }

    @Test
    fun `the model is told the diff was cut`() {
        // Silently truncating is how a model ends up reviewing one file of a change and pronouncing
        // the whole pull request fine.
        val truncated = LiteLLMClient.truncateDiff(diffOf(5_000), limit = 1_000)
        assertTrue(truncated.contains("[truncated:"))
    }

    @Test
    fun `an untruncated diff carries no truncation note`() {
        // A note on a whole diff is a false statement to the model.
        assertTrue(!LiteLLMClient.truncateDiff(diffOf(500)).contains("[truncated:"))
    }

    @Test
    fun `the cap is well inside a modern context window`() {
        // ~15k tokens. The point of the guard is to stay under a small model, not to send
        // everything the server will accept.
        assertTrue(LiteLLMClient.MAX_DIFF_CHARACTERS <= 60_000)
    }

    @Test
    fun `a diff with no line breaks still respects the cap`() {
        val truncated = LiteLLMClient.truncateDiff("x".repeat(500), limit = 100)
        assertTrue(truncated.startsWith("x".repeat(100)))
        assertTrue(truncated.contains("[truncated:"))
    }
}
