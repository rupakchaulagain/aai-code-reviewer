package com.fintech.aireview.llm;

import com.fintech.aireview.settings.AiReviewSettings;
import com.fintech.aireview.settings.ApiKeyStore;

public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    public static LlmClient create(AiReviewSettings.State state) {
        return create(state, ApiKeyStore.get(LlmProvider.fromId(state.provider)));
    }

    public static LlmClient create(AiReviewSettings.State state, String key) {
        LlmProvider provider = LlmProvider.fromId(state.provider);
        return switch (provider) {
            case ANTHROPIC -> new AnthropicClient(state, key);
            case GEMINI -> new GeminiClient(state, key);
            case OPENAI -> new OpenAiCompatibleClient(state, key, LlmProvider.OPENAI);
            case CUSTOM -> new OpenAiCompatibleClient(state, key, LlmProvider.CUSTOM);
        };
    }
}
