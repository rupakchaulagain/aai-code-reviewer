package com.fintech.aireview.llm;

import java.util.List;

public enum LlmProvider {
    ANTHROPIC(
            "Anthropic (Claude)",
            "https://api.anthropic.com",
            "claude-sonnet-5",
            List.of("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001"),
            "sk-ant-..."
    ),
    OPENAI(
            "OpenAI",
            "https://api.openai.com",
            "gpt-4o",
            List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"),
            "sk-..."
    ),
    GEMINI(
            "Google (Gemini)",
            "https://generativelanguage.googleapis.com",
            "gemini-2.5-pro",
            List.of("gemini-2.5-pro", "gemini-2.5-flash"),
            "AIza..."
    ),
    CUSTOM(
            "Custom / self-hosted (OpenAI-compatible)",
            "http://localhost:11434",
            "qwen2.5-coder:14b",
            List.of("qwen2.5-coder:14b", "llama3.1:70b", "deepseek-coder-v2"),
            "optional"
    );

    private final String displayName;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final List<String> suggestedModels;
    private final String tokenHint;

    LlmProvider(String displayName, String defaultBaseUrl, String defaultModel,
                List<String> suggestedModels, String tokenHint) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.suggestedModels = suggestedModels;
        this.tokenHint = tokenHint;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public List<String> getSuggestedModels() {
        return suggestedModels;
    }

    public String getTokenHint() {
        return tokenHint;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static LlmProvider fromId(String id) {
        for (LlmProvider p : values()) {
            if (p.name().equalsIgnoreCase(id)) return p;
        }
        return ANTHROPIC;
    }
}
