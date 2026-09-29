package com.github.senocak.prreview

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * Everything a Bitbucket Server REST URL is built from, plus the remote it was read out of.
 *
 * [host] is kept separately from [projectKey] and [repoSlug] because the host is only ever shown
 * to the user: the API paths are relative to the configured base URL, which is what an
 * on-premise install behind a reverse proxy or a context path actually needs.
 */
data class BitbucketCoordinates(
    val host: String,
    val projectKey: String,
    val repoSlug: String,
    val remoteName: String,
    val remoteUrl: String,
) {
    /** `PROJECT / repo` — how a Bitbucket user names a repository. */
    val displayName: String get() = "$projectKey / $repoSlug"

    /** Shown when a remote points at a host other than the configured base URL. */
    fun differsFrom(configuredBaseUrl: String): Boolean {
        val configuredHost = hostOf(configuredBaseUrl) ?: return false
        return !configuredHost.equals(host, ignoreCase = true)
    }
}

/** The three values parsed out of one remote URL, before the remote it belongs to is known. */
data class BitbucketRemote(val host: String, val projectKey: String, val repoSlug: String)

/** One `remote` section of a `.git/config`, as read from disk or from Git4Idea's own index. */
data class GitRemoteRef(val name: String, val url: String)

/**
 * `git@host:PROJECT/repo.git` and the `host:path` form without a user.
 *
 * The negative lookahead on `//` is what keeps a real URL out of this branch: in
 * `ssh://git@host:7999/PROJ/repo.git` the `[^@/]+@` group cannot match, because `/` appears
 * before the `@`, and the port is preceded by a `//` that the lookahead rejects. Those two
 * together are why the scp-like branch only ever sees the colon-as-separator syntax.
 */
private val SCP_LIKE_URL = Regex("""^(?:[^@/\s]+@)?([^/:\s]+):(?!//)(.+)$""")

private val REMOTE_SECTION = Regex("""^\[remote\s+"([^"]+)"\s*]$""", RegexOption.IGNORE_CASE)

private const val GIT_EXTENSION = ".git"

/**
 * Reads a clone URL apart into host, project key and repository slug.
 *
 * Bitbucket Server hands out two shapes and this has to survive both, plus the context path an
 * on-premise install can sit behind:
 *
 * - SSH: `ssh://git@bitbucket.company.com:7999/PROJECT_KEY/repo-slug.git` and its scp-like
 *   shorthand `git@bitbucket.company.com:PROJECT_KEY/repo-slug.git`
 * - HTTPS: `https://bitbucket.company.com/scm/PROJECT_KEY/repo-slug.git`, older installs and
 *   context paths simply add segments in front of `/scm/`
 *
 * Rather than pattern-matching each documented form, the path is split into segments and the pair
 * after `/scm/` is preferred, falling back to the last two segments for the SSH form that has no
 * `/scm/`. Anything with fewer than two segments is not a repository URL.
 *
 * @return the parsed coordinates, or null when the URL is not a Bitbucket clone URL — a GitHub or
 *   a plain filesystem remote, both of which the user needs told about rather than guessed at.
 */
internal fun parseBitbucketRemote(url: String): BitbucketRemote? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null

    val scp = SCP_LIKE_URL.matchEntire(trimmed)
    val host: String
    val path: String
    if (scp != null) {
        host = scp.groupValues[1]
        path = scp.groupValues[2]
    } else {
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        // A missing host rules out file:// and other non-network remotes outright.
        val uriHost = uri.host ?: return null
        if (uri.scheme == null || uriHost.isEmpty()) return null
        host = uriHost
        path = uri.path.orEmpty()
    }

    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null

    val scmIndex = segments.indexOfLast { it.equals("scm", ignoreCase = true) }
    val projectKey: String
    val repoSlug: String
    if (scmIndex >= 0) {
        // A `/scm` with fewer than two segments behind it names no repository, and falling back
        // to the last two would read the marker itself as the project key.
        if (segments.size - scmIndex < 3) return null
        projectKey = segments[scmIndex + 1]
        repoSlug = segments[scmIndex + 2]
    } else {
        projectKey = segments[segments.size - 2]
        repoSlug = segments[segments.size - 1]
    }

    val slug = repoSlug.removeGitExtension()
    if (projectKey.isEmpty() || slug.isEmpty()) return null
    return BitbucketRemote(host, projectKey, slug)
}

