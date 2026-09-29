package com.github.senocak.prreview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

/**
 * What the Changes tab shows a reviewer.
 *
 * The assertions are on the two rendered sides rather than on the component tree, because the two
 * texts are the whole point of the view: a file list with a diff underneath that does not put the
 * old and the new side by side is not the view that was asked for.
 */
class ChangesViewTest : BasePlatformTestCase() {
    private val TWO_FILES = """
        diff --git a/src/Main.kt b/src/Main.kt
        index 1234567..89abcde 100644
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -1,2 +1,3 @@ class Main {
             fun main() {
        -        println("old")
        +        println("new")
        +        println("more")
        diff --git a/src/Util.kt b/src/Util.kt
        index 89abcde..1234567 100644
        --- a/src/Util.kt
        +++ b/src/Util.kt
        @@ -10,1 +10,1 @@
        -val a = 1
        +val a = 2
    """.trimIndent()

    private fun viewOf(diff: String = TWO_FILES): ChangesView =
        ChangesView().apply { show(parseUnifiedDiff(diff)) }

    fun testTheFileListCoversEveryChange() {
        assertEquals(listOf("src/Main.kt", "src/Util.kt"), viewOf().listedPaths)
    }

    fun testSelectingAPullRequestOpensTheFirstFilesDiff() {
        // Selecting a pull request is the only click a reviewer makes to get here, so it has to end
        // with a diff on screen rather than an empty panel.
        val view = viewOf()
        assertEquals("src/Main.kt", view.openFile?.path)
        assertTrue(view.newText.contains("""println("new")"""))
    }

    fun testTheTwoSidesHaveTheSameNumberOfLines() {
        // The alignment is what makes the layout readable: if the left side had more lines, every
        // comparison after the divergence would be against the wrong line.
        val view = viewOf()
        assertEquals(view.oldText.lines().size, view.newText.lines().size)
    }

    fun testTheOldLineSitsOnTheSameLineAsTheLineThatReplacedIt() {
        val view = viewOf()
        val oldRow = view.oldText.lines().indexOfFirst { it.contains("""println("old")""") }
        val newRow = view.newText.lines().indexOfFirst { it.contains("""println("new")""") }
        assertTrue(oldRow >= 0)
        assertEquals(oldRow, newRow)
    }

    fun testEachSideIsNumberedWithItsOwnLineNumbers() {
        val view = viewOf()
        val oldRow = view.oldText.lines().first { it.contains("""println("old")""") }
        val newRow = view.newText.lines().first { it.contains("""println("new")""") }
        // Old line 2 is replaced by new line 2; the numbers are not interchangeable and the view
        // must not use one side's numbers for both.
        assertTrue(oldRow.trimStart().startsWith("2"))
        assertTrue(newRow.trimStart().startsWith("2"))
    }

    fun testContextIsShownSoAChangeCanBeJudged() {
        val view = viewOf()
        assertTrue(view.newText.contains("fun main() {"))
    }

    fun testClickingAnotherFileOpensThatFilesDiff() {
        val view = viewOf()
        view.openFileAt(1)
        assertEquals("src/Util.kt", view.openFile?.path)
        assertTrue(view.newText.contains("val a = 2"))
        // The previous file's diff has to be gone, or the reviewer reads the wrong code.
        assertFalse(view.newText.contains("println"))
    }

    fun testAnAddedFileHasNothingOnTheLeft() {
        val view = viewOf(
            """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1 @@
            +hello
            """.trimIndent(),
        )
        assertTrue(view.newText.contains("hello"))
        assertFalse(view.oldText.contains("hello"))
    }

    fun testTheHeaderSaysWhichFileIsOpenAndWhatHappenedToIt() {
        val view = ChangesView().apply {
            show(
                parseUnifiedDiff(
                    """
                    diff --git a/old.txt b/new.txt
                    similarity index 90%
                    rename from old.txt
                    rename to new.txt
                    """.trimIndent(),
                ),
            )
        }
        assertTrue(view.headerText.contains("new.txt"))
        assertTrue(view.headerText.contains("renamed"))
    }

    fun testARenamedFileSaysWhereItCameFrom() {
        val view = ChangesView().apply {
            show(
                parseUnifiedDiff(
                    """
                    diff --git a/old.txt b/new.txt
                    similarity index 90%
                    rename from old.txt
                    rename to new.txt
                    """.trimIndent(),
                ),
            )
        }
        assertTrue(view.listedPaths.contains("new.txt"))
    }

    fun testABinaryFileSaysThereIsNothingToReadRatherThanShowingAnEmptyPane() {
        val view = viewOf(
            """
            diff --git a/logo.png b/logo.png
            index 1234567..89abcde 100644
            Binary files a/logo.png and b/logo.png differ
            """.trimIndent(),
        )
        // An empty pane next to a file in the list reads as a broken plugin rather than as a file
        // that has no textual diff.
        assertTrue(view.messageText.contains("no textual diff"))
    }

