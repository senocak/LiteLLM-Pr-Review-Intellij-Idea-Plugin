# Changelog

All notable changes to LiteLLM PR Review are documented in this file.

## [Unreleased]

### Added

- **A notes box on the Review tab, for what the diff cannot say.** A diff does not record why a pull
  request exists. Sometimes the change implements a specification nobody in the conversation can read,
  a directory is generated and should not be reviewed by hand, or the project has already settled a
  question the model is about to raise — and in each of those cases a review that has not been told is
  not merely incomplete, it is confidently wrong. So the reviewer can write what they know in their
  own words and it goes with the request. The notes are **labelled as the reviewer's words and not as
  code or findings**, so a model does not cite one back as a line of the diff, and the model is told
  explicitly that it **cannot open a file, ticket or link a note points at** — without that it
  invents the contents of a specification it was merely told about, and reports findings against it.
  A note saying a file is generated is treated as a reason to leave that file alone.
  Notes are **remembered per pull request, per project**, so switching between pull requests swaps the
  note rather than risking one pull request's brief being sent with another's diff, and they survive
  closing the IDE. The result line says whether the answer was produced with them, so a note that was
  typed and forgotten cannot pass silently for one that was used. The box is held still while a
  request is in flight, because a note edited afterwards is not in the answer that comes back.

- **A Changes tab that shows what a pull request changes, side by side.** Selecting a pull request
  lists every file it touches, with its status — added, modified, deleted, renamed, binary — and how
  many lines moved in each. Clicking a file opens its diff with the old and the new in two columns
  that scroll together, so the question a reviewer is actually asking, "what does this line
  replace?", can be answered by looking at one row. A replaced line stays on one row rather than
  being split into a deletion above an addition, and the characters that actually changed inside it
  are marked, so a one-character edit is not hidden inside a highlighted line. **A change is coloured
  by what kind of change it is: an added line is green, a removed line is red, and a replaced line is
  yellow on both of its halves.** A replaced line is not red on the left and green on the right, which
  would read as two unrelated lines when what happened is that one line was rewritten, and only the
  half that actually has the line is coloured, so an inserted line is visibly one-sided. Within a
  replaced line the characters that differ are marked in a stronger shade of the same colour, which is
  how a diff is read everywhere else: a pale band says the line moved, a strong mark says where. Each
  side is numbered
  with its own line numbers, which are the numbers a review comment cites. Binary files say they
  have no textual diff instead of appearing empty, and a diff in a format the plugin cannot read is
  shown as raw text rather than as an empty pane.
- **Reviews are about the changed lines, and ask for a concrete replacement.** The model is sent
  every added and modified line with the line number it sits on, rather than a raw unified diff
  whose context is most of its size and whose `@@` headers a model has to count through to cite a
  single line. A finding now has to name a location, mark its severity, and give the replacement
  code ready to paste, with a sentence on the failure or the cost it avoids — so a review is a set
  of commentable suggestions instead of advice. Deleted lines are sent as context but are not
  offered improvements, since there is nothing left to rewrite. When a pull request is too large
  for the model's context, unchanged code around the changes is given up first: a changed line is
  never dropped to save space, and a pull request too large even then says so rather than letting
  the model review a partial change and call it whole. **A review now states how much it looked at**
  — `412 added, 88 removed · 6 files` — because "no actionable findings" means something quite
  different about four changed lines than about four thousand, and a reviewer should not have to open
  the Changes tab to tell which they got. A diff that comes back in a form the plugin cannot read is
  **refused rather than reviewed**: asked about a diff that never arrived, a model answers "no
  actionable findings", and that is a clean bill of health for a pull request nobody looked at. The
  raw response is on the Changes tab, where the actual problem is visible. A pull request that only
  renames files says so instead of spending a call that can only come back with "no findings".
- **A pull request is shown as it is now, not as it was when it was last looked at.** Selecting a
  pull request always asks the server for its diff. It used to reuse the copy already downloaded, and
  because a pull request's number never changes, a reviewer who pushed a commit and came back to the
  same pull request was shown the diff they had read before the push — and had that stale diff
  reviewed, which is the version that matters least to get right. The copy is now kept only for the
  moment between reading the changes and pressing Review, so that costs one request rather than two
  and cannot outlive the commit it describes.
