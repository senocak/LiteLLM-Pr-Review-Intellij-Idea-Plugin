package com.github.senocak.prreview

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridLayout
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ListSelectionModel
import javax.swing.text.DefaultHighlighter

/**
 * What a pull request changes: the files it touches, and the diff of the one being read.
 *
 * Selecting a file opens its diff side by side rather than scrolling one combined listing, because
 * the question a reviewer asks of a change is "what does this line replace?", and that is only
 * answerable with the old and the new next to each other. One file at a time is also what makes
 * that layout usable in a dock a few hundred pixels wide.
 *
 * Two text areas rather than an editor or a table: an editor has no two revisions to open, a table
 * cell cannot mark part of a line, and a highlighter over text can mark exactly the characters that
 * changed. The two panes are scrolled together, so a row on the left is the same row on the right.
 */
internal class ChangesView : JPanel(BorderLayout()) {
    private val fileModel = DefaultListModel<DiffFile>()
    private val fileList = JBList(fileModel).apply {
        cellRenderer = DiffFileRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "Select a pull request to see what it changes."
    }

    private val oldSide = sidePane(true)
    private val newSide = sidePane(false)
    private val fileHeader = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(4, 8, 4, 8)
    }
    private val messageArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }

    private val messageCard = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(messageArea, BorderLayout.CENTER)
    }
    private val diffCard = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(fileHeader, BorderLayout.NORTH)
        add(
            JPanel(GridLayout(1, 2, 0, 0)).apply {
                isOpaque = false
                // No gap: the two sides have to look like one table with a seam down it, not like
                // two panes a reader has to measure up against each other.
                add(oldSide.scroll)
                add(newSide.scroll)
            },
            BorderLayout.CENTER,
        )
    }
    private val cards = CardLayout()
    private val center = JPanel(cards).apply { isOpaque = false }

    private val summary = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        center.add(diffCard, DIFF_CARD)
        center.add(messageCard, MESSAGE_CARD)

        add(
            JBScrollPane(fileList).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLineBottom(JBColor.border()),
                    JBUI.Borders.empty(),
                )
                preferredSize = JBUI.size(240, 150)
            },
            BorderLayout.NORTH,
        )
        add(center, BorderLayout.CENTER)
        add(summary, BorderLayout.SOUTH)

        scrollTogether(oldSide.scroll.viewport, newSide.scroll.viewport)
        showMessage(NO_PULL_REQUEST_SELECTED)
        fileList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showSelectedFile()
        }
    }

    /**
     * Shows a pull request's changes and opens its first file.
     *
     * The first file rather than nothing, because a reviewer who has just selected a pull request
     * wants to see a diff, and the list has already told them which files there are.
     *
     * @param note appended to the summary line, so a diff that is not the whole change says so
     *   rather than implying it is.
     */
    fun show(changed: List<DiffFile>, note: String? = null) {
        fileModel.removeAllElements()
        changed.forEach(fileModel::addElement)
        summary.text = describe(changed, note)
        if (changed.isEmpty()) {
            fileList.clearSelection()
            clearDiffPanes()
            showMessage("This pull request has no changes to show.")
            return
        }
        fileList.selectedIndex = 0
        showSelectedFile()
    }

    /** Clears the view for a pull request whose diff is not loaded yet. */
    fun showLoading(message: String) {
        clearFileList()
        summary.text = message
        showMessage(message)
    }

    /** Shows a diff that could not be broken down, as text: better than an empty pane. */
    fun showUnparsed(text: String, note: String) {
        clearFileList()
        summary.text = note
        showMessage(text)
    }

    fun showFailure(message: String) {
        clearFileList()
        showMessage(message)
    }

    private fun clearFileList() {
        fileModel.removeAllElements()
    }

    /**
     * Empties the diff panes without touching the file list.
     *
     * Deliberately separate from [clearFileList]: this is reached from the selection listener, and
     * emptying the list model there would re-enter the listener through the selection change that
     * emptying causes. The result is a list that empties itself the moment it is refilled.
     */
    private fun clearDiffPanes() {
        fileHeader.text = ""
        render(sideBySideForNothing())
    }

    private fun sideBySideForNothing(): SideBySide = SideBySide("", emptyList(), emptyList())

    /** The file whose diff is open. */
    internal val openFile: DiffFile? get() = fileList.selectedValue

    internal val listedPaths: List<String> get() = fileModel.elements().asSequence().map { it.path }.toList()

    internal val headerText: String get() = fileHeader.text

    internal val summaryText: String get() = summary.text

    internal val oldText: String get() = oldSide.text.text

    internal val newText: String get() = newSide.text.text

    /** The band and mark colours actually painted on one side, for tests. */
    internal fun paintedColors(isOldSide: Boolean): List<Color> =
        (if (isOldSide) oldSide else newSide).paintedColors

    internal val messageText: String get() = messageArea.text

    /** Opens a file by position, as clicking it in the list does. */
    internal fun openFileAt(index: Int) {
        fileList.selectedIndex = index
    }

    private fun showSelectedFile() {
        val file = fileList.selectedValue
        if (file == null) {
            clearDiffPanes()
            showMessage(NO_PULL_REQUEST_SELECTED)
            return
        }
        if (file.isBinary) {
            fileHeader.text = file.path
            render(sideBySideForNothing())
            showMessage(
                "This file has no textual diff: an image, an archive or a compiled artefact changes " +
                    "by its bytes rather than by its lines. It is part of the pull request, but " +
                    "there is nothing here to read line by line.",
            )
            return
        }
        fileHeader.text = "${file.path}  ·  ${labelOf(file.status)}  ·  +${file.additions} −${file.deletions}"
        render(sideBySide(file))
    }

    private fun render(diff: SideBySide) {
        oldSide.render(diff.oldSide, diff.gutterWidth)
        newSide.render(diff.newSide, diff.gutterWidth)
        cards.show(center, DIFF_CARD)
    }

    private fun showMessage(message: String) {
        messageArea.text = message
        messageArea.caretPosition = 0
        cards.show(center, MESSAGE_CARD)
    }

    /**
     * Keeps the two panes on the same row.
     *
     * Vertical only: the two sides are the same lines of the same file, so a row is a row. Each
     * pane keeps its own horizontal position, because a long line on one side would otherwise push
     * the other side's text out of view — the case where reading them separately matters more.
     */
    private fun scrollTogether(left: JViewport, right: JViewport) {
        var following = false
        fun copy(from: JViewport, to: JViewport) {
            // Setting the other viewport's position fires its own listener; without this the two
            // would push each other back and forth.
            if (following || from.viewPosition.y == to.viewPosition.y) return
            following = true
            try {
                to.viewPosition = Point(to.viewPosition.x, from.viewPosition.y)
            } finally {
                following = false
            }
        }
        left.addChangeListener { copy(left, right) }
        right.addChangeListener { copy(right, left) }
    }

    private fun describe(changed: List<DiffFile>, note: String?): String {
        if (changed.isEmpty()) return note.orEmpty()
        val added = changed.sumOf { it.additions }
        val removed = changed.sumOf { it.deletions }
        val text = "${changed.size} ${if (changed.size == 1) "file" else "files"}, +$added −$removed"
        return listOfNotNull(text, note).joinToString("  ·  ")
    }

    private companion object {
        const val DIFF_CARD = "diff"
        const val MESSAGE_CARD = "message"
        const val NO_PULL_REQUEST_SELECTED = "Select a pull request to see what it changes."
    }
}

