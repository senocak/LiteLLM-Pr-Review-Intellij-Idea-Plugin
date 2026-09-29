package com.github.senocak.prreview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What the reviewer gets to tell the model, and where it is remembered.
 *
 * A diff cannot say why a pull request exists. The notes are the only way a reviewer can say that
 * the change implements a specification nobody here can read, that a file is generated, or that this
 * project has settled a question — and every one of those is a case where a review without them is
 * confidently wrong rather than merely incomplete. So two things matter: the notes reach the model,
 * and the notes about one pull request never end up attached to another.
 */
class ReviewNotesTest : BasePlatformTestCase() {
    /**
     * The store belongs to the project, and the project outlives a single test, so notes written by
     * one test would otherwise be visible to the next — and the test that writes two hundred of them
     * would silently empty the ones written before it.
     */
    override fun setUp() {
        super.setUp()
        ReviewNotes.getInstance(project).clear()
    }

    private val pullRequest = PullRequest(
        id = 42,
        title = "Add the thing",
        author = "someone",
        sourceBranch = "feature/x",
        targetBranch = "main",
        link = null,
        updatedOn = "2026-09-28T10:00:00+0000",
    )

    private val changes = """
        diff --git a/gen/Api.kt b/gen/Api.kt
        --- a/gen/Api.kt
        +++ b/gen/Api.kt
        @@ -1,1 +1,1 @@
        -val old = 1
        +val generated = 2
    """.trimIndent()

    fun testANoteIsSentWithTheRequest() {
        val message = LiteLLMClient.userMessage(
            pullRequest,
            changes,
            "gen/ is generated from the spec; do not review it by hand.",
        )
        assertTrue(message.contains("gen/ is generated from the spec; do not review it by hand."))
    }

    fun testTheNoteIsLabelledSoItIsNotReadAsCode() {
        // Otherwise a model can treat the note as a finding, or as a line of the diff to cite.
        val message = LiteLLMClient.userMessage(pullRequest, changes, "ignore the generated files")
        assertTrue(
            message.contains("Reviewer's notes, in their own words, about this pull request"),
        )
    }

    fun testTheNoteComesBeforeTheLinesSoItIsReadFirst() {
        val message = LiteLLMClient.userMessage(pullRequest, changes, "the brief")
        assertTrue(message.indexOf("the brief") < message.indexOf("line-number marker text"))
    }

    fun testNoNotesMeansNoSection() {
        for (blank in listOf("", "   ", "\n\t ")) {
            val message = LiteLLMClient.userMessage(pullRequest, changes, blank)
            assertFalse(message.contains("Reviewer's notes"))
        }
    }

    fun testTheModelIsToldItCannotOpenWhatANotePointsAt() {
        // Without this the model invents the contents of a ticket it was told about, and reports
        // findings against a specification nobody in this conversation has read.
        assertTrue(LiteLLMClient.SYSTEM_PROMPT.contains("You cannot open a file, a ticket"))
    }

    fun testTheModelIsToldAGeneratedFileIsAReasonToStayQuiet() {
        assertTrue(LiteLLMClient.SYSTEM_PROMPT.contains("generated or vendored"))
    }

    fun testNotesAreRememberedForThePullRequestTheyWereWrittenFor() {
        val notes = ReviewNotes.getInstance(project)
        notes.setNotes("stash.example/bb", 42, "implements SPEC-7")
        assertEquals("implements SPEC-7", notes.notesFor("stash.example/bb", 42))
    }

    fun testANoteWrittenForOnePullRequestIsNotSentWithAnother() {
        val notes = ReviewNotes.getInstance(project)
        notes.setNotes("stash.example/bb", 42, "about forty two")
        assertEquals("", notes.notesFor("stash.example/bb", 43))
    }

    fun testNotesDoNotCrossRepositories() {
        // The same number on a different server is a different pull request, and a note about one
        // repository's conventions is misleading in another.
        val notes = ReviewNotes.getInstance(project)
        notes.setNotes("one.example/bb", 42, "about this repository")
        assertEquals("", notes.notesFor("two.example/bb", 42))
    }

    fun testABlankNoteIsAnAbsentOne() {
        // Storing empty strings would make the store grow for every pull request ever looked at.
        val notes = ReviewNotes.getInstance(project)
        notes.setNotes("stash.example/bb", 42, "something")
        notes.setNotes("stash.example/bb", 42, "   ")
        assertEquals("", notes.notesFor("stash.example/bb", 42))
        assertFalse(notes.state.byPullRequest.containsKey("stash.example/bb#42"))
    }

    fun testANoteIsTrimmedRatherThanSentWithBlankLinesAroundIt() {
        val notes = ReviewNotes.getInstance(project)
        notes.setNotes("stash.example/bb", 42, "\n\n  the brief  \n\n")
        assertEquals("the brief", notes.notesFor("stash.example/bb", 42))
    }

    fun testTheStoreDoesNotGrowWithoutBound() {
        val notes = ReviewNotes.getInstance(project)
        for (id in 1..260) notes.setNotes("stash.example/bb", id, "note $id")
        assertTrue("kept ${notes.state.byPullRequest.size}", notes.state.byPullRequest.size <= 200)
        // The newest are the ones a reviewer is still working on, so the oldest go.
        assertEquals("note 260", notes.notesFor("stash.example/bb", 260))
        assertEquals("", notes.notesFor("stash.example/bb", 1))
    }
}
