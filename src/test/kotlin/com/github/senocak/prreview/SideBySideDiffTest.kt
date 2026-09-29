package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two columns a reviewer reads a change in. */
class SideBySideDiffTest {
    private val ONE_CHANGE = """
        diff --git a/src/Main.kt b/src/Main.kt
        index 1234567..89abcde 100644
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -10,4 +10,4 @@ class Main {
             fun main() {
        -        println("old")
        +        println("new")
             }
         }
    """.trimIndent()

    private fun parse(diff: String): DiffFile = parseUnifiedDiff(diff).single()

    @Test
    fun `a replaced line is one row, not two`() {
        // The whole reason for the layout: the old line and the line that replaced it share a row,
        // so the reader does not have to match them up across a gap.
        val side = sideBySide(parse(ONE_CHANGE))
        assertEquals(4, side.height)
        val changed = side.newSide.filter { it.kind == RowKind.CHANGED }
        assertEquals(1, changed.size)
        assertEquals(11, changed.single().line)
        assertEquals("""        println("new")""", changed.single().text)
    }

    @Test
    fun `both sides have the same number of rows`() {
        // Alignment is the property the view depends on: a row that existed on one side only would
        // put the two halves of the comparison on different lines.
        val side = sideBySide(parse(ONE_CHANGE))
        assertEquals(side.oldSide.size, side.newSide.size)
    }

    @Test
    fun `both sides of a changed row carry their own line number`() {
        val side = sideBySide(parse(ONE_CHANGE))
        val row = side.newSide.indices.first { side.newSide[it].kind == RowKind.CHANGED }
        assertEquals(11, side.oldSide[row].line)
        assertEquals(11, side.newSide[row].line)
    }

    @Test
    fun `context is shown on both sides`() {
        val side = sideBySide(parse(ONE_CHANGE))
        val context = side.newSide.filter { it.kind == RowKind.CONTEXT }
        assertEquals(listOf(10, 12, 13), context.map { it.line })
        assertTrue(context.all { it.text == side.oldSide[side.newSide.indexOf(it)].text })
    }

