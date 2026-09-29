package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model is given to review.
 *
 * The property these tests exist to hold is that an added or modified line always reaches the
 * model, with the line number the review will have to cite. Everything else about the format is
 * negotiable; that is not.
 */
class ReviewDiffTest {
    private val SIMPLE = """
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

    private fun review(diff: String = SIMPLE, limit: Int = 60_000): String =
        buildReviewDiff(parseUnifiedDiff(diff), limit)

    @Test
    fun `an added line is sent`() {
        assertTrue(review().contains("""+        println("new")"""))
    }

    @Test
    fun `a removed line is sent as context`() {
        // The removed code is what makes the added code judgeable, so it is sent too.
        assertTrue(review().contains("""-        println("old")"""))
    }

    @Test
    fun `every line is sent with its line number`() {
        // A review that cites a line the author cannot find is worse than no review, so the number
        // travels with the line rather than being left in a range header to be counted through.
        val text = review()
        val added = text.lines().first { it.contains("""println("new")""") }
        assertTrue(added.trimStart().startsWith("11 +"))
        val removed = text.lines().first { it.contains("""println("old")""") }
        assertTrue(removed.trimStart().startsWith("11 -"))
    }

    @Test
    fun `the surrounding code is sent so a change can be judged`() {
        assertTrue(review().contains("fun main() {"))
    }

    @Test
    fun `each file is named`() {
        // The model is asked to answer with `path:line`, so the path has to be in the input.
        val text = review(
            """
            diff --git a/src/One.kt b/src/One.kt
            --- a/src/One.kt
            +++ b/src/One.kt
            @@ -1 +1 @@
            -a
            +b
            diff --git a/src/Two.kt b/src/Two.kt
            --- a/src/Two.kt
            +++ b/src/Two.kt
            @@ -1 +1 @@
            -c
            +d
            """.trimIndent(),
        )
        assertTrue(text.contains("## src/One.kt"))
        assertTrue(text.contains("## src/Two.kt"))
    }

    @Test
    fun `what each file did is stated`() {
        val text = review()
        assertTrue(text.contains("modified"))
        assertTrue(text.contains("+1"))
    }

    @Test
    fun `a rename says where the file came from`() {
        val text = review(
            """
            diff --git a/old.kt b/new.kt
            similarity index 90%
            rename from old.kt
            rename to new.kt
            """.trimIndent(),
        )
        assertTrue(text.contains("renamed from old.kt"))
    }

    @Test
    fun `a binary file is acknowledged rather than sent as nothing`() {
        val text = review(
            """
            diff --git a/logo.png b/logo.png
            index 1234567..89abcde 100644
            Binary files a/logo.png and b/logo.png differ
            """.trimIndent(),
        )
        assertTrue(text.contains("Binary file"))
    }

    @Test
    fun `the section a change is in is sent`() {
        // The enclosing function is often the only thing that says what a line is for.
        assertTrue(review().contains("@@ class Main { @@"))
    }

    @Test
    fun `the no-newline marker is kept with its line`() {
        val text = review(
            """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,1 @@
            -a
            \ No newline at end of file
            +b
            """.trimIndent(),
        )
        assertTrue(text.contains("No newline at end of file"))
    }

    @Test
    fun `a pull request with no changes says so`() {
        assertTrue(buildReviewDiff(emptyList()).contains("no file changes"))
    }

    @Test
    fun `a small change is sent with all of its context`() {
        // Nothing is pruned when everything fits: a reviewer would only be misled by a context gap
        // that was introduced to save tokens nobody was short of.
        val text = review()
        assertFalse(text.contains("..."))
        assertTrue(text.contains("     }"))
    }

    @Test
    fun `a change is never dropped to save space`() {
        // The whole point of pruning context rather than the tail: a large pull request loses the
        // code around a change, never the change itself.
        val many = buildString {
            for (index in 1..400) {
                append("@@ -$index,4 +$index,4 @@\n")
                for (line in 0 until 3) append("  context $index.$line\n")
                append("-removed $index\n")
                append("+added $index\n")
            }
        }
        // Large enough for the changes alone and not for the changes with their context, which is
        // the trade the review input is supposed to make.
        val text = buildReviewDiff(
            parseUnifiedDiff(
                """
                diff --git a/big.txt b/big.txt
                --- a/big.txt
                +++ b/big.txt
                """.trimIndent() + "\n" + many,
            ),
            limit = 30_000,
        )
        assertTrue(text.contains("+added 1\n"))
        assertTrue(text.contains("+added 400\n"))
        assertTrue(text.contains("-removed 200\n"))
        // And the context around them is what went.
        assertTrue(text.contains("..."))
    }

    @Test
    fun `context is what gives way when the change is large`() {
        val many = buildString {
            for (index in 1..300) {
                append("@@ -$index,5 +$index,4 @@\n")
                for (line in 0 until 4) append("  context $index.$line\n")
                append("-removed $index\n")
            }
        }
        val text = buildReviewDiff(
            parseUnifiedDiff(
                """
                diff --git a/big.txt b/big.txt
                --- a/big.txt
                +++ b/big.txt
                """.trimIndent() + "\n" + many,
            ),
            limit = 20_000,
        )
        assertTrue(text.contains("-removed 1\n"))
        assertTrue(text.contains("-removed 300\n"))
        // Gaps are marked, so the model is not shown a jump and told it is continuous.
        assertTrue(text.contains("..."))
    }

    @Test
    fun `a change too large even on its own is dropped and said so`() {
        // The honest failure. A model told to review a diff that silently lost its tail will
        // pronounce a partial change complete.
        val huge = buildString {
            for (index in 1..500) {
                append("@@ -$index,0 +$index,1 @@\n")
                append("+added line number $index that is quite long indeed\n")
            }
        }
        val text = buildReviewDiff(
            parseUnifiedDiff(
                """
                diff --git a/huge.txt b/huge.txt
                --- a/huge.txt
                +++ b/huge.txt
                """.trimIndent() + "\n" + huge,
            ),
            limit = 2_000,
        )
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun `line numbers are aligned so they are readable in a column`() {
        val text = review(
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
        )
        val numbered = text.lines().filter { it.trimStart().startsWith("1 ") || it.trimStart().startsWith("1200") }
        assertEquals(4, numbered.size)
        val starts = numbered.map { it.indexOfFirst { it == '+' || it == '-' } }
        assertEquals(1, starts.distinct().size)
    }

    @Test
    fun `a diff that could not be read is never reviewed`() {
        // The whole point: a model handed nothing answers "no actionable findings", and that is
        // indistinguishable from a clean bill of health for a large pull request.
        assertEquals(ReviewScope.Unreadable, reviewScopeOf(emptyList()))
    }

    @Test
    fun `a response that is not a diff is never reviewed`() {
        // What a Bitbucket login page or an error body parses to.
        assertEquals(
            ReviewScope.Unreadable,
            reviewScopeOf(parseUnifiedDiff("<html><body>Sign in to Bitbucket</body></html>")),
        )
    }

    @Test
    fun `a rename with no code changed is not worth a model call`() {
        val renamed = parseUnifiedDiff(
            """
            diff --git a/old.kt b/new.kt
            similarity index 100%
            rename from old.kt
            rename to new.kt
            """.trimIndent(),
        )
        assertEquals(ReviewScope.NoCode, reviewScopeOf(renamed))
    }

    @Test
    fun `a real change says how much of it there is`() {
        val scope = reviewScopeOf(
            parseUnifiedDiff(
                """
                diff --git a/one.txt b/one.txt
                --- a/one.txt
                +++ b/one.txt
                @@ -1,1 +1,2 @@
                -old
                +new
                +more
                """.trimIndent(),
            ),
        )
        assertEquals(ReviewScope.Ready(added = 2, removed = 1, files = 1), scope)
    }

    @Test
    fun `the scope is stated in words a reviewer can check against the changes tab`() {
        assertEquals("412 added, 88 removed · 6 files", ReviewScope.Ready(412, 88, 6).describe())
        assertEquals("1 added, 0 removed · 1 file", ReviewScope.Ready(1, 0, 1).describe())
    }

    @Test
    fun `the review reuses the copy the reviewer is already reading`() {
        // The reason this exists at all: a full diff is the slowest thing in the window, and the
        // review should not pay for a second copy of what is on screen.
        val onScreen = DisplayedDiff()
        onScreen.put(DiffKey(42, "2026-09-28T10:00:00+0000"), "the diff on screen")
        assertEquals("the diff on screen", onScreen.get(DiffKey(42, "2026-09-28T10:00:00+0000")))
    }

    @Test
    fun `another pull request does not read as the one on screen`() {
        val onScreen = DisplayedDiff()
        onScreen.put(DiffKey(42, null), "forty two")
        assertNull(onScreen.get(DiffKey(43, null)))
    }

    @Test
    fun `a commit pushed since the changes were read is not reviewed from the old copy`() {
        // The bug: a pull request number does not change when a commit is pushed, so anything keyed
        // on the number alone hands back the diff the reviewer has already seen.
        val onScreen = DisplayedDiff()
        onScreen.put(DiffKey(42, "2026-09-28T10:00:00+0000"), "the first version")
        assertNull(onScreen.get(DiffKey(42, "2026-09-28T10:05:00+0000")))
    }

    @Test
    fun `a refresh throws away what was on screen`() {
        val onScreen = DisplayedDiff()
        onScreen.put(DiffKey(42, null), "something")
        onScreen.clear()
        assertNull(onScreen.get(DiffKey(42, null)))
    }
}