- **LiteLLM PR Review** tool window, docked on the left. The project a reviewer is working on is
  usually already cloned from the server under review, so the plugin reads the repository out of
  Git rather than asking for it: remotes come from the repository's own `config` file, and the one
  matching the configured Bitbucket host is used. `origin` then `upstream` are the fallback order,
  because a fork's `origin` points the wrong way and `upstream` is the remote that has the pull
  requests.
- Pull request list from the Bitbucket Server REST v1.0 API, authenticated with a personal access
  token, paginated until the server says the last page, and refreshable with a cancellable
  progress indicator. The selection is kept across a refresh so a review in progress is not thrown
  away by a background update.
- One-click review of the selected pull request: its changes are downloaded from
  `pull-requests/{id}.diff` and sent to a LiteLLM OpenAI-compatible `/chat/completions` endpoint,
  then shown in the Review tab, which opens itself when the answer arrives rather than leaving it
  behind a tab. The changes are not downloaded twice: a pull request whose diff is already on
  screen in the Changes tab is reviewed from the same copy, and a few recent ones are kept so
  clicking back and forth is instant.
- Settings under **Settings | Tools | LiteLLM PR Review**: Bitbucket base URL, personal access
  token, LiteLLM base URL, model, and an optional API key. The base URLs are normalised on entry, so
  a URL pasted out of the Bitbucket REST docs, or a `/v1/chat/completions` URL, reduces to the base
  the plugin appends its own paths to.
- **Model drop-down, populated from the gateway's own `/models` endpoint**, in both the settings
  page and the tool window. The names it lists are the ones this installation can actually route,
  including the aliases a team has defined in its `config.yaml`, and they change with every provider
  release — so the model is chosen from a list rather than typed from memory. The box stays
  editable, because a gateway can be down when the page opens and a model can be served without
  being listed; in both cases the saved model stays selected and the box stays usable. The list is
  fetched off the EDT, debounced behind the URL field, retried after a failure, and refetched when
  the configured gateway changes. The tool window's box is for the session and seeds from the
  setting, so a cheap model for a rename and an expensive one for a concurrency change do not
  require a trip through the settings page.
- Failures are reported with the service that produced them — a 401 from Bitbucket and a 401 from
  the gateway are not the same problem, and neither is the same as a diff too large for the model.
  When detection finds no Bitbucket remote, the panel shows the remotes that *are* configured
  instead of only saying it failed.

### Fixed

- **Reviews were never sent.** The Chat Completions call wrote its body to a connection that had
  not been put into write mode, so the request failed before a single byte reached the gateway.
  Reported as an unreachable LiteLLM, because from the plugin's side that is indistinguishable from
  the network. A POST is now built as a POST, with `Content-Type: application/json` declared.
- **A failure no longer names two services.** A review is two calls to two different servers, and
  both were reported as "Bitbucket Server / LiteLLM", so a failure raised by one of them could not
  be told apart from a failure raised by the other — and a Bitbucket 405 was reported as a problem
  with the model name. Every call now names the service it is for, and the failure is phrased where
  the call is made.
- One-click review of the selected pull request: its changes are downloaded from
  `pull-requests/{id}.diff` and sent to a LiteLLM OpenAI-compatible `/chat/completions` endpoint,
  then shown in the Review tab, which opens itself when the answer arrives rather than leaving it
  on a tab behind the diff. The changes are not downloaded twice: a pull request whose diff is
  already on screen in the Changes tab is reviewed from the same copy, and a few recent ones are
  kept so clicking back and forth is instant.
- **Failures name the request that failed.** 404, 405, 5xx and the unclassified statuses now
  include the verb and the URL, because the request is the one piece of a failure the user cannot
  reconstruct. A 405 additionally says what it means: the URL exists and does not accept that verb,
  which is what a base URL pointed at a health, docs or admin page produces.

### Changed

- The plugin id, Kotlin package, storage file and Password Safe keys are new; the previous
  LiteLLM Integration settings are not read. This is a different plugin, not a revision of the
  previous one.
- Requires IntelliJ IDEA 2025.3 or later. Git4Idea moved `GitRepositoryManager` out of
  `com.intellij.dvcs.repo` into `git4idea.repo` in 2025.3, and the plugin uses the current package.
