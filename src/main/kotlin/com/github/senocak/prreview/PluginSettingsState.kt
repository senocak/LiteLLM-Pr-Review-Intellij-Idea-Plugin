package com.github.senocak.prreview

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

private const val DEFAULT_MODEL = "claude-3-5-sonnet"

/**
 * Path segments a user realistically pastes into the Bitbucket field. All of them are legal to
 * append to the base URL that the REST client builds, but none of them are the base URL itself,
 * and a plugin that then 404s on a URL the user can see in their own address bar looks broken.
 */
private val BITBUCKET_URL_SUFFIXES = listOf(
    "/rest/api/1.0",
    "/rest/api/latest",
    "/rest",
    "/scm",
)

/**
 * The one endpoint suffix that is stripped. A `/v1` that came before it is left alone, so both
 * `http://host:4000/chat/completions` and `http://host:4000/v1/chat/completions` reduce to the
 * same base, and the `/v1` rule below then applies to whichever of the two is left.
 */
private const val LITELLM_ENDPOINT_SUFFIX = "/chat/completions"

/**
 * The host of a base URL, without the port, userinfo or path.
 *
 * Used to decide whether a Git remote and the configured Bitbucket URL are talking to the same
 * server, which is the only way to tell a Bitbucket remote from a GitHub one — the clone URLs are
 * the same shape. The port is dropped on purpose: the REST calls go to the configured base URL
 * while a remote may be SSH, and the two are not required to agree on it.
 */
internal fun hostOf(baseUrl: String): String? =
    baseUrl.trim().takeIf { it.isNotEmpty() }
        ?.substringAfter("://", "")
        ?.substringBefore('/')
        ?.substringBefore('@')
        ?.substringBefore(':')
        ?.takeIf { it.isNotEmpty() }

/**
 * Reduces a pasted URL to the base the REST paths hang off.
 *
 * Two shapes are handled. A URL that already ends in a path segment the plugin appends itself
 * (`/rest/api/1.0`) is trimmed. A URL that goes on to name a *repository* — a clone or browse URL
 * such as `.../scm/PROJECT/repo.git` — is cut at the `/scm` marker, because the repository is
 * read from Git and the base URL must not carry it.
 *
 * The marker is matched as a whole path segment, so a host like `https://scm.company.com` and a
 * project key that happens to contain "scm" are both left alone. Idempotent, so it is also safe
 * to apply to a value that has already been normalised.
 */
internal fun normalizeBitbucketBaseUrl(url: String): String {
    var base = url.trim().trimEnd('/')
    if (base.isEmpty()) return base
    while (true) {
        val suffix = BITBUCKET_URL_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) } ?: break
        base = base.dropLast(suffix.length).trimEnd('/')
    }
    val segments = base.split('/')
    val marker = segments.indexOfFirst { it.equals("scm", ignoreCase = true) }
    // Past the host (index 2 is the first path segment) and with something after it, so a base URL
    // that merely ends in a segment called "scm" is not emptied.
    return if (marker > 2 && marker < segments.lastIndex) {
        segments.take(marker).joinToString("/")
    } else {
        base
    }
}

/**
 * Reduces a pasted URL to the OpenAI-compatible root, i.e. the part `/chat/completions` is
 * appended to.
 *
 * `/v1` is added only when no path was given at all. `http://localhost:4000` is a complete and
 * correct LiteLLM answer, while `https://gateway.company.com/litellm` may well be a gateway
 * mounted on a sub-path whose version segment is already included, and second-guessing it would
 * break the configuration that works.
 */
internal fun normalizeLiteLlmBaseUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    var base = trimmed
    if (base.endsWith(LITELLM_ENDPOINT_SUFFIX, ignoreCase = true)) {
        base = base.dropLast(LITELLM_ENDPOINT_SUFFIX.length).trimEnd('/')
    }
    return if (base.substringAfter("://", "").contains('/')) base else "$base/v1"
}

/**
 * The non-secret half of the settings, safe to read on the EDT.
 *
 * The token is deliberately absent rather than blanked: Password Safe reads trip the platform's
 * slow-operation assertion, and this is read while a button is being pressed. [PluginSettings]
 * carries the token and is only ever built inside a background task.
 */
data class PluginSettingsSummary(
    val bitbucketBaseUrl: String,
    val litellmBaseUrl: String,
    val model: String,
) {
    val ready: Boolean
        get() = bitbucketBaseUrl.isNotBlank() && litellmBaseUrl.isNotBlank() && model.isNotBlank()

    /** What the user still has to fill in, phrased for a dialog. Null when the visible half is complete. */
    fun problem(): String? = when {
        bitbucketBaseUrl.isBlank() -> "Set the Bitbucket base URL in Settings | Tools | LiteLLM PR Review."
        litellmBaseUrl.isBlank() -> "Set the LiteLLM base URL in Settings | Tools | LiteLLM PR Review."
        model.isBlank() -> "Set the model name in Settings | Tools | LiteLLM PR Review."
        else -> null
    }
}

