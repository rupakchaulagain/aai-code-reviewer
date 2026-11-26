package com.fintech.aireview.llm;

/**
 * Callbacks used while a completion is streaming so the tool window can trace it live.
 */
public interface LlmCallContext {
    /** Called for every streamed text fragment. */
    void onDelta(String text);

    /** Human readable progress line for the trace console. */
    void onTrace(String line);

    /** Throws if the user pressed Stop / cancelled the background task. */
    void checkCancelled();
}
