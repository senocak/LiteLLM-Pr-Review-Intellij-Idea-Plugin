package com.github.senocak.prreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffTest {
    private val MODIFIED = """
        diff --git a/src/Main.kt b/src/Main.kt
        index 1234567..89abcde 100644
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -10,4 +10,5 @@ class Main {
             fun main() {
        -        println("old")
        +        println("new")
        +        println("more")
             }
         }
    """.trimIndent()

    @Test
    fun `a modified file is read with its line numbers`() {
        val file = parseUnifiedDiff(MODIFIED).single()
        assertEquals("src/Main.kt", file.path)
        assertEquals(DiffStatus.MODIFIED, file.status)
        assertEquals(1, file.hunks.size)
        val hunk = file.hunks.single()
        assertEquals(10, hunk.oldStart)
        assertEquals(4, hunk.oldCount)
        assertEquals(10, hunk.newStart)
        assertEquals(5, hunk.newCount)
        assertEquals("class Main {", hunk.heading)
    }

    @Test
    fun `additions and deletions are counted`() {
        // The number next to a file in the list is the first thing a reviewer looks at, and it has
        // to count lines rather than hunks.
        val file = parseUnifiedDiff(MODIFIED).single()
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
    }

    @Test
    fun `line numbers count up on each side independently`() {
        // The two sides advance separately: a deleted line moves only the old one, an added line
        // only the new one. Getting this wrong shifts every line number a comment would cite.
        val hunk = parseUnifiedDiff(MODIFIED).single().hunks.single()
        val byKind = hunk.lines.groupBy { it.kind }
        assertEquals(listOf(10, 12, 13), byKind[DiffLineKind.CONTEXT]?.map { it.oldLine })
        assertEquals(listOf(10, 13, 14), byKind[DiffLineKind.CONTEXT]?.map { it.newLine })
        assertEquals(11, byKind[DiffLineKind.DELETION]?.single()?.oldLine)
        assertNull(byKind[DiffLineKind.DELETION]?.single()?.newLine)
        assertEquals(listOf(11, 12), byKind[DiffLineKind.ADDITION]?.map { it.newLine })
        assertNull(byKind[DiffLineKind.ADDITION]?.first()?.oldLine)
    }

    @Test
    fun `a deleted line whose content starts with a dash is content, not a file header`() {
        // "--- comment" is a deletion of a line reading "-- comment", and only the hunk's own line
        // counts tell it apart from the --- of the next file. Reading it as a header loses the
        // rest of the diff.
        val diff = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,2 +1,1 @@
             keep
            --- dashed
            diff --git a/b.txt b/b.txt
            --- a/b.txt
            +++ b/b.txt
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()
        val files = parseUnifiedDiff(diff)
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.path })
        assertEquals(1, files[0].deletions)
        assertEquals(listOf("--- dashed"), files[0].hunks.single().lines.map { it.text }.filter { it.startsWith("---") })
    }

    @Test
    fun `an added file is named from the plus side`() {
        val diff = """
            diff --git a/New.kt b/New.kt
            new file mode 100644
            index 0000000..a1b2c3d
            --- /dev/null
            +++ b/New.kt
            @@ -0,0 +1,2 @@
            +package demo
            +
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals("New.kt", file.path)
        assertEquals(DiffStatus.ADDED, file.status)
        assertEquals(2, file.additions)
    }

    @Test
    fun `a deleted file is named from the minus side`() {
        // The path only appears on the --- line, and getting it from there is the only way it can
        // be shown at all.
        val diff = """
            diff --git a/Old.kt b/Old.kt
            deleted file mode 100644
            index a1b2c3d..0000000
            --- a/Old.kt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -package demo
            -class Old
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals("Old.kt", file.path)
        assertEquals(DiffStatus.DELETED, file.status)
        assertEquals(2, file.deletions)
    }

    @Test
    fun `a rename carries both names`() {
        val diff = """
            diff --git a/old/Name.kt b/new/Name.kt
            similarity index 94%
            rename from old/Name.kt
            rename to new/Name.kt
            --- a/old/Name.kt
            +++ b/new/Name.kt
            @@ -1,2 +1,2 @@
             class Name {
            -    val a = 1
            +    val a = 2
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals("new/Name.kt", file.path)
        assertEquals("old/Name.kt", file.oldPath)
        assertEquals(DiffStatus.RENAMED, file.status)
    }

    @Test
    fun `a binary file is reported as binary and has no line counts`() {
        // Counting lines in a binary file would report additions and deletions that do not exist,
        // and offering to show hunks that were never sent is worse than saying nothing.
        val diff = """
            diff --git a/logo.png b/logo.png
            index a1b2c3d..e4f5a6b 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals("logo.png", file.path)
        assertEquals(DiffStatus.BINARY, file.status)
        assertTrue(file.isBinary)
        assertEquals(0, file.additions)
        assertTrue(file.hunks.isEmpty())
    }

    @Test
    fun `several files are kept in order`() {
        val diff = """
            diff --git a/one.txt b/one.txt
            --- a/one.txt
            +++ b/one.txt
            @@ -1,1 +1,1 @@
            -a
            +b
            diff --git a/two.txt b/two.txt
            --- a/two.txt
            +++ b/two.txt
            @@ -1,1 +1,2 @@
             keep
            +added
            diff --git a/three.txt b/three.txt
            --- a/three.txt
            +++ b/three.txt
            @@ -1,1 +1,1 @@
            -c
            +d
        """.trimIndent()
        val files = parseUnifiedDiff(diff)
        assertEquals(listOf("one.txt", "two.txt", "three.txt"), files.map { it.path })
        assertEquals(1, files[1].additions)
        assertEquals(0, files[1].deletions)
    }

    @Test
    fun `line numbers keep counting across a second hunk in the same file`() {
        // The second hunk starts where its header says, not where the first one stopped counting.
        val diff = """
            diff --git a/f.txt b/f.txt
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
            @@ -20,2 +20,3 @@ def something():
             x
            +y
             z
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals(2, file.hunks.size)
        assertEquals(20, file.hunks[1].oldStart)
        assertEquals(20, file.hunks[1].newStart)
        assertEquals("def something():", file.hunks[1].heading)
        // 20 and 22 are context, 21 is the added line, and the three of them run consecutively
        // because the hunk header says where this hunk starts.
        assertEquals(listOf(20, 21, 22), file.hunks[1].lines.map { it.newLine })
        assertEquals(21, file.hunks[1].lines.single { it.kind == DiffLineKind.ADDITION }.newLine)
    }

    @Test
    fun `a no-newline marker belongs to the line above it and counts nothing`() {
        val diff = """
            diff --git a/f.txt b/f.txt
            --- a/f.txt
            +++ b/f.txt
            @@ -1,1 +1,1 @@
            -old
            \ No newline at end of file
            +new
            \ No newline at end of file
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)
        assertEquals(2, file.hunks.single().lines.count { it.kind == DiffLineKind.META })
    }

    @Test
    fun `a path containing spaces survives`() {
        val diff = """
            diff --git a/src/My File.kt b/src/My File.kt
            --- a/src/My File.kt
            +++ b/src/My File.kt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()
        assertEquals("src/My File.kt", parseUnifiedDiff(diff).single().path)
    }

    @Test
    fun `a quoted path is unquoted`() {
        // Git quotes a path with a character it considers unusual, and the quotes would otherwise
        // be shown to the user and could not be matched against the file on disk.
        val diff = """
            diff --git "a/src/caf\303\251.kt" "b/src/caf\303\251.kt"
            index 1234567..89abcde 100644
            --- "a/src/caf\303\251.kt"
            +++ "b/src/caf\303\251.kt"
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()
        assertTrue(parseUnifiedDiff(diff).single().path.endsWith(".kt"))
    }

    @Test
    fun `a patch with no file header still produces a file`() {
        val diff = """
            --- a/only.txt
            +++ b/only.txt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()
        val file = parseUnifiedDiff(diff).single()
        assertEquals("only.txt", file.path)
        assertEquals(1, file.additions)
    }

    @Test
    fun `an empty diff is no files rather than a failure`() {
        assertEquals(emptyList<DiffFile>(), parseUnifiedDiff(""))
        assertEquals(emptyList<DiffFile>(), parseUnifiedDiff("   \n\n"))
    }

    @Test
    fun `a diff that is not a diff produces nothing to show`() {
        // What a login portal or an error page returns when the token is wrong. Showing that as a
        // change list would be nonsense, so the caller is told there is nothing here.
        assertEquals(emptyList<DiffFile>(), parseUnifiedDiff("<html>Sign in</html>"))
    }
}
