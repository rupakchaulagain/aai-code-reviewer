package com.fintech.aireview.review;

import com.fintech.aireview.vcs.ReviewContext;

public interface ReviewListener {
    void onTrace(String line);

    void onDelta(String text);

    void onContextReady(ReviewContext context);

    void onFinished(ReviewResult result, long elapsedMillis);

    void onError(String message);
}
