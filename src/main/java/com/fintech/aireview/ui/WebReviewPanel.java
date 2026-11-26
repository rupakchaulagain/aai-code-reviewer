package com.fintech.aireview.ui;

import com.fintech.aireview.llm.LlmProvider;
import com.fintech.aireview.review.Finding;
import com.fintech.aireview.review.ReviewEngine;
import com.fintech.aireview.review.ReviewResult;
import com.fintech.aireview.review.Severity;
import com.fintech.aireview.settings.AiReviewConfigurable;
import com.fintech.aireview.settings.AiReviewSettings;
import com.fintech.aireview.settings.ApiKeyStore;
import com.fintech.aireview.vcs.ReviewContext;
import com.fintech.aireview.vcs.ReviewSource;
import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The rich review UI: the bundled {@code webview/panel.html} design rendered inside a
 * {@link JBCefBrowser}. Java drives the page through its {@code window.AICR} API, and the
 * page calls back into the IDE through a handful of injected {@code window.ide*}
 * bridges (run, stop, copy, open-file).
 */
public class WebReviewPanel extends JPanel implements ReviewView, Disposable {

    /** Separates path and line in the open-file bridge argument (String.fromCharCode(1) on the JS side). */
    private static final String OPEN_ARG_SEPARATOR = "\u0001";

    private final Project project;
    private final Gson gson = new Gson();
    private final JBCefBrowser browser = new JBCefBrowser();

    // JS -> Java bridges.
    private final JBCefJSQuery runQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    private final JBCefJSQuery stopQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    private final JBCefJSQuery copyQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
    private final JBCefJSQuery openQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

    // Scripts issued before the page finished loading are queued and drained on load.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();

    private volatile boolean pageLoaded;
    private volatile ProgressIndicator indicator;
    private volatile boolean running;

    // Model-stream deltas arrive fast; batch them into the page on a timer.
    private final StringBuilder streamBuffer = new StringBuilder();
    private final Timer flushTimer = new Timer(120, e -> flushStream());

    public WebReviewPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        Disposer.register(this, browser);
        add(browser.getComponent(), BorderLayout.CENTER);
        wireBridges();
        installLoadHandler();
        browser.loadHTML(loadHtml());
        flushTimer.setRepeats(true);
        flushTimer.start();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    // ------------------------------------------------------------- JS -> Java