    @Test
    fun `an added file has no line on the old side`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +first
                +second
                """.trimIndent(),
            ),
        )
        assertEquals(2, side.height)
        assertTrue(side.oldSide.all { it.isEmpty })
        assertEquals(listOf(1, 2), side.newSide.map { it.line })
        assertTrue(side.newSide.all { it.kind == RowKind.ADDED })
    }

    @Test
    fun `a deleted file has no line on the new side`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -first
                -second
                """.trimIndent(),
            ),
        )
        assertTrue(side.newSide.all { it.isEmpty })
        assertEquals(listOf(1, 2), side.oldSide.map { it.line })
        assertTrue(side.oldSide.all { it.kind == RowKind.DELETED })
    }

    @Test
    fun `a run of insertions and deletions pairs up positionally`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,2 +1,2 @@
                -one
                -two
                +ONE
                +TWO
                """.trimIndent(),
            ),
        )
        assertEquals(2, side.height)
        assertEquals(listOf("one", "two"), side.oldSide.map { it.text })
        assertEquals(listOf("ONE", "TWO"), side.newSide.map { it.text })
        assertTrue(side.oldSide.all { it.kind == RowKind.CHANGED })
    }

    @Test
    fun `more additions than deletions leaves the extras as additions`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,1 +1,3 @@
                -one
                +ONE
                +extra one
                +extra two
                """.trimIndent(),
            ),
        )
        // One replacement plus two genuine insertions: 3 rows, not 4, and the extras are not
        // presented as replacements of lines that were never removed.
        assertEquals(3, side.height)
        assertEquals(
            listOf(RowKind.CHANGED, RowKind.ADDED, RowKind.ADDED),
            side.newSide.map { it.kind },
        )
        assertEquals(listOf(1, null, null), side.oldSide.map { it.line })
    }

    @Test
    fun `more deletions than additions leaves the extras as deletions`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,3 +1,1 @@
                -one
                -two
                -three
                +ONE
                """.trimIndent(),
            ),
        )
        assertEquals(3, side.height)
        assertEquals(
            listOf(RowKind.CHANGED, RowKind.DELETED, RowKind.DELETED),
            side.oldSide.map { it.kind },
        )
    }

    @Test
    fun `the changed part of a line is marked, not the whole line`() {
        // The test is worth more than the feature: a one-character change highlighted as a whole
        // line is indistinguishable from a rewritten one.
        val (oldFocus, newFocus) = changedCharacters("val timeout = 30", "val timeout = 60")
        assertEquals("3", "val timeout = 30".substring(oldFocus!!.first, oldFocus.last + 1))
        assertEquals("6", "val timeout = 60".substring(newFocus!!.first, newFocus.last + 1))
    }

    @Test
    fun `a shared prefix and suffix are excluded from the marked part`() {
        val (oldFocus, newFocus) = changedCharacters("total = count(rows)", "total = rows.size")
        assertEquals("count(rows)", "total = count(rows)".substring(oldFocus!!.first, oldFocus.last + 1))
        assertEquals("rows.size", "total = rows.size".substring(newFocus!!.first, newFocus.last + 1))
    }

    @Test
    fun `only the differing characters are marked, not the shared ones around them`() {
        // The prefix "return user.n" and the suffix "e" are common to both lines, so marking them
        // would point the reader at text that did not change.
        val (oldFocus, newFocus) = changedCharacters("return user.name", "return user.nickname")
        assertNull(oldFocus)
        assertEquals("ickn", "return user.nickname".substring(newFocus!!.first, newFocus.last + 1))
    }

    @Test
    fun `lines that share nothing have no marked part`() {
        // The common case for a replaced line, and marking nothing is the right answer: there is no
        // part of it to point at.
        assertNull(changedCharacters("aaaa", "bbbb").first)
        assertNull(changedCharacters("aaaa", "bbbb").second)
    }

    @Test
    fun `identical lines have no marked part`() {
        assertEquals(null to null, changedCharacters("same", "same"))
    }

    @Test
    fun `a side that the shared prefix already covers has nothing to mark`() {
        // "ab" is entirely the prefix of "abcd", so there is no character of the short line that
        // changed. Marking a range past the end of it would throw at paint time.
        val (oldFocus, newFocus) = changedCharacters("ab", "abcd")
        assertNull(oldFocus)
        assertEquals("cd", "abcd".substring(newFocus!!.first, newFocus.last + 1))
    }

    @Test
    fun `both columns start their code at the same place`() {
        // The gutter is part of the rendered line, so the two sides only stay comparable if the
        // width is the same on both.
        val side = sideBySide(
            parse(
                """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,1 +1,1 @@
                -a
                +bbbb
                """.trimIndent(),
            ),
        )
        val width = side.gutterWidth
        val gutter = width + GUTTER_SEPARATOR.length
        side.oldSide.forEach { row ->
            assertEquals(gutter + row.text.length, row.render(width).length)
        }
        side.newSide.forEach { row ->
            assertEquals(gutter + row.text.length, row.render(width).length)
        }
    }

    @Test
    fun `the gutter is wide enough for the largest line number`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,1 +1,1 @@
                -a
                +b
                @@ -1200,1 +1200,1 @@
                -c
                +d
                """.trimIndent(),
            ),
        )
        assertTrue(side.gutterWidth >= 4)
        // Wide enough for line 1200 on either side, so no number is truncated or wrapped.
        side.oldSide.filterNot { it.isEmpty }.forEach { assertTrue(it.line!! <= 1200) }
    }

    @Test
    fun `a row renders with its line number and text`() {
        val side = sideBySide(parse(ONE_CHANGE))
        val row = side.newSide.first { it.kind == RowKind.CHANGED }
        assertEquals(" 11" + GUTTER_SEPARATOR + """        println("new")""", row.render(3))
    }

    @Test
    fun `a side with no line renders an empty gutter`() {
        val side = sideBySide(
            parse(
                """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1 @@
                +only
                """.trimIndent(),
            ),
        )
        assertEquals("   " + GUTTER_SEPARATOR, side.oldSide.single().render(3))
    }

    @Test
    fun `an added line is green and only on the new side`() {
        assertEquals(ChangeHighlight.ADDED, highlightFor(RowKind.ADDED, isOldSide = false))
        assertEquals(ChangeHighlight.NONE, highlightFor(RowKind.ADDED, isOldSide = true))
    }

    @Test
    fun `a deleted line is red and only on the old side`() {
        assertEquals(ChangeHighlight.DELETED, highlightFor(RowKind.DELETED, isOldSide = true))
        assertEquals(ChangeHighlight.NONE, highlightFor(RowKind.DELETED, isOldSide = false))
    }

    @Test
    fun `a replaced line is an update on both sides`() {
        // Not a deletion on one side and an insertion on the other: that would read as two
        // unrelated lines rather than as one line having been rewritten.
        assertEquals(ChangeHighlight.UPDATED, highlightFor(RowKind.CHANGED, isOldSide = true))
        assertEquals(ChangeHighlight.UPDATED, highlightFor(RowKind.CHANGED, isOldSide = false))
    }

    @Test
    fun `context is not highlighted on either side`() {
        assertEquals(ChangeHighlight.NONE, highlightFor(RowKind.CONTEXT, isOldSide = true))
        assertEquals(ChangeHighlight.NONE, highlightFor(RowKind.CONTEXT, isOldSide = false))
    }
}
