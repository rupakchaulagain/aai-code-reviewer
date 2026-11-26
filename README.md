# Fintech AI Code Reviewer — JetBrains Plugin

An interactive, AI-powered code review tool window for **IntelliJ IDEA** and **WebStorm**
(and any other JetBrains IDE with Git support). It reads your real Git changes, sends them to
the LLM you configure, and streams back a structured review split into
🔴 **Must Do** and 🟡 **Should Do** findings, each with a one-click *Copy code* button.

---

## 1. Requirements

| Thing | Version |
|---|---|
| JDK | 21 (JetBrains Runtime or any OpenJDK 21) |
| Maven | 3.8+ (`mvn`) |
| IDE | IntelliJ IDEA / WebStorm **2024.2** or newer |
| Git | the project must be a Git repository |

The Maven build compiles against the IntelliJ Platform artifacts published in JetBrains' Maven
repositories (declared in `pom.xml`, `provided` scope), so the machine that builds the plugin needs
internet access to `www.jetbrains.com/intellij-repository`, `cache-redirector.jetbrains.com` and
Maven Central.

---

## 2. Build and install

```bash
# build the installable zip
mvn clean package

# artifact lands in:
#   target/ai-code-reviewer-1.0.0.zip
```

Then in **IntelliJ IDEA or WebStorm**:

`Settings/Preferences` → `Plugins` → gear icon ⚙ → **Install Plugin from Disk…** →
pick `target/ai-code-reviewer-1.0.0.zip` → **Restart IDE**.

### Build settings

The platform version and the `since-build` written into `plugin.xml` are properties in `pom.xml`:

| Property | Default | Meaning |
|---|---|---|
| `intellij.platform.version` | `242.24807.4` | IntelliJ Platform build compiled against (IDEA 2024.2.5) |
| `plugin.sinceBuild` | `242` | `<idea-version since-build>`; no until-build is set |

`plugin.xml` gets `${project.version}` and `${plugin.sinceBuild}` through Maven resource filtering.

### Developing and testing

There is no Maven equivalent of the Gradle `runIde` / `verifyPlugin` tasks. To try a change,
run `mvn package`, install the zip into a development IDE from disk, and restart it.
For cross-IDE compatibility checks, run the standalone
[IntelliJ Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier) against
`target/ai-code-reviewer-1.0.0.zip`.

---

## 3. Configure

`Settings/Preferences` → `Tools` → **AI Code Reviewer**

| Field | Notes |
|---|---|
| **Provider** | Anthropic (Claude), OpenAI, Google (Gemini), or Custom |
| **Model** | editable combo, pre-filled with sensible suggestions per provider |
| **API token** | stored in the IDE **credential store** (OS keychain / KeePass) — never in project files |
| **Custom API base URL** | leave blank for the provider default; set it for Ollama, vLLM, LM Studio, OpenRouter, Azure gateways or an internal proxy |
| **Max output tokens / temperature / timeout** | request tuning; temperature defaults to `0.1` for deterministic reviews |
| **Max context characters** | hard cap on how much diff is sent (default 60 000) |
| **Commits for 'Last N commits'** | used by that review source |
| **Base branch for PR review** | e.g. `origin/main`, `origin/develop` |
| **Extra house rules** | free text appended to the system prompt with the same authority as the built-in rules |

Press **Test connection** to verify the token, model name and URL before your first review.

**Custom provider** speaks the OpenAI `/v1/chat/completions` dialect, so a local Ollama works with
base URL `http://localhost:11434` and model `qwen2.5-coder:14b`, no token required.

---

## 4. Use

Open the **AI Code Review** tool window on the right-hand side bar.

Pick a review source and press **Review**:

| Source | What it sends |
|---|---|
| Local uncommitted changes | `git diff HEAD` + new untracked files |
| Staged changes (index) | `git diff --staged` |
| Last commit | `git show HEAD --stat --patch` |
| Last N commits | `git diff HEAD~N..HEAD` |
| Branch vs base branch | `git diff origin/main...HEAD` — the pull-request view |
| Current file in editor | the whole open file |

While it runs, the bottom half traces the process live:

* **Trace** — repository, branch, changed file count, prompt size, timings
* **Model stream** — the raw tokens as they arrive from the LLM
* **Reviewed context** — exactly what was sent, so nothing is a black box

Results appear on top: a summary card with the risk level and counts, then one card per finding
with its rule reference, file:line, deep reasoning, business impact, the offending code and the
suggested fix with a **Copy code** button. **Copy all fixes** puts every suggestion on the clipboard.

Also available from `Tools` → `AI Code Review`, the editor right-click menu, and the VCS menu.
Default shortcut for local changes: <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>R</kbd>.

---

## 5. Architecture

