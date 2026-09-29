package com.github.senocak.prreview

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.net.URI
import java.net.URISyntaxException
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Must match the `applicationConfigurable` id in plugin.xml: the tool window opens the settings
 * page by this string when a field it needs has not been filled in.
 */
const val PR_SETTINGS_ID = "com.github.senocak.prreview.settings"

/**
 * The Settings | Tools | LiteLLM PR Review page.
 *
 * The two secrets go to Password Safe and the rest to the plugin's own XML, so the layout here
 * only has to mirror that split: the two password fields and everything else.
 *
 * The model is a drop-down fed by the gateway's own `/models` rather than a text field. The names
 * are the ones this installation can actually route — including the aliases a team has defined in
 * its `config.yaml` — and they move with every provider release, so a field asking the user to
 * type one is a field that goes stale. The box stays editable for the two cases the endpoint
 * cannot cover: a gateway that is down while the page opens, and a model served but not listed.
 */
class PluginSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var bitbucketUrlField: JBTextField? = null
    private var tokenField: JBPasswordField? = null
    private var litellmUrlField: JBTextField? = null
    private var litellmKeyField: JBPasswordField? = null
    private var modelComboBox: ComboBox<String>? = null
    private var endpointHint: JBLabel? = null
    private var modelHint: JBLabel? = null

    /** Guards the callbacks below once the page has been closed. */
    private var uiDisposable: Disposable = Disposer.newDisposable("LiteLLM PR Review settings")

    /**
     * Debounces the model fetch behind the URL field.
     *
     * Without it every keystroke of a URL is a request, which on a proxy that takes a second to
     * answer its `/models` is a queue of requests the user cannot cancel and did not ask for.
     */
    private var modelAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, uiDisposable)

    /** The last failure, so a re-fetch is triggered by a key being typed and not by anything else. */
    private var modelFetchFailed = false

    override fun getDisplayName() = PR_SETTINGS_ID_DISPLAY_NAME

    override fun createComponent(): JComponent {
        val fields = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }
        fun addRow(row: Int, label: String, component: JComponent, fill: Int = GridBagConstraints.HORIZONTAL) {
            constraints.gridx = 0; constraints.gridy = row; constraints.weightx = 0.0; constraints.fill = GridBagConstraints.NONE
            fields.add(JLabel(label), constraints)
            constraints.gridx = 1; constraints.weightx = 1.0; constraints.fill = fill
            fields.add(component, constraints)
        }

        bitbucketUrlField = JBTextField().apply {
            emptyText.text = "https://bitbucket.company.com"
            toolTipText = "The Bitbucket Server base URL. A pasted /rest/api/1.0 or /scm suffix is removed."
        }
        tokenField = JBPasswordField().apply {
            emptyText.text = "Personal access token with Read pull request access"
            toolTipText = "Stored in Password Safe, not in the plugin's settings file."
        }
        litellmUrlField = JBTextField().apply {
            emptyText.text = "http://localhost:4000/v1"
            toolTipText = "The gateway root. /chat/completions is appended to it."
        }
        litellmKeyField = JBPasswordField().apply {
            emptyText.text = "Leave empty for an unauthenticated proxy"
            toolTipText = "Optional. Only needed when the proxy was started with LITELLM_MASTER_KEY."
        }
        modelComboBox = ComboBox<String>().apply {
            isEditable = true
            toolTipText = "The models your gateway serves. Select one, or type a name it does not list."
        }
        // Names the URL that will actually be called, so the /v1 the plugin appends is not a rule
        // the user has to discover.
        endpointHint = hintLabel()
        modelHint = hintLabel()

        val litellmUrlRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(litellmUrlField, BorderLayout.CENTER)
            add(endpointHint, BorderLayout.SOUTH)
        }
        val modelRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(modelComboBox, BorderLayout.CENTER)
                    add(modelHint, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            add(
                JButton("Refresh").apply {
                    toolTipText = "Ask the gateway which models it serves."
                    addActionListener { refreshModels(userInitiated = true) }
                },
                BorderLayout.EAST,
            )
        }

        addRow(0, "Bitbucket Base URL:", bitbucketUrlField!!)
        addRow(1, "Access Token:", tokenField!!)
        addRow(2, "LiteLLM Base URL:", litellmUrlRow)
        addRow(3, "LiteLLM API Key:", litellmKeyField!!)
        addRow(4, "Model:", modelRow)

        val note = JBLabel(
            "<html>Both tokens are kept in IntelliJ Password Safe.<br>" +
                "Open pull requests are read from the Git remote of this project.</html>",
        ).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }

        panel = JPanel(BorderLayout()).apply {
            add(fields, BorderLayout.NORTH)
            add(
                JBScrollPane(note).apply { border = JBUI.Borders.empty() },
                BorderLayout.CENTER,
            )
        }

        reset()
        // Registered after reset(): populating the fields from saved settings is not an edit, and
        // treating it as one would make the page look modified the moment it opens.
        litellmUrlField!!.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                paintEndpointHint()
                // Only while the list is missing: once it is loaded the box is usable, and a
                // further keystroke in the URL is not a request to make.
                if (modelComboBox!!.itemCount <= 1) scheduleModelFetch()
            }
        })
        // A key is typed one character at a time, so it cannot be fetched on every change. It is
        // fetched on the way out of the field, but only while there is nothing to choose from.
        litellmKeyField!!.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                if (modelFetchFailed || modelComboBox?.itemCount ?: 0 <= 1) refreshModels(userInitiated = false)
            }
        })
        paintEndpointHint()
        if (PluginSettingsState.getInstance().summary().litellmBaseUrl.isNotBlank()) refreshModels(userInitiated = false)
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PluginSettingsState.getInstance().settings()
        return bitbucketUrlField?.text?.trim() != settings.bitbucketBaseUrl ||
            String(tokenField?.password ?: CharArray(0)) != settings.bitbucketToken ||
            litellmUrlField?.text?.trim() != settings.litellmBaseUrl ||
            String(litellmKeyField?.password ?: CharArray(0)) != settings.litellmApiKey ||
            selectedModel() != settings.model
    }

    override fun apply() {
        val bitbucketBaseUrl = requireHttpUrl(bitbucketUrlField?.text, "Bitbucket Base URL")
        val litellmBaseUrl = requireHttpUrl(litellmUrlField?.text, "LiteLLM Base URL")
        PluginSettingsState.getInstance().update(
            bitbucketBaseUrl = bitbucketBaseUrl,
            bitbucketToken = String(tokenField?.password ?: CharArray(0)),
            litellmBaseUrl = litellmBaseUrl,
            litellmApiKey = String(litellmKeyField?.password ?: CharArray(0)),
            model = selectedModel(),
        )
        // Show back what was stored: normalising rewrites both URLs, and leaving the pre-typed text
        // on screen would misreport what the plugin will call.
        reset()
    }

    override fun reset() {
        val settings = PluginSettingsState.getInstance().settings()
        bitbucketUrlField?.text = settings.bitbucketBaseUrl
        tokenField?.text = settings.bitbucketToken
        litellmUrlField?.text = settings.litellmBaseUrl
        litellmKeyField?.text = settings.litellmApiKey
        modelComboBox?.selectedItem = settings.model
    }

    override fun disposeUIResources() {
        modelAlarm.cancelAllRequests()
        Disposer.dispose(uiDisposable)
        // A new page creates a new alarm, so the disposed one must not be handed to a second.
        uiDisposable = Disposer.newDisposable("LiteLLM PR Review settings")
        modelAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, uiDisposable)
        panel = null
        bitbucketUrlField = null
        tokenField = null
        litellmUrlField = null
        litellmKeyField = null
        modelComboBox = null
        endpointHint = null
        modelHint = null
    }

    /** The model as the user left it, whether picked from the list or typed. */
    private fun selectedModel(): String = modelComboBox?.let { selectedModel(it) }.orEmpty()

    private fun scheduleModelFetch() {
        modelAlarm.cancelAllRequests()
        modelAlarm.addRequest({ refreshModels(userInitiated = false) }, MODEL_FETCH_DELAY_MS)
    }

    /**
     * Fills the drop-down from the gateway, off the EDT.
     *
     * The URL and the key are read here, on the EDT, and handed to the background task: the
     * unsaved values, so a gateway can be checked before it is applied, and read from a password
     * field where they have to be. A fetch is never allowed to stop the page being used — the box
     * is editable, and a failure is reported in place with the saved model left in it.
     */
    private fun refreshModels(userInitiated: Boolean) {
        val base = normalizeLiteLlmBaseUrl(litellmUrlField?.text.orEmpty())
        if (base.isBlank()) {
            if (userInitiated) {
                modelHint?.text = "Set the LiteLLM base URL first."
            }
            return
        }
        val apiKey = String(litellmKeyField?.password ?: CharArray(0))
        if (userInitiated) modelHint?.text = "Loading models…"

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            null,
            "Loading LiteLLM models",
            true,
        ) {
            private var models: List<String> = emptyList()
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    models = LiteLLMClient.models(base, apiKey)
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: Exception) {
                    failure = HttpTransport.describe(exception)
                }
            }

            override fun onSuccess() {
                val problem = failure
                if (problem != null) {
                    // The saved model stays selected and the page stays usable: the endpoint being
                    // unreachable says nothing about which model the user wanted.
                    modelFetchFailed = true
                    modelHint?.text = "Could not load the model list: $problem"
                    return
                }
                modelFetchFailed = models.isEmpty()
                applyModels(models)
            }

            override fun onThrowable(error: Throwable) {
                modelFetchFailed = true
                modelHint?.text = "Could not load the model list: ${error.message}"
            }
        })
    }

    /**
     * Replaces the drop-down contents while keeping the selection.
     *
     * The selection is put back by hand because an editable combo drops the editor's text when the
     * model is replaced, and a saved model the gateway does not list has to survive that.
     */
    private fun applyModels(models: List<String>) {
        val combo = modelComboBox ?: return
        fillModelCombo(combo, models)
        modelHint?.text = when {
            models.isEmpty() -> "The gateway reports no models. Type a name to use one anyway."
            models.size == 1 -> "1 model available."
            else -> "${models.size} models available."
        }
    }

    /**
     * Refuses a URL that cannot be called, naming the field.
     *
     * A wrong base URL is the one setting mistake that produces no visible symptom until a button
     * is pressed in a tool window, so it is worth catching while the user is looking at the form.
     */
    private fun requireHttpUrl(text: String?, field: String): String {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) throw ConfigurationException("$field is not set.")
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            throw ConfigurationException("$field is not a valid URL: $trimmed")
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            throw ConfigurationException("$field must be an http:// or https:// URL: $trimmed")
        }
        return trimmed
    }

    /** Names the URL that will actually be called, so the `/v1` the plugin appends is not a rule to discover. */
    private fun paintEndpointHint() {
        val base = normalizeLiteLlmBaseUrl(litellmUrlField?.text.orEmpty())
        endpointHint?.text = if (base.isBlank()) " " else "Calls $base/chat/completions"
    }

    private fun hintLabel(): JBLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    private companion object {
        const val PR_SETTINGS_ID_DISPLAY_NAME = "LiteLLM PR Review"

        /** Long enough that a URL is not fetched per keystroke, short enough to feel immediate. */
        const val MODEL_FETCH_DELAY_MS = 700
    }
}
