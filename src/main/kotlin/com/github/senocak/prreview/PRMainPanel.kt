package com.github.senocak.prreview

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.util.Collections
import java.util.LinkedHashMap
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The tool window's contents: the repository it resolved, the open pull requests, and the review.
 *
 * The panel owns no repository or pull request state beyond the current list — everything that
 * costs a network call is re-derived per action. That keeps the window honest after a push, a
 * settings change or a fresh clone, none of which produce an event the panel could react to.
 */
class PRMainPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val prModel = DefaultListModel<PullRequest>()
    private val prList = JBList(prModel).apply {
        cellRenderer = PullRequestRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "Press Refresh to load open pull requests."
        // A busy repository has more pull requests than fit, and searching by id, title, author or
        // branch is how a reviewer finds the one they were told about.
        ListSpeedSearch.installOn(this) { it.toListRow() }
    }

    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        emptyText.text = "Select a pull request and run Review with LiteLLM."
    }

    /**
     * The reviewer's own words about this pull request, sent with the request.
     *
     * A diff cannot say why a pull request exists: that it implements a ticket nobody here can see,
     * that a file under a path is generated and should be left alone, that this project has settled
     * on a way of doing something. Without somewhere to say that, the only alternative is a review
     * confident about a change whose purpose it cannot know.
     */
    private val notes = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        // Notes are prose about a change, so they wrap and read; unlike the two diff panes, there
        // is nothing here to line up character by character.
        rows = NOTES_ROWS
        emptyText.text = "Optional. Anything the review needs to know that the diff cannot say: " +
            "what the pull request is for, which files are generated, the conventions this project " +
            "follows, where the specification lives."
        toolTipText = "Sent with the request, in your own words. Remembered per pull request."
    }

    /** Which pull request the notes on screen belong to, so a stale one is never sent. */
    private var notesForPullRequest: Int? = null

    private val reviewTitle = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val openInBrowser = ActionLink("Open in browser") { openSelectedPullRequest() }.apply {
        isVisible = false
    }

    private val status = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        text = NOT_REFRESHED
    }

    /** The changed files and the diff itself, shown as soon as a pull request is selected. */
    private val changesView = ChangesView()

    private val tabs = JBTabbedPane().apply {
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /**
     * The model the next review is asked to use.
     *
     * In the window rather than only in Settings because the model a reviewer wants is a property
     * of the review in front of them — a cheap model for a rename, an expensive one for a
     * concurrency change — and reaching into a settings page to change it once per pull request
     * would put it out of reach. The selection is for this session: Settings stays the default,
     * and this box is seeded from it.
     */
    private val modelComboBox = ComboBox<String>().apply {
        isEditable = true
        toolTipText = "The model the next review is asked to use. Change it in Settings to make it the default."
    }

    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar(PR_TOOL_WINDOW_ID, DefaultActionGroup().apply {
            // One group per action: ActionToolbar draws a group holding a single child as a plain
            // button, and collapses anything larger into a dropdown behind the first action.
            add(DefaultActionGroup(RefreshAction()))
            add(DefaultActionGroup(ReviewAction()))
        }, true)
        .apply { targetComponent = this@PRMainPanel }

    private var coordinates: BitbucketCoordinates? = null
    private var busy = false
    private var reviewed: PullRequest? = null

    /** How much the last review actually looked at, shown next to its result. */
    private var reviewScope: String? = null

    /**
     * Which tab a newly selected pull request opens on.
     *
     * Changes is the default because that is what a reviewer opened the pull request to look at,
     * but a reviewer working through a queue does not want to be thrown back to it every time, so
     * the last tab they chose is what the next one opens on.
     */
    private var preferredTab: String = CHANGE_TAB

    /**
     * The diff download in flight, cancelled when the selection moves on without it.
     *
     * The indicator rather than the task, because a task cannot be cancelled and only the indicator
     * is what actually stops the download.
     */
    private var diffIndicator: ProgressIndicator? = null

    /**
     * Diffs already downloaded, by pull request id.
     *
     * Shared between the Changes tab and the review so that a pull request whose changes are on
     * screen is not downloaded a second time to be reviewed — that is a full copy of the pull
     * request, and on a large one it is the slowest thing in the window. Bounded because a
     * reviewer moving down a list of fifty pull requests should not leave fifty diffs in memory.
     * Synchronized because the review task fills it from a background thread while a selection
     * reads it on the UI thread.
     */
    private val displayedDiff = DisplayedDiff()

    /**
     * A pull request's diff, reusing the copy already on screen when it is the same pull request.
     *
     * Only ever called for the pull request currently selected, so the single slot in
     * [DisplayedDiff] is all this needs.
     */
    private fun diffOf(settings: PluginSettings, found: BitbucketCoordinates, pullRequest: PullRequest): String {
        val key = DiffKey(pullRequest.id, pullRequest.updatedOn)
        displayedDiff.get(key)?.let { return it }
        val downloaded = BitbucketServerClient.diff(settings, found, pullRequest.id)
        displayedDiff.put(key, downloaded)
        return downloaded
    }

    /**
     * The models the gateway serves, fetched once per window.
     *
     * Null means not fetched yet; a fetch that failed is not retried on every refresh, because a
     * refresh is a request to Bitbucket and a gateway being down should not make every one of them
     * wait on a second timeout. It is retried when the settings change, which is when a wrong base
     * URL or a missing key is most likely to have been the cause.
     */
    private var models: List<String>? = null
    private var modelsBaseUrl: String? = null
    private var modelFetchFailed = false

    init {
        background = UIUtil.getPanelBackground()
        seedModelBox()
        add(toolbar.component, BorderLayout.NORTH)
        add(splitter(), BorderLayout.CENTER)
        add(statusBar(), BorderLayout.SOUTH)
        // Selecting a pull request is how a reviewer says "this one", so its changes are fetched
        // then and not on a second button press.
        prList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onPullRequestSelected()
        }
    }

    /**
     * Loads the selected pull request's diff and shows what it changes.
     *
     * Runs on a click through the list, so it has to be cheap when it can be: an already-loaded
     * pull request is shown from the cache, and a click that lands while a fetch for the previous
     * one is still running cancels that fetch rather than queueing another behind it. A reviewer
     * clicking down a list would otherwise leave a queue of diffs to download that nobody is
     * waiting for.
     */
    private fun onPullRequestSelected() {
        val pullRequest = selectedPullRequest ?: run {
            changesView.showLoading("Select a pull request to see what it changes.")
            loadNotes(null)
            return
        }
        selectTab(preferredTab)
        loadNotes(pullRequest)
        showDiff(pullRequest)
    }

    /**
     * Puts this pull request's notes on screen.
     *
     * Switching pull requests swaps the notes, so a note about one pull request can never be sent
     * with another. The listener is detached first because assigning the text is not something the
     * reviewer did, and saving one pull request's note into another's slot is the exact mistake this
     * prevents.
     */
    private fun loadNotes(pullRequest: PullRequest?) {
        val notesService = ReviewNotes.getInstance(project)
        notes.document.removeDocumentListener(notesListener)
        notesForPullRequest = pullRequest?.id
        notes.text = pullRequest
            ?.let { notesService.notesFor(repositoryKey(), it.id) }
            .orEmpty()
        notes.document.addDocumentListener(notesListener)
    }

    private val notesListener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = saveNotes()
        override fun removeUpdate(event: DocumentEvent) = saveNotes()
        override fun changedUpdate(event: DocumentEvent) = saveNotes()

        private fun saveNotes() {
            val id = notesForPullRequest ?: return
            ReviewNotes.getInstance(project).setNotes(repositoryKey(), id, notes.text)
        }
    }

    /**
     * Notes are stored against the repository as well as the pull request, so a change of Bitbucket
     * host in the settings does not carry one repository's notes over to another's pull request with
     * the same number.
     */
    private fun repositoryKey() = coordinates?.displayName.orEmpty()

    /**
     * Puts a downloaded diff on screen.
     *
     * An empty result is not treated as a pull request with no changes, because a pull request
     * cannot have none — one with no changes cannot be opened. A blank diff therefore means the text
     * was not the unified diff this reads, and the raw text is shown rather than an empty tab that
     * looks like a broken plugin.
     */
    private fun showDiffText(raw: String) {
        val files = parseUnifiedDiff(raw)
        if (files.isEmpty()) {
            changesView.showUnparsed(
                raw,
                "This diff is not in a format the plugin can break down; the raw text is below.",
            )
            return
        }
        changesView.show(files)
    }

    private fun showDiff(pullRequest: PullRequest) {
        diffIndicator?.cancel()
        changesView.showLoading("Loading the changes in #${pullRequest.id}…")
        val found = coordinates
        if (found == null) {
            // The list is only populated after a successful refresh, which resolves the repository
            // first, so this cannot normally happen; saying so beats an unexplained empty tab.
            changesView.showFailure("The repository for this project has not been resolved yet. Press Refresh.")
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Loading changes in #${pullRequest.id}",
            true,
        ) {
            private var diff: String? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                diffIndicator = indicator
                try {
                    val settings = PluginSettingsState.getInstance().settings()
                    diff = diffOf(settings, found, pullRequest)
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: Exception) {
                    failure = HttpTransport.describe(exception)
                }
            }

            override fun onSuccess() {
                diffIndicator = null
                val answer = diff
                if (answer == null) {
                    changesView.showFailure(failure ?: "The changes in #${pullRequest.id} could not be loaded.")
                    return
                }
                displayedDiff.put(DiffKey(pullRequest.id, pullRequest.updatedOn), answer)
                // The user may have moved on while this was downloading.
                if (selectedPullRequest?.id != pullRequest.id) return
                showDiffText(answer)
            }
        })
    }

    /**
     * Pull requests on top, the review below, with the divider left where the user put it.
     *
     * The review is a wall of text and the list is navigation, so the split is horizontal at two
     * thirds in favour of the list. The text area wraps rather than scrolls sideways, because a
     * review read with a horizontal scrollbar is a review nobody reads in a left-hand dock.
     */
    private fun splitter(): JBSplitter = JBSplitter(true).apply {
        proportion = 0.38f
        firstComponent = JBScrollPane(prList).apply {
            border = JBUI.Borders.empty()
        }
        secondComponent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            // Changes and Review are tabs rather than two stacked panes: a review is read against
            // the diff it is about, and a left dock is too narrow to give both their own scrollbar
            // without the diff becoming a strip of truncated lines.
            add(
                tabs.apply {
                    addTab(CHANGE_TAB, AllIcons.Actions.Diff, changesView)
                    addTab(REVIEW_TAB, AllIcons.Actions.Execute, reviewPane())
                    addChangeListener { onTabChanged() }
                },
                BorderLayout.CENTER,
            )
        }.apply { border = JBUI.Borders.empty() }
    }

    /**
     * The review tab: what the reviewer has to say about this pull request, then the answer.
     *
     * The notes sit above the answer rather than in a dialog, because they are part of how the
     * review is asked for and a reviewer rewrites them between one press of Review and the next. The
     * field keeps a fixed number of rows and scrolls past that: a note is usually a sentence or two,
     * and a dock a few hundred pixels wide should not spend itself on an empty text box.
     */
    private fun reviewPane(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(outputHeader(), BorderLayout.NORTH)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 4, 0)
                add(notesCaption(), BorderLayout.NORTH)
                add(
                    JBScrollPane(notes).apply {
                        border = JBUI.Borders.customLine(JBColor.border())
                        viewport.background = UIUtil.getPanelBackground()
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.SOUTH,
        )
        add(
            JBScrollPane(output).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
    }

    private fun notesCaption(): JBLabel = JBLabel(NOTES_CAPTION).apply {
        font = JBUI.Fonts.label().apply { deriveFont(JBUI.Fonts.label().size - 1f) }
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 0, 2, 0)
    }

    /** Selects a tab by name, so the code below does not depend on the tab's index. */
    private fun selectTab(name: String) {
        for (tab in 0 until tabs.tabCount) {
            if (tabs.getTitleAt(tab) == name) tabs.selectedIndex = tab
        }
    }

    private fun onTabChanged() {
        // A pull request is about its changes, so selecting one shows them. Switching to the
        // Review tab by hand is remembered for the next one, because a reviewer working through a
        // queue wants to stay where they were.
        if (tabs.selectedIndex >= 0) {
            preferredTab = tabs.getTitleAt(tabs.selectedIndex)
        }
    }

    private fun outputHeader(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(6, 8, 4, 8),
        )
        add(reviewTitle, BorderLayout.CENTER)
        add(openInBrowser, BorderLayout.EAST)
    }

    /**
     * Fills the window's model box from what the fetch returned.
     *
     * A null list means the fetch did not happen or did not work, and the box is left exactly as it
     * was: seeded from the saved model, still typed into if that is what it holds. The list is the
     * convenience, never the source of truth — a reviewer whose model is not in it is not blocked.
     */
    private fun applyModelList(fetched: List<String>?, baseUrl: String?) {
        if (fetched == null) return
        models = fetched
        modelsBaseUrl = baseUrl
        modelFetchFailed = fetched.isEmpty()
        fillModelCombo(modelComboBox, fetched)
    }

    /**
     * Whether the cached list can still be believed.
     *
     * Keyed to the base URL rather than to the key: a key that is wrong produces a 401 on the
     * review, which is reported where it happens, while a URL that has changed is a different
     * gateway whose models are a different list. A fetch that failed is always retried, so fixing
     * either setting and pressing Refresh is enough to recover.
     */
    private fun needsModelFetch(baseUrl: String): Boolean =
        models == null || modelFetchFailed || modelsBaseUrl != baseUrl

    /** Puts the saved model in the box, before the gateway has been asked anything. */
    private fun seedModelBox() {
        val configured = PluginSettingsState.getInstance().summary().model
        if (configured.isNotBlank()) {
            modelComboBox.selectedItem = configured
        }
    }

    /** The model the next review will be sent to. */
    private val reviewModel: String
        get() = selectedModel(modelComboBox).ifBlank { PluginSettingsState.getInstance().summary().model }

    private fun statusBar(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(4, 8),
        )
        isOpaque = false
        add(status, BorderLayout.CENTER)
        // East of the status line rather than under the toolbar: in a left dock the toolbar row is
        // the narrowest thing on screen, and a model name is the longest string this window shows.
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(modelComboBox, BorderLayout.CENTER)
            },
            BorderLayout.EAST,
        )
    }

    private val selectedPullRequest: PullRequest? get() = prList.selectedValue

    /** What the Review action's enabled state is derived from; a second run is never useful. */
    private val canReview: Boolean get() = !busy && selectedPullRequest != null

    /**
     * Re-reads the remotes and refetches the open pull requests.
     *
     * Every step that can fail says so in a way the user can act on. The most common failure is
     * not a server error at all but a remote that is not a Bitbucket one, so the dialog lists the
     * remotes that were found, exactly as they appear in `.git/config` — that is the file the user
     * is going to go and look at next.
     */
    fun refresh() {
        if (busy) return
        // A refresh is the reviewer saying the server has moved on, so anything remembered about the
        // pull requests is now a guess. Without this the timestamp on the key would usually catch it,
        // but "usually" is the wrong word for a reviewer looking for a commit they just pushed.
        displayedDiff.clear()
        PluginSettingsState.getInstance().summary().problem()?.let { return promptForConfiguration(it) }
        busy = true
        notes.isEnabled = false
        setStatus("Reading Git remotes…", STATUS_PENDING)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading open pull requests…", true) {
            private var remotes: List<GitRemoteRef> = emptyList()
            private var pullRequests: List<PullRequest> = emptyList()
            private var failure: String? = null
            private var fetchedModels: List<String>? = null
            private var fetchedBaseUrl: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text2 = "Reading .git/config…"
                    remotes = GitConfigDetector.remoteRefs(project)
                    val configuredHost = hostOf(PluginSettingsState.getInstance().summary().bitbucketBaseUrl)
                    val found = GitConfigDetector.detect(project, configuredHost)
                    if (found == null) {
                        failure = describeUnresolvedRepository(remotes)
                        return
                    }
                    coordinates = found
                    val settings = PluginSettingsState.getInstance().settings()
                    settings.problem()?.let {
                        failure = it
                        return
                    }
                    pullRequests = BitbucketServerClient.openPullRequests(settings, found, indicator)
                    // After the list, not before it: a gateway that is unreachable must not delay
                    // the pull requests, and a failure here must not fail the refresh.
                    indicator.checkCanceled()
                    if (needsModelFetch(settings.litellmBaseUrl)) {
                        indicator.text2 = "Loading models…"
                        fetchedBaseUrl = settings.litellmBaseUrl
                        fetchedModels = runCatching { LiteLLMClient.models(settings.litellmBaseUrl, settings.litellmApiKey) }
                            .onFailure { if (it !is ProcessCanceledException) modelFetchFailed = true }
                            .getOrNull()
                    }
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: Exception) {
                    failure = HttpTransport.describe(exception)
                }
            }

            override fun onSuccess() {
                applyModelList(fetchedModels, fetchedBaseUrl)
                val problem = failure
                if (problem != null) {
                    setStatus("Could not load pull requests", STATUS_FAILURE)
                    PluginNotifications.error(project, problem)
                    return
                }
                val found = coordinates ?: return
                val previous = selectedPullRequest?.id
                prModel.removeAllElements()
                pullRequests.forEach(prModel::addElement)
                prList.selectedIndex = pullRequests.indexOfFirst { it.id == previous }
                prList.emptyText.text = if (pullRequests.isEmpty()) {
                    "No open pull requests in ${found.displayName}."
                } else {
                    "No pull requests match the filter."
                }
                describeRepository(found, pullRequests.size)
            }

            override fun onFinished() {
                busy = false
                // A note edited after the request was sent would not be in that answer, so the field
                // is held still while one is in flight rather than quietly not counting.
                notes.isEnabled = true
                toolbar.updateActionsAsync()
            }
        })
    }

    /**
     * Sends the selected pull request's diff to the model and shows the answer.
     *
     * The diff is fetched here rather than when the list was loaded: it is the expensive call, it
     * is per pull request, and a list that is ten minutes old is still worth reviewing.
     */
    fun review() {
        val pullRequest = selectedPullRequest ?: return
        if (busy) return
        PluginSettingsState.getInstance().summary().problem()?.let { return promptForConfiguration(it) }
        // Read here, on the EDT, and passed in: the box is component state, and the background task
        // has no business touching a component — or a model the user may still be changing.
        val model = reviewModel
        busy = true
        reviewScope = null
        showReviewHeader(pullRequest, "Fetching the diff…")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reviewing pull request #${pullRequest.id}…", true) {
            private var review: String? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val settings = PluginSettingsState.getInstance().settings()
                    settings.problem()?.let {
                        failure = it
                        return
                    }
                    val found = coordinates
                        ?: GitConfigDetector.detect(project, hostOf(settings.bitbucketBaseUrl))?.also { coordinates = it }
                        ?: run {
                            failure = describeUnresolvedRepository(GitConfigDetector.remoteRefs(project))
                            return
                        }
                    indicator.text2 = "Downloading the diff…"
                    // Parsed here rather than in the client: the same parsed changes are what the
                    // Changes tab shows, and handing over the model has to write a review about
                    // every added and modified line means the line numbers have to be real.
                    val changed = parseUnifiedDiff(diffOf(settings, found, pullRequest))
                    // Decided before the model is asked, because "no actionable findings" about a
                    // diff that never arrived is worse than an error: it is a clean bill of health
                    // for a pull request nobody looked at.
                    when (val scope = reviewScopeOf(changed)) {
                        ReviewScope.Unreadable -> {
                            failure = "The diff for #${pullRequest.id} ${scope.describe()}"
                            return
                        }
                        ReviewScope.NoCode -> {
                            review = scope.describe()
                            return
                        }
                        is ReviewScope.Ready -> {
                            val note = notes.text.trim()
                            reviewScope = scope.describe().let { described ->
                                if (note.isEmpty()) described else "$described · with your notes"
                            }
                            indicator.text2 = "Waiting for $model…"
                            review = LiteLLMClient.review(settings, pullRequest, changed, note, model)
                        }
                    }
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: Exception) {
                    failure = HttpTransport.describe(exception)
                }
            }

            override fun onSuccess() {
                val answer = review
                if (answer == null) {
                    val problem = failure ?: "The review could not be produced."
                    showReviewHeader(pullRequest, "Review failed")
                    setOutput(problem)
                    Messages.showErrorDialog(project, asDialogHtml(problem), DIALOG_TITLE)
                    return
                }
                showReviewHeader(pullRequest, reviewScope)
                setOutput(answer)
                // The reviewer pressed Review and is now on the Changes tab reading a diff; an
                // answer they have to go and find is an answer they do not read.
                selectTab(REVIEW_TAB)
            }

            override fun onFinished() {
                busy = false
                // A note edited after the request was sent would not be in that answer, so the field
                // is held still while one is in flight rather than quietly not counting.
                notes.isEnabled = true
                toolbar.updateActionsAsync()
            }
        })
    }

    private fun showReviewHeader(pullRequest: PullRequest, subtitle: String?) {
        reviewed = pullRequest
        val coordinatesText = coordinates?.let { "${it.host} · ${it.displayName}" }.orEmpty()
        reviewTitle.text = buildString {
            append("#")
            append(pullRequest.id)
            append("  ")
            append(pullRequest.title)
            if (coordinatesText.isNotBlank()) {
                append("   —   ")
                append(coordinatesText)
            }
            if (subtitle != null) {
                append("   —   ")
                append(subtitle)
            }
        }
        reviewTitle.toolTipText = pullRequest.link
        openInBrowser.isVisible = !pullRequest.link.isNullOrBlank()
    }

    private fun setOutput(text: String) {
        output.text = text
        output.caretPosition = 0
    }

    private fun openSelectedPullRequest() {
        val url = reviewed?.link ?: return
        BrowserUtil.browse(url)
    }

    private fun describeRepository(found: BitbucketCoordinates, count: Int) {
        val baseUrl = PluginSettingsState.getInstance().summary().bitbucketBaseUrl
        val summary = if (count == 1) "1 open pull request" else "$count open pull requests"
        setStatus(
            "${found.remoteName} · ${found.host} · ${found.displayName} · $summary",
            if (found.differsFrom(baseUrl)) STATUS_PENDING else STATUS_READY,
        )
        status.toolTipText = if (found.differsFrom(baseUrl)) {
            "The remote points at ${found.host}, but the configured base URL is $baseUrl. " +
                "Requests are sent to the configured base URL."
        } else {
            found.remoteUrl
        }
    }

    /**
     * Explains what the remotes actually are.
     *
     * Listed verbatim, and not just "not found": the fix is nearly always a wrong remote URL, and
     * the fastest way to spot one is to see it next to the Bitbucket form it should take.
     */
    private fun describeUnresolvedRepository(remotes: List<GitRemoteRef>): String {
        if (remotes.isEmpty()) {
            return if (GitConfigDetector.hasGitRepository(project)) {
                "This project's .git/config has no remotes.\n\nAdd one with:\n" +
                    "    git remote add origin <bitbucket-clone-url>"
            } else {
                "This project is not a Git repository, so there is no remote to read."
            }
        }
        return buildString {
            append("No Bitbucket Server remote was found in this project's .git/config.\n\n")
            append("Remotes found:")
            remotes.forEach {
                append("\n    ")
                append(it.name)
                append("  ")
                append(it.url)
            }
            append("\n\nA Bitbucket clone URL looks like:\n")
            append("    ssh://git@bitbucket.company.com:7999/PROJECT_KEY/repo-slug.git")
        }
    }

    private fun promptForConfiguration(problem: String) {
        val choice = Messages.showDialog(
            project,
            asDialogHtml("$problem\n\nOpen the settings now?"),
            DIALOG_TITLE,
            arrayOf("Open Settings", "Cancel"),
            0,
            Messages.getQuestionIcon(),
        )
        if (choice == 0) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PR_SETTINGS_ID)
        }
    }

    private fun setStatus(text: String, color: Color) {
        status.text = text
        status.foreground = color
        status.toolTipText = null
    }

    override fun dispose() {
        // A diff download left running against a closed tool window would keep a background thread
        // and a progress indicator alive for a reader that no longer exists.
        diffIndicator?.cancel()
        diffIndicator = null
    }

    private inner class RefreshAction : AnAction(
        "Refresh",
        "Re-read .git/config and fetch open pull requests from Bitbucket Server",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) = this@PRMainPanel.refresh()

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !this@PRMainPanel.busy
        }
    }

    private inner class ReviewAction : AnAction(
        "Review with LiteLLM",
        "Download this pull request's diff and send it to the configured LiteLLM model",
        AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(event: AnActionEvent) = this@PRMainPanel.review()

        override fun update(event: AnActionEvent) {
            val selected = this@PRMainPanel.selectedPullRequest
            event.presentation.isEnabled = this@PRMainPanel.canReview
            event.presentation.text = selected?.let { "Review #${it.id} with LiteLLM" } ?: "Review with LiteLLM"
        }
    }

    private companion object {
        /** Fixed rather than growing with the text: a note is a sentence or two, not an essay. */
        const val NOTES_ROWS = 4

        const val NOTES_CAPTION = "Notes for the model — sent with the request, in your own words"

        const val DIALOG_TITLE = "LiteLLM PR Review"
        const val CHANGE_TAB = "Changes"
        const val REVIEW_TAB = "Review"
        const val NOT_REFRESHED = "Press Refresh to load open pull requests."

        val STATUS_READY: Color = JBColor.GRAY
        val STATUS_PENDING: Color = JBColor(Color(0x9A, 0x67, 0x00), Color(0xD8, 0xB2, 0x4C))
        val STATUS_FAILURE: Color = JBColor(Color(0xC6, 0x28, 0x28), Color(0xFF, 0x6B, 0x68))
    }
}

/**
 * `Messages` dialogs render their text as HTML, so a `\n` in a multi-line message collapses into
 * a single line. The messages here are lists of remote URLs, which is exactly the case that
 * breaks, so the conversion happens here rather than being remembered at each call site.
 */
internal fun asDialogHtml(message: String): String = buildString {
    append("<html>")
    message.lines().forEach { line ->
        append(line.escapeHtml())
        append("<br>")
    }
    append("</html>")
}

private fun String.escapeHtml(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/** One row per pull request: id, then title, then the metadata that tells two PRs apart. */
private class PullRequestRenderer : ColoredListCellRenderer<PullRequest>() {
    override fun customizeCellRenderer(
        list: JList<out PullRequest>,
        value: PullRequest?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        append("#${value.id}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val meta = buildString {
            if (value.author.isNotBlank()) {
                append(value.author)
                append(" · ")
            }
            append(value.sourceBranch)
            append(" → ")
            append(value.targetBranch)
        }
        if (meta.isNotBlank()) append("   $meta", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