/** One side of the diff: a text area and the scroll pane around it. */
private class DiffSide(private val isOldSide: Boolean) {
    val text = JBTextArea().apply {
        isEditable = false
        // No wrapping, or the two sides wrap at different points and stop being comparable.
        lineWrap = false
        // Whitespace in code is meaningful, and a tab rendered as spaces hides the difference
        // between an indented continuation and a block.
        font = JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().size)
        border = JBUI.Borders.empty(2, 4)
    }
    val scroll = JBScrollPane(text).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        // The line numbers are part of the text, so a horizontal scrollbar is expected and the
        // corner is left empty rather than covered by one.
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    fun render(rows: List<DiffColumnRow>, gutterWidth: Int) {
        // The offset of every row is recorded as the text is built: there is no API on a text area
        // that maps a line number to a document position, and a highlighter needs positions.
        val starts = IntArray(rows.size)
        val body = buildString {
            rows.forEachIndexed { index, row ->
                if (index > 0) append('\n')
                starts[index] = length
                append(row.render(gutterWidth))
            }
        }
        text.text = body
        text.caretPosition = 0
        paint(rows, starts, body.length, gutterWidth)
    }

    /**
     * Tints each changed row according to what kind of change it is, and marks the characters that
     * changed within a replaced line.
     *
     * The row band and the mark within it are two shades of one colour rather than two different
     * colours, which is how a diff is read in every other tool: a pale band says the line moved, and
     * a stronger mark inside it says where. A replaced line is therefore yellow with a stronger
     * yellow on the characters that differ, and never green on one side and red on the other, which
     * would read as two unrelated lines.
     *
     * One highlighter per changed row, capped: a pull request can be tens of thousands of lines
     * and past a few thousand the highlighters themselves are what makes scrolling stutter. The
     * text is all still there, only the marks are dropped.
     */
    private fun paint(rows: List<DiffColumnRow>, starts: IntArray, endOfText: Int, gutterWidth: Int) {
        text.highlighter.removeAllHighlights()
        if (rows.size > MAX_PAINTED_ROWS) return
        val gutter = gutterWidth + GUTTER_SEPARATOR.length
        rows.forEachIndexed { index, row ->
            val highlight = highlightFor(row.kind, isOldSide)
            if (highlight == ChangeHighlight.NONE) return@forEachIndexed
            val start = starts[index]
            // The end is the start of the next row less the newline between them.
            val end = if (index + 1 < starts.size) starts[index + 1] - 1 else endOfText
            if (end <= start) return@forEachIndexed
            text.highlighter.addHighlight(start, end, painter(rowColor(highlight)))
            row.focus?.let {
                val focusStart = start + gutter + it.first
                val focusEnd = start + gutter + it.last + 1
                if (focusEnd <= end) text.highlighter.addHighlight(focusStart, focusEnd, painter(focusColor(highlight)))
            }
        }
    }

    private fun painter(color: Color) = DefaultHighlighter.DefaultHighlightPainter(color)

    /** The colours actually painted on this side, which is what a test can look at. */
    val paintedColors: List<Color>
        get() = text.highlighter.highlights.map {
            (it.painter as DefaultHighlighter.DefaultHighlightPainter).getColor()
        }

    private companion object {
        const val MAX_PAINTED_ROWS = 4_000
        // Two shades per kind of change, light theme first. The band is pale enough to read text on
        // and the mark is strong enough to pick out a few characters in it.
        val ADDED_ROW = JBColor(Color(0xE6, 0xFF, 0xEC), Color(0x0B, 0x2E, 0x18))
        val ADDED_FOCUS = JBColor(Color(0x9C, 0xE8, 0xB4), Color(0x1E, 0x5C, 0x33))
        val DELETED_ROW = JBColor(Color(0xFF, 0xEC, 0xEC), Color(0x33, 0x14, 0x14))
        val DELETED_FOCUS = JBColor(Color(0xFF, 0xB3, 0xB3), Color(0x5C, 0x20, 0x20))
        val UPDATED_ROW = JBColor(Color(0xFF, 0xF8, 0xC5), Color(0x3D, 0x33, 0x08))
        val UPDATED_FOCUS = JBColor(Color(0xFF, 0xDD, 0x66), Color(0x6B, 0x55, 0x10))

        fun rowColor(highlight: ChangeHighlight): Color = when (highlight) {
            ChangeHighlight.ADDED -> ADDED_ROW
            ChangeHighlight.DELETED -> DELETED_ROW
            ChangeHighlight.UPDATED -> UPDATED_ROW
            // Unreachable: a row that is not highlighted is skipped before it gets here.
            ChangeHighlight.NONE -> error("no row colour for an unchanged row")
        }

        fun focusColor(highlight: ChangeHighlight): Color = when (highlight) {
            ChangeHighlight.ADDED -> ADDED_FOCUS
            ChangeHighlight.DELETED -> DELETED_FOCUS
            ChangeHighlight.UPDATED -> UPDATED_FOCUS
            ChangeHighlight.NONE -> error("no mark colour for an unchanged row")
        }
    }
}