    private void wireBridges() {
        runQuery.addHandler(scope -> {
            onEdt(() -> startReview(parseSource(scope)));
            return null;
        });
        stopQuery.addHandler(ignored -> {
            ProgressIndicator current = indicator;
            if (current != null) current.cancel();
            return null;
        });
        copyQuery.addHandler(text -> {
            if (text != null && !text.isEmpty()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(text));
            }
            return null;
        });
        openQuery.addHandler(arg -> {
            if (arg != null) {
                String[] parts = arg.split(OPEN_ARG_SEPARATOR, -1);
                String path = parts.length > 0 ? parts[0] : "";
                int line = 1;
                if (parts.length > 1) {
                    try {
                        line = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                        line = 1;
                    }
                }
                int target = line;
                if (!path.isBlank()) onEdt(() -> openInEditor(path, target));
            }
            return null;
        });
    }

    private void installLoadHandler() {
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                // Only act on the main frame's completion.
                if (frame != null && !frame.isMain()) return;
                injectBridges();
                pageLoaded = true;
                drainPending();
                refreshModelLine();
            }
        }, browser.getCefBrowser());
    }

    /** Define the {@code window.ide*} functions the page calls. */
    private void injectBridges() {
        String js = "window.ideRun=function(s){" + runQuery.inject("s") + "};"
                + "window.ideStop=function(){" + stopQuery.inject("'stop'") + "};"
                + "window.ideCopy=function(t){" + copyQuery.inject("t") + "};"
                + "window.ideOpenFile=function(p,l){" + openQuery.inject("p + String.fromCharCode(1) + l") + "};";
        rawExec(js);
    }

    // ------------------------------------------------------------- ReviewView

    @Override
    public void startReview(ReviewSource source) {
        if (running) return;
        AiReviewSettings settings = AiReviewSettings.getInstance();
        AiReviewSettings.State state = settings.getState();
        LlmProvider provider = settings.provider();
        if (!ApiKeyStore.isConfigured(provider) && provider != LlmProvider.CUSTOM) {
            trace("No API token configured for " + provider.getDisplayName() + ". Open Settings and add one.");
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiReviewConfigurable.class);
            refreshModelLine();
            return;
        }

        setSelectedScope(source);
        setRunning(true, "Reviewing " + source.getDisplayName().toLowerCase(Locale.ROOT) + "…");
        refreshModelLine();
        trace("Provider: " + provider.getDisplayName() + "   Model: " + state.model);

        ReviewEngine engine = project.getService(ReviewEngine.class);
        WebReviewPanel listener = this;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI code review", true) {
            @Override
            public void run(@NotNull ProgressIndicator progress) {
                indicator = progress;
                progress.setIndeterminate(true);
                engine.run(source, listener, progress);
            }

            @Override
            public void onFinished() {
                indicator = null;
                setRunning(false, null);
            }
        });
    }

    // ------------------------------------------------------------- ReviewListener

    @Override
    public void onTrace(String line) {
        trace(line);
    }

    @Override
    public void onDelta(String text) {
        synchronized (streamBuffer) {
            streamBuffer.append(text);
        }
    }

    @Override
    public void onContextReady(ReviewContext context) {
        exec("window.AICR&&AICR.context(" + json(context.header() + "\n" + context.payload()) + ");");
    }

    @Override
    public void onFinished(ReviewResult result, long elapsedMillis) {
        flushStream();
        List<WebFinding> findings = new ArrayList<>();
        for (Finding f : result.findingList()) findings.add(toWeb(f));
        exec("window.AICR&&AICR.render(" + gson.toJson(findings) + ");");

        String risk = result.getRiskLevel() != null ? result.getRiskLevel().toUpperCase(Locale.ROOT) : "UNKNOWN";
        String status = String.format("Done in %.1fs · risk %s · %d must-do, %d should-do",
                elapsedMillis / 1000.0, risk,
                result.countOf(Severity.MUST), result.countOf(Severity.SHOULD));
        if (result.getSummary() != null && !result.getSummary().isBlank()) {
            trace("Summary: " + result.getSummary());
        }
        setRunning(false, status);
    }

    @Override
    public void onError(String message) {
        flushStream();
        exec("window.AICR&&AICR.error(" + json(message) + ");");
        setRunning(false, "Failed — see the Trace tab");
    }

    // ------------------------------------------------------------- page driving

    private void trace(String line) {
        exec("window.AICR&&AICR.trace(" + json(line) + ");");
    }

    private void flushStream() {
        String chunk;
        synchronized (streamBuffer) {
            if (streamBuffer.isEmpty()) return;
            chunk = streamBuffer.toString();
            streamBuffer.setLength(0);
        }
        exec("window.AICR&&AICR.chunk(" + json(chunk) + ");");
    }

    private void setRunning(boolean value, String status) {
        running = value;
        String statusArg = status != null ? json(status) : "null";
        exec("window.AICR&&AICR.busy(" + value + "," + statusArg + ");");
    }

    private void setSelectedScope(ReviewSource source) {
        exec("(function(){var s=document.getElementById('scope');if(s)s.value=" + json(source.name()) + ";})();");
    }

    private void refreshModelLine() {
        AiReviewSettings settings = AiReviewSettings.getInstance();
        LlmProvider provider = settings.provider();
        String token = ApiKeyStore.isConfigured(provider) ? "token set" : "no token";
        String line = provider.getDisplayName() + " — " + settings.getState().model + " (" + token + ")";
        exec("window.AICR&&AICR.model(" + json(line) + ");");
    }

    // ------------------------------------------------------------- editor nav

    private void openInEditor(String path, int line) {
        VirtualFile file = resolveFile(path);
        if (file == null) {
            exec("window.AICR&&AICR.toast(" + json("Can't locate " + path + " in the project") + ",true);");
            return;
        }
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, Math.max(0, line - 1), 0);
        FileEditorManagerEx.getInstanceEx(project).openTextEditor(descriptor, true);
    }

    @SuppressWarnings("deprecation")
    private VirtualFile resolveFile(String path) {
        LocalFileSystem lfs = LocalFileSystem.getInstance();
        VirtualFile direct = lfs.findFileByPath(path);
        if (direct != null) return direct;
        String base = project.getBasePath();
        if (base != null) {
            VirtualFile underBase = lfs.findFileByPath(base + "/" + path);
            if (underBase != null) return underBase;
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                VirtualFile relative = baseDir.findFileByRelativePath(path);
                if (relative != null) return relative;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- plumbing

    private void exec(String script) {
        if (pageLoaded) rawExec(script);
        else pending.add(script);
    }

    private void rawExec(String script) {
        CefBrowser cef = browser.getCefBrowser();
        String url = cef.getURL();
        cef.executeJavaScript(script, url != null ? url : "", 0);
    }

    private void drainPending() {
        String script;
        while ((script = pending.poll()) != null) {
            rawExec(script);
        }
    }

    private String json(String value) {
        return gson.toJson(value);
    }

    private static ReviewSource parseSource(String raw) {
        if (raw != null) {
            String id = raw.trim();
            for (ReviewSource s : ReviewSource.values()) {
                if (s.name().equals(id)) return s;
            }
        }
        return ReviewSource.UNCOMMITTED;
    }

    private String loadHtml() {
        try (InputStream stream = getClass().getResourceAsStream("/webview/panel.html")) {
            if (stream == null) {
                return "<html><body style='font-family:sans-serif;padding:16px'>"
                        + "panel.html resource is missing from the plugin.</body></html>";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body style='font-family:sans-serif;padding:16px'>"
                    + "Could not load panel.html: " + e.getMessage() + "</body></html>";
        }
    }

    private static void onEdt(Runnable block) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any());
    }

    @Override
    public void dispose() {
        flushTimer.stop();
        Disposer.dispose(runQuery);
        Disposer.dispose(stopQuery);
        Disposer.dispose(copyQuery);
        Disposer.dispose(openQuery);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static WebFinding toWeb(Finding f) {
        String sev = switch (f.severityEnum()) {
            case MUST -> "blocker";
            case SHOULD -> "major";
            case INFO -> "minor";
        };

        List<String> bodyParts = new ArrayList<>();
        if (notBlank(f.getExplanation())) bodyParts.add(f.getExplanation().trim());
        if (notBlank(f.getImpact())) bodyParts.add("Impact: " + f.getImpact().trim());
        if (notBlank(f.getOriginal()) && !notBlank(f.getSuggestion())) {
            bodyParts.add("Current code:\n`" + f.getOriginal() + "`");
        }

        List<String> ruleParts = new ArrayList<>();
        if (notBlank(f.getRule())) ruleParts.add(f.getRule());
        if (notBlank(f.getCategory())) ruleParts.add(f.getCategory());

        String body = String.join("\n\n", bodyParts);
        return new WebFinding(
                sev,
                f.titleOrDefault(),
                f.getFile() != null ? f.getFile() : "",
                f.getLine() != null ? f.getLine() : 0,
                String.join(" · ", ruleParts),
                body.isBlank() ? "No further detail provided." : body,
                notBlank(f.getSuggestion()) ? f.getSuggestion() : null
        );
    }

    /** JSON shape consumed by {@code AICR.render} in panel.html. */
    private static final class WebFinding {
        final String sev;
        final String title;
        final String file;
        final int line;
        final String rule;
        final String body;
        final String patch;

        WebFinding(String sev, String title, String file, int line, String rule, String body, String patch) {
            this.sev = sev;
            this.title = title;
            this.file = file;
            this.line = line;
            this.rule = rule;
            this.body = body;
            this.patch = patch;
        }
    }
}
