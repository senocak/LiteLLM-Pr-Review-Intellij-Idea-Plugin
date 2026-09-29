package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitbucketRemoteUrlTest {
    private fun parse(url: String) = parseBitbucketRemote(url)

    private fun assertCoordinates(url: String, host: String, projectKey: String, repoSlug: String) {
        val parsed = parse(url)
        assertEquals(url, BitbucketRemote(host, projectKey, repoSlug), parsed)
    }

    @Test
    fun `an ssh url with a port`() {
        assertCoordinates(
            "ssh://git@bitbucket.company.com:7999/PROJECT_KEY/repo-slug.git",
            "bitbucket.company.com", "PROJECT_KEY", "repo-slug",
        )
    }

    @Test
    fun `the scp-like ssh shorthand`() {
        // The form `git clone git@host:project/repo.git` produces, and the one most teams have
        // actually pasted into a .git/config.
        assertCoordinates(
            "git@bitbucket.company.com:PROJECT_KEY/repo-slug.git",
            "bitbucket.company.com", "PROJECT_KEY", "repo-slug",
        )
    }

    @Test
    fun `an https url through the scm path`() {
        assertCoordinates(
            "https://bitbucket.company.com/scm/PROJECT_KEY/repo-slug.git",
            "bitbucket.company.com", "PROJECT_KEY", "repo-slug",
        )
    }

    @Test
    fun `an https url behind a context path`() {
        // On-premise installs are routinely mounted under a path, which puts extra segments in
        // front of /scm/ and defeats anything that assumed "<host>/<project>/<repo>".
        assertCoordinates(
            "https://git.company.com/bitbucket/scm/PROJ/repo-slug.git",
            "git.company.com", "PROJ", "repo-slug",
        )
    }

    @Test
    fun `an https url carrying credentials`() {
        // A remote left over from an HTTPS-with-token clone: the userinfo must not be mistaken for
        // the host, or the API call is aimed at a host that does not exist.
        assertCoordinates(
            "https://user:glpat-x@bitbucket.company.com/scm/PROJ/repo-slug.git",
            "bitbucket.company.com", "PROJ", "repo-slug",
        )
    }

    @Test
    fun `a github remote parses to the same shape as a bitbucket one`() {
        // Deliberate: a clone URL says nothing about which forge it points at, and `git@github.com:
        // torvalds/linux.git` has exactly the shape of a Bitbucket SSH remote. Rejecting it here
        // would be a guess. Telling the two apart is what the configured base URL is for, in
        // GitConfigDetector.detect, which prefers a remote on that host.
        assertCoordinates("git@github.com:torvalds/linux.git", "github.com", "torvalds", "linux")
    }

    @Test
    fun `the older http form with a port and no scm segment`() {
        assertCoordinates(
            "http://bitbucket.company.com:7990/PROJ/repo-slug.git",
            "bitbucket.company.com", "PROJ", "repo-slug",
        )
    }

    @Test
    fun `a repository named scm is not mistaken for the path segment`() {
        // "scm" is looked for from the right, so a repo that is itself called scm still resolves
        // to the pair before it.
        assertCoordinates(
            "https://bitbucket.company.com/scm/PROJ/scm.git",
            "bitbucket.company.com", "PROJ", "scm",
        )
    }

    @Test
    fun `surrounding whitespace and an uppercase extension are tolerated`() {
        assertCoordinates(
            "  ssh://git@bitbucket.company.com:7999/PROJ/repo-slug.GIT  ",
            "bitbucket.company.com", "PROJ", "repo-slug",
        )
    }

    @Test
    fun `a missing git extension is left alone`() {
        assertCoordinates(
            "https://bitbucket.company.com/scm/PROJ/repo-slug",
            "bitbucket.company.com", "PROJ", "repo-slug",
        )
    }

    @Test
    fun `a github remote is not a bitbucket remote`() {
        // Deliberate: a clone URL says nothing about which forge it points at, and `git@github.com:
        // torvalds/linux.git` has exactly the shape of a Bitbucket SSH remote. Rejecting it here
        // would be a guess. Telling the two apart is what the configured base URL is for, in
        // GitConfigDetector.detect, which prefers a remote on that host.
        assertCoordinates("git@github.com:torvalds/linux.git", "github.com", "torvalds", "linux")
    }

    @Test
    fun `a local path is not a remote`() {
        assertNull(parse("file:///srv/git/repo.git"))
        assertNull(parse("/srv/git/repo.git"))
    }

    @Test
    fun `an scm path with no project key names no repository`() {
        // Falling back to the last two segments here would read "scm" as the project key, and the
        // resulting REST call would 404 on a project that does not exist.
        assertNull(parse("https://bitbucket.company.com/scm/repo-slug.git"))
        assertNull(parse("https://bitbucket.company.com/scm"))
        assertNull(parse(""))
        assertNull(parse("   "))
    }
}
