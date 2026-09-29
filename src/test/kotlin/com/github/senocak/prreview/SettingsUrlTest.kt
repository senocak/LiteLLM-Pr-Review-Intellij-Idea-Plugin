package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsUrlTest {
    @Test
    fun `a bare litellm host gains the version segment`() {
        // The README's own example of a working setup, in the shortest form it can be written.
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000"))
        assertEquals("https://gateway.company.com/v1", normalizeLiteLlmBaseUrl("https://gateway.company.com/"))
    }

    @Test
    fun `an existing path is left alone`() {
        // A gateway mounted on a sub-path may already carry its version segment, and inventing
        // one would break the configuration that works.
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000/v1"))
        assertEquals(
            "https://gateway.company.com/litellm/openai",
            normalizeLiteLlmBaseUrl("https://gateway.company.com/litellm/openai"),
        )
    }

    @Test
    fun `a pasted chat completions url reduces to its base`() {
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000/v1/chat/completions"))
        assertEquals("http://localhost:4000/v1", normalizeLiteLlmBaseUrl("http://localhost:4000/chat/completions"))
    }

    @Test
    fun `normalising twice changes nothing`() {
        // Applied on every load, so it has to be idempotent or the stored value would drift.
        val once = normalizeLiteLlmBaseUrl("http://localhost:4000/v1/chat/completions")
        assertEquals(once, normalizeLiteLlmBaseUrl(once))
    }

    @Test
    fun `an empty litellm url stays empty`() {
        // Appending "/v1" to nothing would make the field look configured when it is not.
        assertEquals("", normalizeLiteLlmBaseUrl(""))
        assertEquals("", normalizeLiteLlmBaseUrl("   "))
    }

    @Test
    fun `a bitbucket base url keeps its context path`() {
        assertEquals(
            "https://git.company.com/bitbucket",
            normalizeBitbucketBaseUrl("https://git.company.com/bitbucket/"),
        )
    }

    @Test
    fun `a pasted bitbucket api url reduces to its base`() {
        // Copying the endpoint out of the Bitbucket REST docs is the obvious thing to do, and
        // appending /rest/api/1.0 to it would produce a URL that 404s.
        assertEquals(
            "https://bitbucket.company.com",
            normalizeBitbucketBaseUrl("https://bitbucket.company.com/rest/api/1.0"),
        )
        assertEquals(
            "https://bitbucket.company.com",
            normalizeBitbucketBaseUrl("https://bitbucket.company.com/rest/api/1.0/"),
        )
    }

    @Test
    fun `a pasted scm url reduces to its base`() {
        assertEquals(
            "https://bitbucket.company.com",
            normalizeBitbucketBaseUrl("https://bitbucket.company.com/scm/PROJ/repo.git"),
        )
    }

    @Test
    fun `a pasted scm url behind a context path keeps it`() {
        assertEquals(
            "https://git.company.com/bitbucket",
            normalizeBitbucketBaseUrl("https://git.company.com/bitbucket/scm/PROJ/repo.git"),
        )
    }

    @Test
    fun `a host that merely starts with scm is not read as the scm path`() {
        // Matched as a whole path segment, so a Bitbucket Server mounted on scm.company.com is not
        // truncated away to a bare scheme.
        assertEquals("https://scm.company.com", normalizeBitbucketBaseUrl("https://scm.company.com"))
        assertEquals(
            "https://scm.company.com/bitbucket",
            normalizeBitbucketBaseUrl("https://scm.company.com/bitbucket/"),
        )
    }

    @Test
    fun `a path segment that merely contains scm is not read as the scm path`() {
        // "scm-tools" is a context path, not the Bitbucket repository marker, and truncating at it
        // would aim the REST calls at a path the server does not serve.
        assertEquals(
            "https://bitbucket.company.com/scm-tools",
            normalizeBitbucketBaseUrl("https://bitbucket.company.com/scm-tools/"),
        )
        assertEquals(
            "https://bitbucket.company.com/PROJ",
            normalizeBitbucketBaseUrl("https://bitbucket.company.com/PROJ"),
        )
    }

    @Test
    fun `an empty bitbucket url stays empty`() {
        assertEquals("", normalizeBitbucketBaseUrl(""))
        assertEquals("", normalizeBitbucketBaseUrl("  "))
    }
}

class PluginSettingsTest {
    @Test
    fun `a fully configured setup reports no problem`() {
        assertNull(
            PluginSettings(
                bitbucketBaseUrl = "https://bitbucket.company.com",
                bitbucketToken = "pat",
                litellmBaseUrl = "http://localhost:4000/v1",
                litellmApiKey = "",
                model = "claude-3-5-sonnet",
            ).problem(),
        )
    }

    @Test
    fun `a missing base url is named before anything else`() {
        val problem = PluginSettings("", "pat", "http://localhost:4000/v1", "", "gpt-4o").problem()
        assertEquals("Set the Bitbucket base URL in Settings | Tools | LiteLLM PR Review.", problem)
    }

    @Test
    fun `a missing token is only reported once the visible fields are filled in`() {
        // The token is the one setting the summary cannot check without a Password Safe read, so
        // it is the last word on the problem rather than the first.
        val problem = PluginSettings("https://bitbucket.company.com", "", "http://localhost:4000/v1", "", "gpt-4o").problem()
        assertEquals(
            "Save a Bitbucket personal access token in Settings | Tools | LiteLLM PR Review.",
            problem,
        )
    }

    @Test
    fun `the summary judges only the fields that cost nothing to read`() {
        assertEquals(false, PluginSettingsSummary("", "", "").ready)
        assertEquals(false, PluginSettingsSummary("https://b", "", "gpt-4o").ready)
        assertEquals(true, PluginSettingsSummary("https://b", "http://l/v1", "gpt-4o").ready)
    }

    @Test
    fun `the chat completions url is derived from the base`() {
        val settings = PluginSettings("https://b", "pat", "http://localhost:4000/v1", "", "gpt-4o")
        assertEquals("http://localhost:4000/v1/chat/completions", settings.litellmChatUrl)
    }
}
