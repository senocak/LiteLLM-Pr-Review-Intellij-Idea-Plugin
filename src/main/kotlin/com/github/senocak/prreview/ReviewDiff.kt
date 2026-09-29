package com.github.senocak.prreview

/**
 * Builds what the model is shown: the pull request's changes, with the line numbers they sit on.
 *
 * A unified diff is the wrong thing to review. Its context is the bulk of it, its `@@` headers
 * carry line numbers as ranges that a model has to count through to cite a single line, and a
 * reviewer asking "what did they write on line 42?" cannot answer it from one. So the change is
 * what is sent: every added and modified line, numbered, with a little of the code around it.
 *
 * The one thing that is never dropped is a changed line. When a pull request is too large for the
 * model's context, it is the unchanged lines around the changes that go first, and only a
 * pathological pull request loses changes — and says so in the text rather than in a truncation
 * marker the model can skim past.
 */
internal fun buildReviewDiff(
    files: List<DiffFile>,
    limit: Int = LiteLLMClient.MAX_DIFF_CHARACTERS,
): String {
    if (files.isEmpty()) return "This pull request has no file changes.\n"
    // Widest first, so the answer is the most complete one that fits. A small pull request never
    // loses a line of context and never notices the loop ran more than once.
    val candidates = listOf(FULL_CONTEXT, TIGHT_CONTEXT, NO_CONTEXT).map { render(files, it) }
    val fitting = candidates.firstOrNull { it.length <= limit }
    if (fitting != null) return fitting
    // Every changed line still does not fit, which is a pull request no reviewer reads end to end.
    // Keep the changes, say plainly what was left out.
    return LiteLLMClient.truncateDiff(candidates.last(), limit)
}

private const val FULL_CONTEXT = 6
private const val TIGHT_CONTEXT = 2
private const val NO_CONTEXT = 0

/** What a review of a diff can be, decided before any model is asked. */
internal sealed interface ReviewScope {
    /**
     * The diff could not be read. A model asked about it answers "no actionable findings", which
     * reads as a clean bill of health for a pull request nobody looked at.
     */
    data object Unreadable : ReviewScope

    /** Renames and mode changes only: no line of code moved, so there is nothing to look at. */
    data object NoCode : ReviewScope

    /** A review worth running, and how much of it there is. */
    data class Ready(val added: Int, val removed: Int, val files: Int) : ReviewScope
}

/**
 * What a review of these changes can honestly be.
 *
 * The size is not decoration. A reviewer reading "no actionable findings" needs to know whether
 * that means 4,000 lines were checked or 4, and the only way to tell is to be told.
 */
internal fun reviewScopeOf(files: List<DiffFile>): ReviewScope {
    if (files.isEmpty()) return ReviewScope.Unreadable
    val added = files.sumOf { it.additions }
    val removed = files.sumOf { it.deletions }
    if (added == 0 && removed == 0) return ReviewScope.NoCode
    return ReviewScope.Ready(added, removed, files.size)
}

internal fun ReviewScope.describe(): String = when (this) {
    ReviewScope.Unreadable ->
        "The diff came back in a form this plugin cannot read, so there was nothing to review. " +
            "The Changes tab shows what the server actually returned."
    ReviewScope.NoCode ->
        "This pull request renames or moves files without changing a line of code, so there is " +
            "nothing for a review to look at."
    is ReviewScope.Ready -> "$added added, $removed removed · ${files.plural("file")}"
}

private fun Int.plural(word: String) = if (this == 1) "1 $word" else "$this ${word}s"

/** Which copy of a pull request's diff is being asked for. */
internal data class DiffKey(val id: Int, val updatedOn: String?)

/**
 * The diff currently on screen, kept so that the review reads the same bytes rather than
 * downloading them again.
 *
 * One slot, not a list of recent ones, and that is the whole point. A tool window shows one pull
 * request at a time, so a second slot has nobody to serve. A list of recent diffs, keyed by pull
 * request number, would look generous and be a bug: a number is stable for the life of a pull
 * request, so it would happily serve a reviewer the diff they read *before* the commit they have
 * just pushed — and review that stale copy too. So selecting a pull request always downloads, and
 * this exists only so the review immediately afterwards costs one request instead of two.
 *
 * [updatedOn] is part of the key so that a refresh between reading the changes and pressing Review
 * is a miss rather than a review of the previous commit.
 */
