You are an expert IntelliJ Platform Plugin developer proficient in Kotlin, the IntelliJ Platform SDK (version 2024.3+), Git4Idea, and Swing/JB UI components.

### Goal
Build a complete, production-ready IntelliJ IDEA plugin written in Kotlin that:
1. Embeds a custom ToolWindow on the left-center stripe of IntelliJ (`anchor="left"`).
2. Auto-detects the Git repository coordinates (Host, Project Key, and Repo Slug) by reading the active project's `.git/config` remotes using `Git4Idea`.
3. Interacts with an on-premise Bitbucket Server (REST API v1.0) using a Personal Access Token (Bearer Auth).
4. Includes a manual "Refresh" action button on the tool window toolbar to inspect `.git/config` and fetch open Pull Requests on demand.
5. Displays open PRs in a `JBList`. When a PR is selected and the user clicks "Review with LiteLLM", it fetches the unified diff from Bitbucket and sends it to a LiteLLM proxy instance (`/v1/chat/completions`) using an OpenAI-compatible spec.
6. Renders the LLM code review cleanly inside a scrollable output panel within the tool window.
7. Provides a Settings panel (`Configurable`) to persist:
    - Bitbucket Base URL (e.g., https://bitbucket.internal.company.com)
    - Bitbucket Personal Access Token (stored securely via `PasswordSafe`)
    - LiteLLM Base URL (e.g., http://localhost:4000/v1)
    - Target LLM Model name (e.g., claude-3-5-sonnet, gpt-4o)

---

### Technical Constraints & Requirements

1. **Build Tool & Language:**
    - Gradle with Kotlin DSL (`build.gradle.kts`) using the official `org.jetbrains.intellij.platform` plugin (v2.x).
    - Target IntelliJ version: `2024.3` (Community or Ultimate).
    - Target JVM: Java 21 / Kotlin 2.0+.
    - Bundled plugin dependency: `Git4Idea`.

2. **Git URL Resolution (`.git/config`):**
    - Must handle on-premise Bitbucket Server URL patterns:
        - SSH: `ssh://git@bitbucket.company.com:7999/PROJECT_KEY/repo-slug.git` or `git@bitbucket.company.com:PROJECT_KEY/repo-slug.git`
        - HTTPS: `https://bitbucket.company.com/scm/PROJECT_KEY/repo-slug.git`
    - Extract `projectKey` and `repoSlug` cleanly.

3. **Bitbucket Server API Specs:**
    - Fetch Open PRs: `GET {baseUrl}/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests?state=OPEN`
    - Fetch Diff: `GET {baseUrl}/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/{prId}.diff`
    - Authentication: `Authorization: Bearer <PAT>`

4. **LiteLLM Client:**
    - Standard `/v1/chat/completions` POST request with `messages: [{"role": "system", ...}, {"role": "user", ...}]`.
    - Include diff truncation guard (e.g., max 50KB-60KB) to prevent token overflow.
    - Non-blocking execution: Background tasks must run inside `ProgressManager.getInstance().run(Task.Backgroundable(...))` to ensure UI responsiveness.

5. **UI Architecture:**
    - Implement `ToolWindowFactory` and register it in `plugin.xml`.
    - Toolbar using `DefaultActionGroup` with standard icons (`AllIcons.Actions.Refresh` and `AllIcons.Actions.Execute`).
    - Split layout: `JBSplitter` holding a `JBList` for PRs on top and a scrollable `JTextArea` or Markdown editor panel at the bottom.

---

### Deliverables Required
Please generate the complete, self-contained project code files with no placeholders or pseudo-code:
1. `build.gradle.kts`
2. `src/main/resources/META-INF/plugin.xml`
3. `GitConfigDetector.kt`: URL extraction logic using `GitRepositoryManager`.
4. `PluginSettingsState.kt` & `PluginSettingsConfigurable.kt`: Settings panel and `PasswordSafe` storage for PAT.
5. `BitbucketServerClient.kt`: Network calls to Bitbucket Server.
6. `LiteLLMClient.kt`: Network calls to LiteLLM.
7. `PRToolWindowFactory.kt` & `PRMainPanel.kt`: Complete Swing/IntelliJ UI layout, action listeners, background tasks, and error handling dialogs.