```
src/main/java/com/fintech/aireview
├── settings
│   ├── AiReviewSettings.java         @Service(APP) + PersistentStateComponent (non-secret config)
│   ├── ApiKeyStore.java              PasswordSafe wrapper, one token slot per provider
│   └── AiReviewConfigurable.java     Settings > Tools > AI Code Reviewer (FormBuilder UI)
├── llm
│   ├── LlmProvider.java              enum: defaults, suggested models, token hints
│   ├── LlmClient.java                client interface (+ LlmCallContext, LlmException)
│   ├── BaseLlmClient.java            java.net.http, SSE reader, cancellation
│   ├── AnthropicClient.java          /v1/messages, content_block_delta stream
│   ├── OpenAiCompatibleClient.java   /v1/chat/completions — OpenAI, Ollama, vLLM, gateways
│   ├── GeminiClient.java             /v1beta/models/{model}:streamGenerateContent?alt=sse
│   └── LlmClientFactory.java
├── vcs
│   ├── ReviewSource.java             the six review scopes
│   ├── ReviewContext.java            record: payload + changed files + truncation flag
│   └── GitContextService.java        @Service(PROJECT), Git4Idea GitLineHandler plumbing
├── review
│   ├── Severity.java, Finding.java,
│   │   ReviewResult.java             Gson-friendly models, nullable fields
│   ├── ReviewPromptBuilder.java      the system prompt: fintech standards + Top 10 rules + JSON contract
│   ├── ReviewResponseParser.java     balanced-brace JSON extraction, tolerant of fences and preambles
│   └── ReviewEngine.java             @Service(PROJECT), orchestrates collect → prompt → stream → parse
├── ui
│   ├── ReviewToolWindowFactory.java  right-anchored tool window
│   ├── ReviewPanel.java              toolbar, findings list, live trace tabs, background task + Stop
│   ├── WebReviewPanel.java           JCEF/HTML variant of the panel (webview/panel.html)
│   ├── FindingCard.java              severity-coloured card with Copy code
│   ├── ScrollableVerticalPanel.java  viewport-width stack so text wraps
│   └── AiReviewIcons.java
└── actions                           Tools menu, editor popup, VCS menu entry points
```

**Threading model.** The review runs in a `Task.Backgroundable`; the LLM stream is read on that
background thread and pushed to the EDT through `invokeLater`. Streamed tokens are buffered and
flushed by a 120 ms Swing timer so a fast stream cannot flood the EDT. **Stop** cancels the
`ProgressIndicator`, which the SSE loop checks on every line.

**Why it works in WebStorm.** The only declared dependencies are `com.intellij.modules.platform`
and `Git4Idea`, both present in every JetBrains IDE. No Java PSI, no `com.intellij.modules.java`.

---

## 6. The review contract

The system prompt (see `ReviewPromptBuilder.java`) enforces:

* **Domain standards** — money precision (`BigDecimal`/minor units, never `double`), idempotency on
  every state-changing entry point, append-only auditability, and the four ACID properties, each
  with the specific failure mode to look for (partial writes, lost updates, publish-before-commit).
* **OWASP Top 10** — injection, XSS, IDOR, broken access control, SSRF, unsafe deserialization,
  secrets in code, weak crypto, missing rate limiting on money movement.
* **The Top 10 Java / Spring Boot rules** — R1 constructor injection … R10 immutability, quoted
  verbatim in the prompt so the model can cite the rule id in each finding.

The model must answer with a single JSON object:

```json
{
  "summary": "...",
  "riskLevel": "LOW | MEDIUM | HIGH",
  "findings": [
    {
      "severity": "MUST",
      "title": "...",
      "category": "Security | ACID | Performance | Correctness | Design | Style | Observability",
      "rule": "R4 Transaction Management",
      "file": "src/main/java/.../TransferService.java",
      "line": 42,
      "explanation": "...",
      "impact": "...",
      "original": "...",
      "suggestion": "...",
      "language": "java"
    }
  ]
}
```

If a model ignores the contract, `ReviewResponseParser` degrades gracefully and shows the raw
output as a single card rather than failing the review.

---

## 7. Extending it

* **New provider** — add an enum constant to `LlmProvider`, subclass `BaseLlmClient` with
  `parseStreamLine` / `parseFullBody`, register it in `LlmClientFactory`. Nothing else changes.
* **New review source** — add a constant to `ReviewSource` and a branch in
  `GitContextService.collect`. The combo box populates itself from the enum.
* **Team rules** — put them in *Extra house rules* in Settings, or edit `SYSTEM_PROMPT` and ship
  them with the plugin so every developer gets the same review.
* **Jump to code** — `Finding.file` / `Finding.line` are already parsed; wire an
  `OpenFileDescriptor` into `FindingCard` to make the location label clickable.

---

## 8. Notes and limits

* Diffs are sent to whichever endpoint you configure. For regulated code, point the **Custom**
  provider at a self-hosted or in-VPC model; nothing is sent anywhere until you press *Review*.
* Very large branch diffs are truncated at *Max context characters*; the model is told about the
  truncation so it does not comment on missing code.
* Untracked-file inclusion is capped at 20 files and 200 KB per file.
* Findings are advisory. Treat 🔴 Must Do as a review checklist, not as a merge gate substitute.
#   a a i - c o d e - r e v i e w e r  
 