internal class DisplayedDiff {
    private var key: DiffKey? = null
    private var diff: String? = null

    // Synchronized: a review reads this on a background thread while a selection writes to it.
    @Synchronized
    fun get(key: DiffKey): String? = if (this.key == key) diff else null

    @Synchronized
    fun put(key: DiffKey, diff: String) {
        this.key = key
        this.diff = diff
    }

    /** Dropped on a refresh, which is the reviewer saying the server has moved on. */
    @Synchronized
    fun clear() {
        key = null
        diff = null
    }
}

private fun render(files: List<DiffFile>, context: Int): String = buildString {
    append("Files changed: ")
    append(files.size)
    append("  ·  ")
    append("+")
    append(files.sumOf { it.additions })
    append(" −")
    append(files.sumOf { it.deletions })
    append("\n")
    files.forEach { file ->
        append("\n## ")
        append(file.path)
        if (file.oldPath != null && file.oldPath != file.path) {
            append(" (renamed from ")
            append(file.oldPath)
            append(")")
        }
        append(" — ")
        append(labelOf(file.status))
        if (file.isBinary) {
            append("\nBinary file: changed, but it has no textual lines to review.\n")
            return@forEach
        }
        append(", +")
        append(file.additions)
        append(" −")
        append(file.deletions)
        append("\n")
        // A rename or a binary file has no lines at all, and there is no width to compute from.
        val width = file.hunks
            .flatMap { hunk -> hunk.lines }
            .maxOfOrNull { maxOf(it.oldLine ?: 0, it.newLine ?: 0) }
            ?.toString()
            ?.length
            ?: 1
        file.hunks.forEach { hunk -> appendHunk(hunk, context, width) }
    }
}

private fun StringBuilder.appendHunk(hunk: DiffHunk, context: Int, width: Int) {
    val lines = hunk.lines
    if (lines.isEmpty()) return
    val kept = keepWithin(lines, context)
    if (kept.none { it }) return
    if (hunk.heading.isNotEmpty()) {
        append("\n@@ ")
        append(hunk.heading)
        append(" @@\n")
    }
    var previousKept = false
    kept.forEachIndexed { index, isKept ->
        if (isKept) {
            if (!previousKept && index > 0) append("   ...\n")
            val line = lines[index]
            val number = line.newLine ?: line.oldLine
            val marker = when (line.kind) {
                DiffLineKind.ADDITION -> "+"
                DiffLineKind.DELETION -> "-"
                DiffLineKind.META -> "\\"
                else -> " "
            }
            if (line.kind == DiffLineKind.META) {
                // The "no newline at end of file" marker belongs to the line above it and has no
                // number of its own.
                append("      ")
                append(line.text.trimStart('\\'))
                append("\n")
            } else {
                append(number.toString().padStart(width))
                append(" ")
                append(marker)
                append(line.text.drop(1))
                append("\n")
            }
        }
        previousKept = isKept
    }
}

/**
 * Which lines survive: every change, plus [context] lines of context around each one.
 *
 * Deletions count as changes even though there is nothing to suggest for them, because the code a
 * deletion removed is often exactly what makes the addition around it hard to judge.
 */
private fun keepWithin(lines: List<DiffLine>, context: Int): BooleanArray {
    val isChange = BooleanArray(lines.size) { index ->
        lines[index].kind != DiffLineKind.CONTEXT
    }
    if (context == NO_CONTEXT) return isChange
    val kept = BooleanArray(lines.size)
    isChange.forEachIndexed { index, changed ->
        if (!changed) return@forEachIndexed
        val from = maxOf(0, index - context)
        val to = minOf(lines.lastIndex, index + context)
        for (near in from..to) kept[near] = true
    }
    return kept
}
