package com.github.senocak.prreview

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.MapAnnotation

/**
 * What the reviewer has told the model about each pull request, in their own words.
 *
 * Project level rather than application level, because a note is about the code in this repository
 * and is meaningless — or worse, misleading — in another one. Connection settings belong to the
 * application; what a pull request is for belongs to the project it lives in.
 *
 * Keyed by repository as well as by number, so that pointing the plugin at a different Bitbucket
 * server does not carry one project's notes over to another project's pull request with the same
 * id.
 */
@Service(Service.Level.PROJECT)
@State(name = "LiteLLMPrReviewNotes", storages = [Storage("litellmPrReviewNotes.xml")])
class ReviewNotes : PersistentStateComponent<ReviewNotes.State> {
    class State {
        /** Pull request key to the reviewer's own words about it. */
        @MapAnnotation(entryTagName = "note", keyAttributeName = "key", valueAttributeName = "text")
        var byPullRequest: MutableMap<String, String> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun notesFor(repository: String, pullRequestId: Int): String =
        state.byPullRequest[key(repository, pullRequestId)].orEmpty()

    /**
     * Notes are written as they are typed, not on leaving the field: a reviewer who types a note and
     * presses Review should not have to click away first to be sure it counted.
     */
    fun setNotes(repository: String, pullRequestId: Int, notes: String) {
        val key = key(repository, pullRequestId)
        val trimmed = notes.trim()
        // A blank note is an absent one. Storing empty strings would make the store grow for every
        // pull request ever looked at, and `""` is what a missing note already reads as.
        if (trimmed.isEmpty()) state.byPullRequest.remove(key) else state.byPullRequest[key] = trimmed
        if (state.byPullRequest.size > MAX_REMEMBERED) forgetOldest()
    }

    fun clear() {
        state.byPullRequest.clear()
    }

    /**
     * The oldest notes go, by insertion order, once there are more than any reviewer plausibly has
     * live. A project's pull requests are numbered upwards, and a note describes one of them, so the
     * least useful thing to keep is the note on the lowest-numbered pull request that is still
     * remembered.
     */
    private fun forgetOldest() {
        val excess = state.byPullRequest.size - MAX_REMEMBERED
        state.byPullRequest.keys.sortedBy { key -> key.substringAfter('#').toIntOrNull() ?: Int.MAX_VALUE }
            .take(excess)
            .forEach { state.byPullRequest.remove(it) }
    }

    private fun key(repository: String, pullRequestId: Int) = "$repository#$pullRequestId"

    companion object {
        private const val MAX_REMEMBERED = 200

        fun getInstance(project: Project): ReviewNotes = project.service()
    }
}
