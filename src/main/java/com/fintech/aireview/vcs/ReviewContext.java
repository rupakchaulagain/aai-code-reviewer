package com.fintech.aireview.vcs;

import java.util.List;

/**
 * Everything that will be handed to the model, plus the metadata the tool window
 * shows while tracing the review.
 */
public record ReviewContext(
        ReviewSource source,
        String header,
        String payload,
        List<String> changedFiles,
        boolean truncated
) {
    public ReviewContext {
        changedFiles = List.copyOf(changedFiles);
    }

    public boolean isEmpty() {
        return payload.isBlank();
    }
}
