# LiteLLM PR Review

LiteLLM PR Review is an IntelliJ IDEA plugin for reviewing pull requests from a self-hosted Bitbucket Server or Data Center instance. It detects the repository from your project's Git remotes, shows open pull requests and their changes, and sends a selected pull request to your LiteLLM proxy for an AI code review.

The plugin provides a **LiteLLM PR Review** tool window on the left side of the IDE. You can inspect diffs, add context for the model, and read suggested fixes without leaving your project.

## Features

- Detect the Bitbucket project key and repository slug from HTTPS, SSH, or SCP-style Git remotes.
- Load open pull requests on demand and search the list by number, title, author, or branch.
- Inspect changed files in the **Changes** tab, with line numbers, synchronized side-by-side diffs, and highlighting within modified lines.
- Choose a model from your LiteLLM gateway or enter a model alias manually.
- Add review notes that are remembered for each repository and pull request within the IDE project.
- Request findings with file locations, severity, an explanation, and replacement code.
- Store the Bitbucket token and optional LiteLLM API key in IntelliJ Password Safe.

## Contents

- [Requirements](#requirements)
- [1. Build and install](#1-build-and-install)
- [2. Prepare the repository and services](#2-prepare-the-repository-and-services)
- [3. Configure the plugin](#3-configure-the-plugin)
- [4. Load pull requests and inspect changes](#4-load-pull-requests-and-inspect-changes)
- [5. Run a review](#5-run-a-review)
- [Review behavior and limits](#review-behavior-and-limits)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

## Requirements

| Component | Requirement |
| --- | --- |
| IDE | IntelliJ IDEA **2025.3 or later**, build **253+**. The project currently compiles against **2025.3.5**. |
| IDE plugin | The bundled **Git** plugin (`Git4Idea`) must be enabled. |
| Repository | A Git repository registered in the open IDE project, with a Bitbucket Server/Data Center remote. |
| Bitbucket | A reachable Server/Data Center instance exposing REST API v1.0 and a personal access token with permission to read the repository and its pull requests. |
| LiteLLM | A reachable proxy with a configured model and OpenAI-compatible Chat Completions support. An API key is needed if the proxy requires authentication. |
| Building from source | **JDK 21**, Git, and network access to download Gradle, dependencies, and the IntelliJ Platform. |

The integration uses the Bitbucket Server/Data Center API; Bitbucket Cloud, GitHub, and GitLab APIs are not supported.

## 1. Build and install

If you already have a built plugin ZIP, start at step 4.

1. Install JDK 21 and make it available to your terminal or set it as the project's Gradle JVM. Check it with:

   ```bash
   java -version
   ```

2. Copy this repository's clone URL and replace `<repository-url>` below:

   ```bash
   git clone <repository-url> litellm-pr-review
   cd litellm-pr-review
   ```

3. Build the installable plugin archive:

   ```bash
   ./gradlew buildPlugin
   ```

   The Gradle wrapper downloads the required build tools. The current version produces `build/distributions/litellm-pr-review-1.0.0.zip`; the filename changes with the version in `gradle.properties`.

4. In IntelliJ IDEA, open **Settings → Plugins**, open the gear menu, and select **Install Plugin from Disk…**.
5. Select the ZIP from `build/distributions/`, apply the change, and restart the IDE if prompted.

On Windows PowerShell, use `.\gradlew.bat` in place of `./gradlew`, for example `.\gradlew.bat buildPlugin`.

## 2. Prepare the repository and services

1. Open the **Bitbucket repository you want to review** in IntelliJ IDEA. The plugin reads the open project's remotes, so use your application repository for reviews.
2. In that repository's terminal, check its remotes:

   ```bash
   git remote -v
   ```

   Supported clone URL examples are:

   ```text
   https://bitbucket.company.com/scm/PROJECT_KEY/repo-slug.git
   ssh://git@bitbucket.company.com:7999/PROJECT_KEY/repo-slug.git
   git@bitbucket.company.com:PROJECT_KEY/repo-slug.git
   ```

   HTTPS URLs with a server context path, such as `https://git.company.com/bitbucket/scm/PROJECT_KEY/repo-slug.git`, are also supported.

3. If the repository has no remote, add its actual Bitbucket clone URL:

   ```bash
   git remote add origin <bitbucket-clone-url>
   ```

4. Create a Bitbucket personal access token in your Bitbucket account settings. Give it permission to read the target repository and pull requests.
5. Obtain your LiteLLM proxy URL, an available model name or alias, and an API key if required. The model's upstream provider credentials are configured on the proxy. To set up a proxy, follow the [LiteLLM proxy quick start](https://docs.litellm.ai/docs/proxy/quick_start).

Repository detection prefers remotes matching the configured Bitbucket host. Within that preference, `origin` comes first, then `upstream`, then other remotes. If no host matches, the plugin tries the remaining parseable remotes. For projects with multiple repositories or remotes, check the detected repository in the tool window's status bar.

## 3. Configure the plugin

1. Open **Settings → Tools → LiteLLM PR Review**.
2. Fill in the connection fields:

   | Field | What to enter | Example |
   | --- | --- | --- |
   | **Bitbucket Base URL** | The HTTP(S) server root, including any context path. | `https://bitbucket.company.com` or `https://git.company.com/bitbucket` |
   | **Access Token** | Your Bitbucket personal access token. | The token created in the previous step. |
   | **LiteLLM Base URL** | The API base to which `/chat/completions` and `/models` are appended. | `http://localhost:4000/v1` |
   | **LiteLLM API Key** | A key accepted by the proxy; leave blank for an unauthenticated proxy. | Your proxy-issued API key. |
   | **Model** | A model ID or alias served by your proxy. | Select an entry returned by the gateway. |

3. Click **Refresh** beside **Model** to request the gateway's model list. The field is editable, so you can type a known alias if the endpoint is unavailable or does not list it. The prefilled `claude-3-5-sonnet` value must be replaced if your proxy does not serve that name.
4. Check the **Calls …/chat/completions** hint below the LiteLLM URL. It shows the endpoint the plugin will use.
5. Click **Apply**, then **OK**.

Both base URLs, a Bitbucket token, and a model are required, including when refreshing the pull request list. Connection settings are shared across IDE projects. Both secrets are saved in IntelliJ Password Safe; the URLs and default model are saved in `litellmPrReview.xml` in the IDE's settings storage.

### URL examples

The plugin normalizes common pasted URLs when settings are saved:

| Entered URL | Stored base URL |
| --- | --- |
| `https://bitbucket.company.com/rest/api/1.0` | `https://bitbucket.company.com` |
| `https://git.company.com/bitbucket/scm/PROJ/repo.git` | `https://git.company.com/bitbucket` |
| `http://localhost:4000` | `http://localhost:4000/v1` |
| `http://localhost:4000/v1/chat/completions` | `http://localhost:4000/v1` |
| `https://gateway.company.com/litellm/v1` | `https://gateway.company.com/litellm/v1` |

For LiteLLM, `/v1` is added automatically only when the base has no path. Include the complete API base yourself when your gateway is mounted under a path. Bitbucket API requests always go to the configured HTTP(S) base URL, even when the Git remote uses a different SSH host or port.

## 4. Load pull requests and inspect changes

1. Open **View → Tool Windows → LiteLLM PR Review**, or click its icon on the left tool window stripe.
2. Click the toolbar's **Refresh** button. Loading is manual; opening the tool window does not automatically fetch pull requests.
3. Check the status bar for the remote name, host, project/repository, and number of open pull requests.
4. Select a pull request from the list. To locate one quickly, focus the list and start typing its number, title, author, or branch name.
5. Open the **Changes** tab. Selecting a pull request loads its diff from Bitbucket and lists its files with their status and added/removed line counts.
6. Select a file to inspect the old version on the left and the new version on the right. Green marks additions, red marks removals, and yellow marks replacements. Stronger highlighting marks the characters changed within a replacement. Vertical scrolling is synchronized.

Binary files display an explanation instead of a textual diff. If the server returns a format the plugin cannot parse, the Changes tab shows the raw response.

After new commits are pushed, click **Refresh** before reviewing again. Refresh clears the displayed diff cache and reloads the pull request list, retaining the selected pull request if it is still open.

## 5. Run a review

1. Select the pull request you want reviewed.
2. Choose a model in the drop-down at the bottom of the tool window, or type its alias. This selection applies to the tool window session. Change **Model** in Settings to set the default for future windows.
3. Open the **Review** tab and optionally fill in **Notes for the model**. Include context that the diff cannot convey, for example:

   ```text
   This PR fixes duplicate notifications when a job is retried.
   Files under src/generated are generated; skip suggestions for them.
   A successful retry must leave exactly one notification for the customer.
   ```

   Notes are saved as you type and restored when you return to that repository and pull request in this IDE project. If you mention a specification or ticket, include the relevant requirements: the review request cannot open referenced files, tickets, or links.

4. Click **Review with LiteLLM** in the toolbar. With a selection, its label includes the pull request number, for example **Review #42 with LiteLLM**. The request reviews the pull request's textual changes across files; selecting one file in Changes does not restrict the review to that file.
5. Wait for the background request to finish. The **Review** tab opens when the answer arrives. The header identifies the pull request and shows its parsed change counts, such as `412 added, 88 removed · 6 files`. It also shows **with your notes** when notes were included.
6. Read the suggestions alongside the Changes tab. The model is asked to provide `path/to/File.ext:line` locations, **Blocking**, **Should fix**, or **Nit** severity, an explanation, and replacement code. The answer appears as selectable text, including any Markdown syntax or code blocks returned by the model.
7. Use **Open in browser**, when available in the review header, to open that pull request in Bitbucket and post any findings you choose to share. Posting comments, approving or merging pull requests, and applying suggested code changes are manual actions outside this plugin.

You can revise the notes or choose another model and run the review again. The output panel displays the latest review result.

## Review behavior and limits

- **Review input:** LiteLLM receives the pull request number, title, available author and branch information, your notes, and a formatted listing of changed lines with line numbers and nearby context. The source is Bitbucket's pull request diff; local uncommitted edits and the full repository are not included.
- **Large diffs:** The change listing uses a **60,000-character** budget. The plugin first reduces surrounding context from six lines to two, then to zero. If the changes still exceed the budget, it truncates the listing and adds an omission notice asking the model to acknowledge the partial review. Metadata, notes, and the truncation notice are additional to that budget. The header's change counts describe the parsed diff, not guaranteed model coverage after truncation.
- **Response size:** The request is non-streaming and sets `max_completion_tokens` to **4,096**. The response is displayed after the request completes.
- **No textual changes:** Pull requests with no added or removed text, such as rename-only or binary-only changes, skip the model call. An unreadable diff produces an error instead of a review.
- **Pull request listing:** Refresh requests only open pull requests, in pages of 50, for up to 20 pages. Merged and declined pull requests are not listed.
- **Connections:** HTTP calls use a 10-second connection timeout and a 120-second read timeout. The plugin does not automatically retry failed requests.
- **Notes storage:** Notes are stored as project settings in `litellmPrReviewNotes.xml`, separate from Password Safe, with up to 200 entries. Clearing a note removes its saved entry.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Plugin cannot be installed, or the tool window is unavailable | Use IntelliJ IDEA 2025.3+ and enable the bundled Git plugin. Confirm LiteLLM PR Review is installed and enabled. |
| Settings are requested when pressing Refresh | Fill both base URLs, save a Bitbucket token, and choose a model under Settings → Tools → LiteLLM PR Review. |
| No repository or remote is found | Open the intended Git project, ensure Git recognizes it in the IDE, and inspect `git remote -v`. Detection errors include the remotes that were found. |
| The wrong repository is shown | Check the detected project/repository in the status bar, the configured Bitbucket host, and the `origin`/`upstream` remotes. Detection selects one matching repository for the project. |
| No pull requests are listed | Check that the detected repository has open pull requests and that the token can read them. |
| HTTP 401 or 403 | Check which service the error names. For Bitbucket, verify the Access Token and repository permissions. For LiteLLM, verify the LiteLLM API Key and access to the requested model. |
| HTTP 404 or 405 | Inspect the method and URL in the error. Check the base URL and any context path. A docs, health, or administration URL is not the Chat Completions API base. |
| The model list is empty or cannot load | Check the gateway URL and API key, then use Refresh beside Model in Settings. You can also type an alias that the gateway serves. |
| LiteLLM rejects a review, or returns an empty answer | Check the selected model alias, the proxy's model configuration, and support for Chat Completions with `max_completion_tokens`. Try another available model. |
| Timeout or service unreachable | Check network/VPN access, IDE proxy settings, and service availability. A slow model may exceed the 120-second read timeout. |
| The displayed diff predates a push | Click Refresh, then select the pull request and review again. |
| The Changes tab shows HTML or another unexpected response | Check the Bitbucket URL, authentication, and any reverse proxy that may be returning a login or error page. |

## Development

The project uses Kotlin, Java 21, the IntelliJ Platform Gradle plugin, Git4Idea, and Swing/JetBrains UI components. Use the included Gradle wrapper; a separate Gradle installation is unnecessary.

| Command | Purpose |
| --- | --- |
| `./gradlew runIde` | Launch a sandbox IntelliJ IDEA instance with the plugin installed. |
| `./gradlew test` | Run the automated tests. |
| `./gradlew check` | Run the Gradle check lifecycle. |
| `./gradlew buildPlugin` | Package the installable ZIP under `build/distributions/`. |

For an interactive development session:

1. Open this repository as a Gradle project in IntelliJ IDEA and use JDK 21 for Gradle.
2. Run `./gradlew runIde`, or use the included **Run IDE with Plugin** run configuration.
3. In the sandbox IDE, open a Bitbucket-backed project and configure the plugin using the steps above. The sandbox uses its own IDE settings.
4. Exercise Refresh, file selection, notes, and Review with your Bitbucket and LiteLLM services.
5. Run `./gradlew check` before packaging changes. Test reports are written to `build/reports/tests/test/index.html`.

The tests cover remote and URL parsing, Bitbucket responses, diff parsing and display, review input and notes, model selection, and HTTP transport behavior.

### Source map

| Area | Main files |
| --- | --- |
| Build and compatibility | [build.gradle.kts](build.gradle.kts), [gradle.properties](gradle.properties), [settings.gradle.kts](settings.gradle.kts) |
| Plugin registration | [plugin.xml](src/main/resources/META-INF/plugin.xml) |
| Tool window and workflow | [PRToolWindowFactory.kt](src/main/kotlin/com/github/senocak/prreview/PRToolWindowFactory.kt), [PRMainPanel.kt](src/main/kotlin/com/github/senocak/prreview/PRMainPanel.kt) |
| Settings and credentials | [PluginSettingsConfigurable.kt](src/main/kotlin/com/github/senocak/prreview/PluginSettingsConfigurable.kt), [PluginSettingsState.kt](src/main/kotlin/com/github/senocak/prreview/PluginSettingsState.kt) |
| Repository detection | [GitConfigDetector.kt](src/main/kotlin/com/github/senocak/prreview/GitConfigDetector.kt) |
| Service clients | [BitbucketServerClient.kt](src/main/kotlin/com/github/senocak/prreview/BitbucketServerClient.kt), [LiteLLMClient.kt](src/main/kotlin/com/github/senocak/prreview/LiteLLMClient.kt), [HttpTransport.kt](src/main/kotlin/com/github/senocak/prreview/HttpTransport.kt) |
| Diff parsing and display | [GitDiff.kt](src/main/kotlin/com/github/senocak/prreview/GitDiff.kt), [ChangesView.kt](src/main/kotlin/com/github/senocak/prreview/ChangesView.kt), [SideBySideDiff.kt](src/main/kotlin/com/github/senocak/prreview/SideBySideDiff.kt) |
| Review input and saved notes | [ReviewDiff.kt](src/main/kotlin/com/github/senocak/prreview/ReviewDiff.kt), [ReviewNotes.kt](src/main/kotlin/com/github/senocak/prreview/ReviewNotes.kt) |

See [CHANGELOG.md](CHANGELOG.md) for the change history.
