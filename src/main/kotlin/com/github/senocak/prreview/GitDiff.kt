package com.github.senocak.prreview

/** What happened to a file in a pull request, as far as the diff says. */
internal enum class DiffStatus { ADDED, DELETED, RENAMED, MODIFIED, BINARY }

internal enum class DiffLineKind { CONTEXT, ADDITION, DELETION, META }

/**
 * One line of a hunk, with the line number it has on each side.
 *
 * The numbers are what a reviewer's comment is anchored to, so they are counted here rather than
 * left to the reader. Counting is the only way they stay right across a file with several hunks,
 * where the line a diff shows as `+42` is not the 42nd line of the hunk.
 */
internal data class DiffLine(
    val kind: DiffLineKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

internal data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    /** The text after the closing `@@`, which is the enclosing function or class in most diffs. */
    val heading: String,
    val lines: List<DiffLine>,
)

internal data class DiffFile(
    /** The path after the change: the new name for a rename, the only name otherwise. */
    val path: String,
    /** The path before the change. Set for a rename and for a delete. */
    val oldPath: String? = null,
    val status: DiffStatus = DiffStatus.MODIFIED,
    val hunks: List<DiffHunk> = emptyList(),
) {
    val additions: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLineKind.ADDITION } }
    val deletions: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffLineKind.DELETION } }
    val isBinary: Boolean get() = status == DiffStatus.BINARY
}

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

private const val GIT_HEADER_PREFIX = "diff --git "
private const val DEV_NULL = "/dev/null"

/**
 * Parses a unified diff into files, hunks and lines.
 *
 * This is the format Bitbucket's `.diff` endpoint returns, and the one `git diff` produces. It is
 * parsed rather than shown as a wall of text because both a reviewer and the model need to know
 * where one file ends and the next begins, and because a file list is the only way to see at a
 * glance what a pull request actually touches.
 *
 * Every part of the format that carries meaning is read: the `new file`, `deleted file` and
 * `rename` markers, `/dev/null` standing in for one side, and the `Binary files … differ` line.
 *
 * A hunk is ended by its own counts being met rather than by the next line looking like a header,
 * because a deleted line whose content starts with `--` is `--- …` and is indistinguishable from a
 * file header by shape alone. The counts are the only thing that tells the two apart.
 */
internal fun parseUnifiedDiff(diff: String): List<DiffFile> = DiffParser().parse(diff)

private class DiffParser {
    private val files = mutableListOf<FileBuilder>()
    private var file: FileBuilder? = null
    private var hunk: HunkBuilder? = null

    fun parse(diff: String): List<DiffFile> {
        diff.lineSequence().forEach { accept(it) }
        return files.map { it.build() }
    }

    private fun accept(line: String) {
        if (line.startsWith(GIT_HEADER_PREFIX)) {
            startFile { parseGitHeader(line, this) }
            return
        }
        val current = file
        if (current == null) {
            // A patch with no `diff --git` header — `diff -u`, or a hand-trimmed one — still has to
            // produce a file, and the `---` line is where it starts.
            if (line.startsWith("--- ")) startFile { applyHeaderLine(line, this) }
            return
        }
        val hunkHeader = HUNK_HEADER.matchEntire(line)
        if (hunkHeader != null) {
            hunk = HunkBuilder(
                oldStart = hunkHeader.groupValues[1].toInt(),
                oldCount = hunkHeader.groupValues[2].toIntOrNull() ?: 1,
                newStart = hunkHeader.groupValues[3].toInt(),
                newCount = hunkHeader.groupValues[4].toIntOrNull() ?: 1,
                heading = hunkHeader.groupValues[5].trim(),
            )
            current.hunks += hunk!!
            return
        }
        val active = hunk
        if (active != null) {
            val kind = kindOf(line)
            when {
                // Still inside the hunk by its own counts: whatever this line looks like, it is
                // content. `---` and `+++` included.
                kind != null && !active.isComplete() -> {
                    active.lines += active.add(line, kind)
                    return
                }
                // The counts are met, so this is a marker that follows the last line, not content.
                kind == DiffLineKind.META -> {
                    active.lines += active.add(line, kind)
                    return
                }
            }
            hunk = null
        }
        applyHeaderLine(line, current)
    }

    private fun startFile(configure: FileBuilder.() -> Unit) {
        val builder = FileBuilder().apply(configure)
        files += builder
        file = builder
        hunk = null
    }