/** The complete configuration. Reading either secret out of this costs a Password Safe lookup. */
data class PluginSettings(
    val bitbucketBaseUrl: String,
    val bitbucketToken: String,
    val litellmBaseUrl: String,
    val litellmApiKey: String,
    val model: String,
) {
    val litellmChatUrl: String get() = "$litellmBaseUrl/chat/completions"
    val litellmModelsUrl: String get() = "$litellmBaseUrl/models"

    fun problem(): String? = PluginSettingsSummary(bitbucketBaseUrl, litellmBaseUrl, model).problem() ?: when {
        // A visible field that is filled in but whose secret is missing is the one gap
        // [PluginSettingsSummary] cannot cover, and the one that produces a bare 401 later.
        bitbucketToken.isBlank() -> "Save a Bitbucket personal access token in Settings | Tools | LiteLLM PR Review."
        else -> null
    }
}

@State(name = "LiteLLMPrReviewSettings", storages = [Storage("litellmPrReview.xml")])
@Service(Service.Level.APP)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState.State> {
    class State {
        var bitbucketBaseUrl: String = ""
        var litellmBaseUrl: String = ""
        var model: String = DEFAULT_MODEL
    }

    private var state = State()

    /**
     * Password Safe reads are slow operations and the panel asks for the secrets on every refresh,
     * so the values are cached. The caches are only ever written by [update], which is the only
     * moment either can change.
     */
    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedApiKey: String? = null

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        // Settings saved from an earlier, more literal version of these fields may hold a pasted
        // full endpoint URL. Normalising on load means the Settings page shows the base the plugin
        // actually calls rather than a path it ignores.
        this.state.bitbucketBaseUrl = normalizeBitbucketBaseUrl(this.state.bitbucketBaseUrl)
        this.state.litellmBaseUrl = normalizeLiteLlmBaseUrl(this.state.litellmBaseUrl)
    }

    /** Off the EDT only: reads both secrets from Password Safe. */
    fun settings(): PluginSettings = PluginSettings(
        bitbucketBaseUrl = state.bitbucketBaseUrl,
        bitbucketToken = token(),
        litellmBaseUrl = state.litellmBaseUrl,
        litellmApiKey = apiKey(),
        model = state.model.trim(),
    )

    fun summary(): PluginSettingsSummary = PluginSettingsSummary(
        bitbucketBaseUrl = state.bitbucketBaseUrl,
        litellmBaseUrl = state.litellmBaseUrl,
        model = state.model.trim(),
    )

    fun update(
        bitbucketBaseUrl: String,
        bitbucketToken: String,
        litellmBaseUrl: String,
        litellmApiKey: String,
        model: String,
    ) {
        state.bitbucketBaseUrl = normalizeBitbucketBaseUrl(bitbucketBaseUrl)
        state.litellmBaseUrl = normalizeLiteLlmBaseUrl(litellmBaseUrl)
        state.model = model.trim().ifBlank { DEFAULT_MODEL }
        cachedToken = bitbucketToken.trim()
        cachedApiKey = litellmApiKey.trim()
        PasswordSafe.instance.set(TOKEN_ATTRIBUTES, cachedToken.orEmpty().toCredentials(TOKEN_USER))
        PasswordSafe.instance.set(API_KEY_ATTRIBUTES, cachedApiKey.orEmpty().toCredentials(API_KEY_USER))
    }

    /**
     * An empty field means "forget the stored secret", not "store an empty one": leaving a blank
     * credential behind would keep the old secret alive after it had been cleared on screen.
     */
    private fun String.toCredentials(user: String) = takeIf { it.isNotEmpty() }?.let { Credentials(user, it) }

    private fun token(): String = cachedToken ?: PasswordSafe.instance
        .get(TOKEN_ATTRIBUTES)?.getPasswordAsString().orEmpty()
        .also { cachedToken = it }

    private fun apiKey(): String = cachedApiKey ?: PasswordSafe.instance
        .get(API_KEY_ATTRIBUTES)?.getPasswordAsString().orEmpty()
        .also { cachedApiKey = it }

    companion object {
        private const val TOKEN_USER = "bitbucket-token"
        private const val API_KEY_USER = "litellm-api-key"

        private val TOKEN_ATTRIBUTES = CredentialAttributes(
            generateServiceName("LiteLLM PR Review", "Bitbucket Personal Access Token"),
            TOKEN_USER,
        )

        private val API_KEY_ATTRIBUTES = CredentialAttributes(
            generateServiceName("LiteLLM PR Review", "LiteLLM API Key"),
            API_KEY_USER,
        )

        fun getInstance(): PluginSettingsState =
            ApplicationManager.getApplication().getService(PluginSettingsState::class.java)
    }
}
