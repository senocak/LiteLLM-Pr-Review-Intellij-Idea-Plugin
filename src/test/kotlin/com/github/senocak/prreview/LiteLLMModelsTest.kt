package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLLMModelsTest {
    @Test
    fun `the openai data envelope is read`() {
        val response = """
            {
              "object": "list",
              "data": [
                { "id": "claude-3-5-sonnet", "object": "model", "owned_by": "anthropic" },
                { "id": "gpt-4o", "object": "model", "owned_by": "openai" }
              ]
            }
        """.trimIndent()
        assertEquals(listOf("claude-3-5-sonnet", "gpt-4o"), LiteLLMClient.parseModels(response))
    }

    @Test
    fun `the order the gateway sent is kept`() {
        // LiteLLM returns config.yaml aliases in declaration order, which is the order the user
        // chose. Sorting would bury the model they put first.
        val response = """{"data":[{"id":"zeta"},{"id":"alpha"},{"id":"mu"}]}"""
        assertEquals(listOf("zeta", "alpha", "mu"), LiteLLMClient.parseModels(response))
    }

    @Test
    fun `a bare array is accepted`() {
        // Some proxies answer /models with the array alone; the alternative is an empty drop-down
        // next to a gateway that plainly has models.
        assertEquals(listOf("gpt-4o"), LiteLLMClient.parseModels("""[{"id":"gpt-4o"}]"""))
    }

    @Test
    fun `an alias defined in config yaml comes through like any other model`() {
        val response = """{"data":[{"id":"team-reviewer"},{"id":"openai/gpt-4o"}]}"""
        assertEquals(listOf("team-reviewer", "openai/gpt-4o"), LiteLLMClient.parseModels(response))
    }

    @Test
    fun `a repeated id is collapsed`() {
        val response = """{"data":[{"id":"gpt-4o"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o"), LiteLLMClient.parseModels(response))
    }

    @Test
    fun `an entry with no id is skipped rather than shown as a blank choice`() {
        val response = """{"data":[{"id":"gpt-4o"},{"object":"model"},{"id":""}]}"""
        assertEquals(listOf("gpt-4o"), LiteLLMClient.parseModels(response))
    }

    @Test
    fun `an entry that is a bare string is taken as the name`() {
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), LiteLLMClient.parseModels("""["gpt-4o","gpt-4o-mini"]"""))
    }

    @Test
    fun `an empty list is a legitimate answer`() {
        // A gateway with nothing configured returns one. Treating it as a failure would report a
        // broken endpoint for a gateway that is answering correctly.
        assertEquals(emptyList<String>(), LiteLLMClient.parseModels("""{"data":[]}"""))
        assertEquals(emptyList<String>(), LiteLLMClient.parseModels("""[]"""))
    }

    @Test
    fun `an error object is raised rather than shown as no models`() {
        // "no models" and "your key is wrong" must not both arrive as an empty drop-down.
        val thrown = runCatching { LiteLLMClient.parseModels("""{"error":{"message":"Authentication Error"}}""") }
        assertEquals("Authentication Error", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `a response with no model list at all fails loudly`() {
        val thrown = runCatching { LiteLLMClient.parseModels("""{"object":"list"}""") }
        assertTrue(thrown.exceptionOrNull() is PrReviewException)
    }

    @Test
    fun `a response that is not JSON fails loudly`() {
        // A proxy that requires a key answers 401, but one sitting behind a login portal answers
        // 200 with HTML, and that must not read as "this gateway has no models".
        val thrown = runCatching { LiteLLMClient.parseModels("<html>Sign in</html>") }
        assertTrue(thrown.exceptionOrNull() is PrReviewException)
    }

    @Test
    fun `the models url hangs off the same base as the chat url`() {
        val settings = PluginSettings("https://b", "pat", "http://localhost:4000/v1", "", "gpt-4o")
        assertEquals("http://localhost:4000/v1/models", settings.litellmModelsUrl)
        assertEquals("http://localhost:4000/v1/chat/completions", settings.litellmChatUrl)
    }

    @Test
    fun `a base url typed without a version is normalised before it is called`() {
        // The same normalisation the chat call relies on, or the list comes from a different
        // place than the reviews do.
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000"))
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000/v1/"))
    }
}
