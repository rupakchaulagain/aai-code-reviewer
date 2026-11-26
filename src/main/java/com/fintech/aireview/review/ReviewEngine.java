package com.fintech.aireview.review;

import com.fintech.aireview.llm.LlmCallContext;
import com.fintech.aireview.llm.LlmClient;
import com.fintech.aireview.llm.LlmClientFactory;
import com.fintech.aireview.settings.AiReviewSettings;
import com.fintech.aireview.text.UiText;
import com.fintech.aireview.vcs.GitContextService;
import com.fintech.aireview.vcs.ReviewContext;
import com.fintech.aireview.vcs.ReviewSource;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.Locale;

@Service(Service.Level.PROJECT)
public final class ReviewEngine {

    private final Project project;

    public ReviewEngine(Project project) {
        this.project = project;
    }

    public void run(ReviewSource source, ReviewListener listener, ProgressIndicator indicator) {
        long started = System.currentTimeMillis();
        AiReviewSettings.State state = AiReviewSettings.getInstance().getState();

        try {
            indicator.setText(UiText.get("engine.collecting"));
            listener.onTrace(UiText.get("engine.started", "scope", source.getDisplayName()));

            ReviewContext context = project.getService(GitContextService.class)
                    .collect(source, state, listener::onTrace);
            listener.onContextReady(context);

            if (context.isEmpty()) {
                listener.onTrace(context.header());
                ReviewResult empty = new ReviewResult();
                empty.setSummary(context.header());
                empty.setRiskLevel("LOW");
                empty.setFindings(new ArrayList<>());
                listener.onFinished(empty, System.currentTimeMillis() - started);
                return;
            }

            indicator.checkCanceled();
            indicator.setText(UiText.get("engine.asking",
                    "provider", state.provider.toLowerCase(Locale.ROOT), "model", state.model));

            LlmClient client = LlmClientFactory.create(state);
            String systemPrompt = ReviewPromptBuilder.systemPrompt(state);
            String userPrompt = ReviewPromptBuilder.userPrompt(context);
            listener.onTrace(UiText.get("engine.promptSize", "chars", systemPrompt.length() + userPrompt.length()));

            LlmCallContext ctx = new LlmCallContext() {
                @Override
                public void onDelta(String text) {
                    listener.onDelta(text);
                }

                @Override
                public void onTrace(String line) {
                    listener.onTrace(line);
                }

                @Override
                public void checkCancelled() {
                    indicator.checkCanceled();
                }
            };

            String raw = client.complete(systemPrompt, userPrompt, ctx);
            indicator.checkCanceled();

            indicator.setText(UiText.get("engine.parsing"));
            ReviewResult result = ReviewResponseParser.parse(raw);
            long elapsed = System.currentTimeMillis() - started;
            listener.onTrace(UiText.get("engine.complete", "seconds", elapsed / 1000.0,
                    "must", result.countOf(Severity.MUST), "should", result.countOf(Severity.SHOULD)));
            listener.onFinished(result, elapsed);
        } catch (ProcessCanceledException e) {
            listener.onTrace(UiText.get("engine.cancelled"));
            throw e;
        } catch (Throwable e) {
            listener.onTrace(UiText.get("engine.error", "message", e.getMessage()));
            listener.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
