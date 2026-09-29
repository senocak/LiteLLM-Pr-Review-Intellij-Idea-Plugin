package com.github.senocak.prreview

/** How one row of a side-by-side diff came about. */
internal enum class RowKind {
    /** Unchanged: the same text on both sides. */
    CONTEXT,

    /** A line replaced by another: the two halves are the same edit. */
    CHANGED,

    /** A line only the new side has. */
    ADDED,

    /** A line only the old side has. */
    DELETED,
}

/** One line as one side of the diff shows it. */
internal data class DiffColumnRow(
    /** The line number on this side, or null where the side has no line at that row. */
    val line: Int? = null,
    /** The line's text without its diff marker, empty where the side has no line. */
    val text: String = "",
    val kind: RowKind,
    /** The characters that differ within a replaced line, so a one-character edit is visible. */
    val focus: IntRange? = null,
) {
    val isEmpty: Boolean get() = line == null
}

/**
 * A file's hunks laid out in two columns.
 *
 * The two lists are always the same length and correspond row for row, which is the whole point:
 * a reviewer comparing a change needs the old line and its replacement on the same row, not two
 * listings they have to read against each other.
 */
internal data class SideBySide(
    val path: String,
    val oldSide: List<DiffColumnRow>,
    val newSide: List<DiffColumnRow>,
) {
    val height: Int get() = oldSide.size

    /**
     * The width the line-number gutter needs, from the largest line number either side reaches.
     *
     * Computed once per file so both columns line up: a gutter that grew as the reader scrolled
     * would push the right-hand text sideways.
     */
    val gutterWidth: Int = maxOf(
        (oldSide.maxOfOrNull { it.line ?: 0 } ?: 0),
        (newSide.maxOfOrNull { it.line ?: 0 } ?: 0),
    ).toString().length.coerceAtLeast(MIN_GUTTER)

    private companion object {
        const val MIN_GUTTER = 3
    }
}

/**
 * Lays a file's hunks out in two columns.
 *
 * Deletions and additions are paired by position within a run of changes, which is what puts a
 * replaced line on one row. Pairing by content similarity would be smarter and is not done: a
 * reviewer asking why two lines were matched would have to trust an invisible heuristic, and
 * position within the run is what the diff already implies.
 */
internal fun sideBySide(file: DiffFile): SideBySide {
    val oldSide = mutableListOf<DiffColumnRow>()
    val newSide = mutableListOf<DiffColumnRow>()
    file.hunks.forEach { hunk ->
        val lines = hunk.lines.filter { it.kind != DiffLineKind.META }
        var index = 0
        while (index < lines.size) {
            if (lines[index].kind == DiffLineKind.CONTEXT) {
                val text = lines[index].text.removeMarker()
                oldSide += DiffColumnRow(lines[index].oldLine, text, RowKind.CONTEXT)
                newSide += DiffColumnRow(lines[index].newLine, text, RowKind.CONTEXT)
                index++
                continue
            }
            val deletions = mutableListOf<DiffLine>()
            val additions = mutableListOf<DiffLine>()
            while (index < lines.size && lines[index].kind != DiffLineKind.CONTEXT) {
                when (lines[index].kind) {
                    DiffLineKind.DELETION -> deletions += lines[index]
                    DiffLineKind.ADDITION -> additions += lines[index]
                    else -> Unit
                }
                index++
            }
            val paired = minOf(deletions.size, additions.size)
            for (position in 0 until paired) {
                val removed = deletions[position]
                val added = additions[position]
                val oldText = removed.text.removeMarker()
                val newText = added.text.removeMarker()
                val (oldFocus, newFocus) = changedCharacters(oldText, newText)
                oldSide += DiffColumnRow(removed.oldLine, oldText, RowKind.CHANGED, oldFocus)
                newSide += DiffColumnRow(added.newLine, newText, RowKind.CHANGED, newFocus)
            }
            // What is left over is a real insert or delete, not half of a replacement.
            deletions.drop(paired).forEach {
                oldSide += DiffColumnRow(it.oldLine, it.text.removeMarker(), RowKind.DELETED)
                newSide += DiffColumnRow(kind = RowKind.DELETED)
            }
            additions.drop(paired).forEach {
                oldSide += DiffColumnRow(kind = RowKind.ADDED)
                newSide += DiffColumnRow(it.newLine, it.text.removeMarker(), RowKind.ADDED)
            }
        }
    }
    return SideBySide(file.path, oldSide, newSide)
}

/**
 * One side of a diff as text, one line per row, with a line-number gutter.
 *
 * The gutter is part of the text rather than a third component so that the two sides cannot drift
 * out of alignment: both are laid out with the same font in the same width, and a row is a row.
 */
internal fun DiffColumnRow.render(width: Int): String {
    val number = line?.toString()?.padStart(width) ?: " ".repeat(width)
    return "$number$GUTTER_SEPARATOR$text"
}

/**
 * The highlight a diff row is painted with.
 *
 * Three states rather than one "changed" state, because the first thing a reviewer looks for in a
 * diff is the kind of change: a line that was added is not the same as a line that was replaced, and
 * they are not the same as one that was removed.
 */
internal enum class ChangeHighlight { NONE, ADDED, DELETED, UPDATED }

/**
 * What a row should be highlighted as, and that is the whole of the colour rule.
 *
 * Only the side that has the line is marked. An inserted line exists on the new side and a removed
 * one on the old side, so the other half of that row stays blank and the band is visibly one-sided
 * — which is what tells a reviewer the line moved rather than being replaced. A replaced line is a
 * change to both halves at once, so both are marked.
 */
internal fun highlightFor(kind: RowKind, isOldSide: Boolean): ChangeHighlight = when (kind) {
    RowKind.CONTEXT -> ChangeHighlight.NONE
    RowKind.ADDED -> if (isOldSide) ChangeHighlight.NONE else ChangeHighlight.ADDED
    RowKind.DELETED -> if (isOldSide) ChangeHighlight.DELETED else ChangeHighlight.NONE
    RowKind.CHANGED -> ChangeHighlight.UPDATED
}

/** The characters between a line number and its code. */
internal const val GUTTER_SEPARATOR = " \u2502 "

/**
 * The part of two similar lines that actually differs.
 *
 * The shared prefix and suffix are excluded: in a side-by-side view the reader's eye goes to what
 * is not the same, and marking a whole line when one character moved hides the change inside it.
 * Both ranges are null when the lines share nothing, which is the normal case for a replaced line
 * rather than an edge case for the renderer to special-case.
 */
internal fun changedCharacters(oldText: String, newText: String): Pair<IntRange?, IntRange?> {
    if (oldText == newText) return null to null
    val shared = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < shared && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (
        suffix < shared - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    if (prefix == 0 && suffix == 0) return null to null
    // Computed per side: one side's differing part can be longer than the other's, in which case
    // that side has no shared prefix to mark.
    val oldEnd = oldText.length - suffix
    val newEnd = newText.length - suffix
    return (if (oldEnd > prefix) prefix until oldEnd else null) to
        (if (newEnd > prefix) prefix until newEnd else null)
}

/** The `+`, `-` or space a diff line starts with. Kept in the model, dropped in the view. */
private fun String.removeMarker(): String = if (isEmpty()) this else substring(1)
