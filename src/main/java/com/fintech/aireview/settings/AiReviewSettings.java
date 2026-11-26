package com.fintech.aireview.settings;

import com.fintech.aireview.llm.LlmProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Application level, persisted plugin configuration.
 * API tokens are NOT stored here - they live in the IDE PasswordSafe (see {@link ApiKeyStore}).
 */
@Service(Service.Level.APP)
@State(
        name = "FintechAiCodeReviewSettings",
        storages = @Storage("fintechAiCodeReview.xml")
)
public final class AiReviewSettings implements PersistentStateComponent<AiReviewSettings.State> {

    /** Public fields are serialised by the IntelliJ XmlSerializer. */
    public static class State {
        public String provider = "ANTHROPIC";
        public String model = "claude-sonnet-5";
        public String customBaseUrl = "";
        public int maxTokens = 8192;
        public double temperature = 0.1;
        public boolean streaming = true;
        public int requestTimeoutSeconds = 180;

        /** Hard cap on how much diff text is sent to the model. */
        public int maxContextChars = 60000;

        /** Used by the "Last N commits" review source. */
        public int commitCount = 1;

        /** Used by the "Branch vs base (pull request)" review source. */
        public String baseBranch = "origin/main";

        /** Extra house rules appended to the system prompt. */
        public String extraRules = "";

        public boolean includeUntrackedFiles = true;
    }

    private State state = new State();

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    public LlmProvider provider() {
        return LlmProvider.fromId(state.provider);
    }

    public static AiReviewSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiReviewSettings.class);
    }
}