/** Pulls the `remote` sections out of a `.git/config`. */
internal fun parseRemoteRefs(configText: String): List<GitRemoteRef> {
    val refs = mutableListOf<GitRemoteRef>()
    var currentRemote: String? = null
    for (rawLine in configText.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[")) {
            // Any other section ends the remote: a [branch] section's own `url`-less keys must
            // not be attributed to the remote above it.
            currentRemote = REMOTE_SECTION.matchEntire(line)?.groupValues?.get(1)
            continue
        }
        val remote = currentRemote ?: continue
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        if (line.substring(0, separator).trim().lowercase() != "url") continue
        refs += GitRemoteRef(remote, line.substring(separator + 1).trim().unquote())
    }
    return refs
}

/**
 * `origin` first, then `upstream`, then everything else in file order.
 *
 * A clone has one of those two remotes pointed at the server a reviewer is expected to use;
 * forks have `origin` pointing the other way, and the fallback remote is the one that actually
 * has the pull requests. `sortedBy` is stable, so within a tier the config order is kept.
 */
internal fun prioritizeRemotes(refs: List<GitRemoteRef>): List<GitRemoteRef> =
    refs.sortedBy { when (it.name.lowercase()) { "origin" -> 0; "upstream" -> 1; else -> 2 } }

private fun String.removeGitExtension(): String =
    if (endsWith(GIT_EXTENSION, ignoreCase = true)) dropLast(GIT_EXTENSION.length) else this

private fun String.unquote(): String =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

/**
 * Works out which Bitbucket Server repository the open project is standing in.
 *
 * Git4Idea supplies the repository list and, per repository, the `config` file that belongs to
 * it — which is not always `<root>/.git/config`, because a linked worktree keeps its remotes in
 * the main repository. The remotes are read out of that file rather than out of Git4Idea's own
 * index, because it is the file the user goes and looks at when the detection goes wrong, and the
 * plugin needs to be able to quote it back at them verbatim.
 */
object GitConfigDetector {
    private val LOG = Logger.getInstance(GitConfigDetector::class.java)

    /**
     * @param preferredHost host of the configured Bitbucket base URL, if there is one. A clone URL
     *   carries no sign of which forge it points at — `git@github.com:torvalds/linux.git` has
     *   exactly the shape of a Bitbucket SSH remote — so the host the user has configured is the
     *   only thing that can tell the two apart. Remotes on it are tried first; the rest are still
     *   tried afterwards, because an on-premise server is often reached over SSH while the
     *   configured base URL is an HTTPS reverse proxy, and those disagree on the host.
     * @return the first remote that looks like a Bitbucket clone URL, or null when the project has
     *   no Bitbucket remote at all. Null is a normal outcome, not a failure: the caller reports it
     *   along with [remoteRefs] so the user can see what the project actually has configured.
     */
    fun detect(project: Project, preferredHost: String? = null): BitbucketCoordinates? {
        val refs = remoteRefs(project)
        val matching = if (preferredHost.isNullOrEmpty()) {
            emptyList()
        } else {
            refs.filter { ref -> parseBitbucketRemote(ref.url)?.host.equals(preferredHost, ignoreCase = true) }
        }
        return (matching + refs).distinct().firstNotNullOfOrNull { ref -> ref.toCoordinates() }
    }

    /** Every remote the project has, most likely one first. */
    fun remoteRefs(project: Project): List<GitRemoteRef> {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val fromConfig = repositories.flatMap { readConfigRefs(it) }
        // A registered repository whose config cannot be read still has remotes in Git4Idea's own
        // index. Same data, different source, so it is worth falling back to rather than reporting
        // the project as having no remotes.
        val refs = if (fromConfig.isNotEmpty()) {
            fromConfig
        } else {
            repositories.flatMap { repository ->
                repository.remotes.flatMap { remote -> remote.urls.map { GitRemoteRef(remote.name, it) } }
            }
        }
        return prioritizeRemotes(refs)
    }

    /** True when the project is under Git at all, which is a different failure to "no Bitbucket remote". */
    fun hasGitRepository(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    private fun readConfigRefs(repository: GitRepository): List<GitRemoteRef> {
        // GitRepositoryFiles resolves the config of a linked worktree to the main repository's,
        // which is the case a plain "<root>/.git/config" read gets wrong.
        val fromConfig = readConfig(repository.repositoryFiles.configFile)
        if (fromConfig.isNotEmpty()) return fromConfig
        return repository.remotes.flatMap { remote -> remote.urls.map { GitRemoteRef(remote.name, it) } }
    }

    private fun readConfig(configFile: File): List<GitRemoteRef> = try {
        parseRemoteRefs(configFile.readText())
    } catch (exception: IOException) {
        LOG.debug("Could not read $configFile", exception)
        emptyList()
    }

    private fun GitRemoteRef.toCoordinates(): BitbucketCoordinates? {
        val parsed = parseBitbucketRemote(url) ?: return null
        return BitbucketCoordinates(parsed.host, parsed.projectKey, parsed.repoSlug, name, url)
    }
}