    private fun applyHeaderLine(line: String, into: FileBuilder) {
        when {
            line.startsWith("new file mode ") -> into.status = DiffStatus.ADDED
            line.startsWith("deleted file mode ") -> into.status = DiffStatus.DELETED
            line.startsWith("rename from ") -> {
                into.oldPath = line.removePrefix("rename from ").trim().unquotePath()
                into.status = DiffStatus.RENAMED
            }
            line.startsWith("rename to ") -> {
                into.path = line.removePrefix("rename to ").trim().unquotePath()
                into.status = DiffStatus.RENAMED
            }
            line.startsWith("Binary files ") || line.startsWith("GIT binary patch") ->
                into.status = DiffStatus.BINARY
            line.startsWith("--- ") -> {
                val path = line.removePrefix("--- ").trim().unquotePath()
                // A new file has nothing on the old side, which is how a bare `---` and `+++`
                // pair is told from a file whose content happens to be empty.
                if (path == DEV_NULL) into.status = DiffStatus.ADDED else into.oldPath = path.removePrefix("a/")
            }
            line.startsWith("+++ ") -> {
                val path = line.removePrefix("+++ ").trim().unquotePath()
                if (path == DEV_NULL) {
                    into.status = DiffStatus.DELETED
                    // A deleted file is only named on the `---` side.
                    if (into.path.isEmpty()) into.path = into.oldPath.orEmpty()
                } else {
                    into.path = path.removePrefix("b/")
                }
            }
        }
    }

    private fun kindOf(line: String): DiffLineKind? = when {
        line.startsWith("+") -> DiffLineKind.ADDITION
        line.startsWith("-") -> DiffLineKind.DELETION
        line.startsWith("\\") -> DiffLineKind.META
        line.startsWith(" ") -> DiffLineKind.CONTEXT
        // A blank line with its leading space stripped. Hand-trimmed patches and a few servers
        // drop it, and counting it as anything but context would shift every line number after it.
        line.isEmpty() -> DiffLineKind.CONTEXT
        else -> null
    }
}

private class FileBuilder {
    var path: String = ""
    var oldPath: String? = null
    var status: DiffStatus = DiffStatus.MODIFIED
    val hunks: MutableList<HunkBuilder> = mutableListOf()

    fun build(): DiffFile = DiffFile(
        path = path.ifEmpty { oldPath.orEmpty() },
        oldPath = oldPath,
        status = status,
        hunks = hunks.map { it.build() },
    )
}

private class HunkBuilder(
    private val oldStart: Int,
    private val oldCount: Int,
    private val newStart: Int,
    private val newCount: Int,
    private val heading: String,
) {
    val lines: MutableList<DiffLine> = mutableListOf()
    private var oldLine = oldStart
    private var newLine = newStart

    /** True once the hunk header's line counts have both been satisfied. */
    fun isComplete(): Boolean = (oldLine - oldStart) >= oldCount && (newLine - newStart) >= newCount

    fun add(raw: String, kind: DiffLineKind): DiffLine = when (kind) {
        DiffLineKind.ADDITION -> DiffLine(kind, null, newLine++, raw)
        DiffLineKind.DELETION -> DiffLine(kind, oldLine++, null, raw)
        DiffLineKind.CONTEXT -> DiffLine(kind, oldLine++, newLine++, raw)
        // The "no newline at end of file" marker belongs to the line above it and occupies none.
        DiffLineKind.META -> DiffLine(kind, null, null, raw)
    }

    fun build(): DiffHunk = DiffHunk(oldStart, oldCount, newStart, newCount, heading, lines.toList())
}

/**
 * Reads the two paths out of a `diff --git` line.
 *
 * Split on the last ` b/` rather than on the first space: a path may contain spaces, and the `a/`
 * and `b/` prefixes are what make the separator findable. The `---`/`+++` pair that follows
 * corrects anything this gets wrong, so a mis-split costs nothing.
 */
private fun parseGitHeader(line: String, into: FileBuilder) {
    val rest = line.removePrefix(GIT_HEADER_PREFIX)
    val separator = rest.lastIndexOf(" b/")
    if (separator < 0) {
        into.path = rest.unquotePath()
        return
    }
    val old = rest.substring(0, separator).removePrefix("a/").unquotePath()
    val new = rest.substring(separator + 3).unquotePath()
    into.oldPath = old.takeIf { it.isNotEmpty() }
    into.path = new.ifEmpty { old }
}

/** Git quotes a path containing unusual characters, and backslash-escapes the quotes inside it. */
private fun String.unquotePath(): String {
    val trimmed = trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return trimmed
    return trimmed.substring(1, trimmed.length - 1)
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