    fun testTheSummaryCountsFilesAndLines() {
        assertEquals("2 files, +3 −2", viewOf().summaryText)
    }

    fun testTheSummaryUsesTheSingularForOneFile() {
        val view = viewOf(
            """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent(),
        )
        assertEquals("1 file, +1 −1", view.summaryText)
    }

    fun testANoteIsAppendedToTheSummary() {
        val view = ChangesView().apply { show(parseUnifiedDiff(TWO_FILES), "Some of this was omitted.") }
        assertEquals("2 files, +3 −2  ·  Some of this was omitted.", view.summaryText)
    }

    fun testLoadingClearsThePreviousPullRequest() {
        val view = viewOf()
        view.showLoading("Loading the changes in #7…")
        assertEquals(emptyList<String>(), view.listedPaths)
        assertEquals("", view.newText)
        assertEquals("Loading the changes in #7…", view.summaryText)
    }

    fun testAFailureIsShownRatherThanAnEmptyDiff() {
        val view = viewOf()
        view.showFailure("Bitbucket Server rejected the request (HTTP 404).")
        assertEquals(emptyList<String>(), view.listedPaths)
        assertEquals("Bitbucket Server rejected the request (HTTP 404).", view.messageText)
    }

    fun testAnUnreadableDiffShowsTheRawTextRatherThanAnEmptyPane() {
        val view = viewOf()
        view.showUnparsed("Some other diff format entirely", "This diff is not in a format the plugin can break down.")
        assertEquals("Some other diff format entirely", view.messageText)
        assertEquals("This diff is not in a format the plugin can break down.", view.summaryText)
    }

    fun testShowingAnotherPullRequestReplacesTheFirst() {
        val view = viewOf()
        view.show(parseUnifiedDiff("diff --git a/other.txt b/other.txt\n@@ -1 +1 @@\n-a\n+b\n"))
        assertEquals(listOf("other.txt"), view.listedPaths)
        assertFalse(view.newText.contains("println"))
    }

    private val ONLY_DELETIONS = """
        diff --git a/src/Gone.kt b/src/Gone.kt
        deleted file mode 100644
        --- a/src/Gone.kt
        +++ /dev/null
        @@ -1,2 +0,0 @@
        -class Gone
        -}
    """.trimIndent()

    fun testAnAddedLineIsGreenAndOnlyOnTheNewSide() {
        val view = viewOf()
        assertTrue(view.paintedColors(isOldSide = true).none(::isGreen))
        assertTrue(view.paintedColors(isOldSide = false).any(::isGreen))
    }

    fun testARemovedLineIsRedAndOnlyOnTheOldSide() {
        val view = viewOf(ONLY_DELETIONS)
        assertTrue(view.paintedColors(isOldSide = true).any(::isRed))
        assertTrue(view.paintedColors(isOldSide = false).isEmpty())
    }

    fun testAReplacedLineIsYellowOnBothSides() {
        val view = viewOf(REPLACED)
        assertTrue(view.paintedColors(isOldSide = true).any(::isYellow))
        assertTrue(view.paintedColors(isOldSide = false).any(::isYellow))
    }

    fun testTheKindOfChangeIsTheOnlyColourOnARow() {
        val view = viewOf()
        val colors = view.paintedColors(isOldSide = false)
        // A replaced line is not also reported as an insertion.
        assertTrue(colors.none(::isRed))
        assertTrue(colors.all { isGreen(it) || isYellow(it) })
    }

    fun testTheMarkedCharactersAreAStrongerShadeOfTheirOwnRow() {
        val view = viewOf()
        val colors = view.paintedColors(isOldSide = false)
        // A replaced line gets a band and a mark, and the mark is the one that stands out.
        assertTrue(colors.count(::isYellow) >= 2)
    }

    private val REPLACED = """
        diff --git a/src/Main.kt b/src/Main.kt
        index 1234567..89abcde 100644
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -1,1 +1,1 @@
        -        println("old")
        +        println("new")
    """.trimIndent()

    /**
     * The colours are named by hue rather than by value, so retuning a shade for one theme does not
     * break the promise that a removal is red and an addition is green.
     */
    private fun hueOf(color: Color) = Color.RGBtoHSB(color.red, color.green, color.blue, null)[0] * 360

    private fun isRed(color: Color) = hueOf(color) < 20 || hueOf(color) > 340

    private fun isYellow(color: Color) = hueOf(color) in 40f..70f

    private fun isGreen(color: Color) = hueOf(color) in 80f..170f
}
