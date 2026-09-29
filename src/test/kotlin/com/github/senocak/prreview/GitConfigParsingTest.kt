package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Test

class GitConfigParsingTest {
    private val ORIGIN_CONFIG = """
        [core]
        	repositoryformatversion = 0
        	filemode = true
        [remote "origin"]
        	url = git@bitbucket.company.com:PROJ/repo-slug.git
        	fetch = +refs/heads/*:refs/remotes/origin/*
        [branch "main"]
        	remote = origin
        	merge = refs/heads/main
    """.trimIndent()

    @Test
    fun `the origin url is found among unrelated config`() {
        assertEquals(
            listOf(GitRemoteRef("origin", "git@bitbucket.company.com:PROJ/repo-slug.git")),
            parseRemoteRefs(ORIGIN_CONFIG),
        )
    }

    @Test
    fun `a branch section's keys are not attributed to the remote above it`() {
        // "merge = refs/heads/main" sits under [branch "main"], not under a remote. A parser that
        // does not close the remote section on every header would look for a url key there.
        assertEquals(1, parseRemoteRefs(ORIGIN_CONFIG).size)
    }

    @Test
    fun `both a fork's origin and the upstream are collected`() {
        val config = """
            [remote "origin"]
            	url = git@bitbucket.company.com:my-fork/repo-slug.git
            [remote "upstream"]
            	url = https://bitbucket.company.com/scm/PROJ/repo-slug.git
        """.trimIndent()
        assertEquals(
            listOf(
                GitRemoteRef("origin", "git@bitbucket.company.com:my-fork/repo-slug.git"),
                GitRemoteRef("upstream", "https://bitbucket.company.com/scm/PROJ/repo-slug.git"),
            ),
            parseRemoteRefs(config),
        )
    }

    @Test
    fun `origin is preferred over upstream`() {
        // A fork is the common case and it points the wrong way: the remote carrying the pull
        // requests is the upstream, so origin must be tried and rejected before it is used.
        val refs = parseRemoteRefs(
            """
            [remote "upstream"]
            	url = git@bitbucket.company.com:PROJ/repo-slug.git
            [remote "origin"]
            	url = git@bitbucket.company.com:my-fork/repo-slug.git
            """.trimIndent(),
        )
        assertEquals("origin", prioritizeRemotes(refs).first().name)
    }

    @Test
    fun `unknown remotes keep their config order`() {
        val refs = listOf(GitRemoteRef("zeta", "z"), GitRemoteRef("alpha", "a"), GitRemoteRef("mid", "m"))
        assertEquals(listOf("zeta", "alpha", "mid"), prioritizeRemotes(refs).map { it.name })
    }

    @Test
    fun `a remote name is matched without regard to case`() {
        // Git accepts "Origin"; Bitbucket's own docs use "origin", and neither should be a
        // different remote as far as this is concerned.
        val refs = parseRemoteRefs("[remote \"Origin\"]\n\turl = git@host:PROJ/repo.git\n")
        assertEquals("origin", prioritizeRemotes(refs).single().name.lowercase())
    }

    @Test
    fun `a quoted url is unwrapped`() {
        val refs = parseRemoteRefs("[remote \"origin\"]\n\turl = \"git@host:PROJ/repo.git\"\n")
        assertEquals("git@host:PROJ/repo.git", refs.single().url)
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val config = """
            # a comment
            ; another comment

            [remote "origin"]
            	url = git@host:PROJ/repo.git
        """.trimIndent()
        assertEquals(listOf(GitRemoteRef("origin", "git@host:PROJ/repo.git")), parseRemoteRefs(config))
    }

    @Test
    fun `a remote with no url yields nothing rather than a blank entry`() {
        assertEquals(emptyList<GitRemoteRef>(), parseRemoteRefs("[remote \"origin\"]\n\tfetch = +refs/heads/*\n"))
    }

    @Test
    fun `a config with no remote section yields nothing`() {
        assertEquals(emptyList<GitRemoteRef>(), parseRemoteRefs("[core]\n\tbare = false\n"))
        assertEquals(emptyList<GitRemoteRef>(), parseRemoteRefs(""))
    }
}