private fun ChangesView.sidePane(isOldSide: Boolean): DiffSide = DiffSide(isOldSide)

/** One row per changed file: the status, the path, and how many lines moved. */
private class DiffFileRenderer : ColoredListCellRenderer<DiffFile>() {
    override fun customizeCellRenderer(
        list: javax.swing.JList<out DiffFile>,
        value: DiffFile?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        val attributes = when (value.status) {
            DiffStatus.ADDED -> ADDED_ATTRIBUTES
            DiffStatus.DELETED -> DELETED_ATTRIBUTES
            DiffStatus.MODIFIED -> UPDATED_ATTRIBUTES
            else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        }
        append(letterOf(value.status) + "  ", attributes)
        append(value.path)
        if (value.oldPath != null && value.oldPath != value.path) {
            append("  (from ${value.oldPath})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (value.isBinary) {
            append("  binary", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        if (value.additions > 0) append("  +${value.additions}", ADDED_ATTRIBUTES)
        if (value.deletions > 0) append("  −${value.deletions}", DELETED_ATTRIBUTES)
    }

    private fun letterOf(status: DiffStatus): String = when (status) {
        DiffStatus.ADDED -> "A"
        DiffStatus.DELETED -> "D"
        DiffStatus.RENAMED -> "R"
        DiffStatus.BINARY -> "B"
        DiffStatus.MODIFIED -> "M"
    }

    private companion object {
        val ADDED_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("GitAdded", Color(0x00, 0x80, 0x00)),
        )
        val DELETED_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor.namedColor("GitDeleted", Color(0xC0, 0x00, 0x00)),
        )
        // Amber, the same hue as the updated bands in the diff, so the list and the diff agree.
        val UPDATED_ATTRIBUTES = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor(Color(0x9A, 0x6A, 0x00), Color(0xE2, 0xB6, 0x4F)),
        )
    }
}

/** What the plugin calls a file's status, in a sentence. */
internal fun labelOf(status: DiffStatus): String = when (status) {
    DiffStatus.ADDED -> "added"
    DiffStatus.DELETED -> "deleted"
    DiffStatus.RENAMED -> "renamed"
    DiffStatus.MODIFIED -> "modified"
    DiffStatus.BINARY -> "binary"
}
