package com.fintech.aireview.llm;

public interface LlmClient {
    LlmProvider getProvider();

    /** Returns the full assistant text (already accumulated from the stream). */
    String complete(String systemPrompt, String userPrompt, LlmCallContext ctx);
}
