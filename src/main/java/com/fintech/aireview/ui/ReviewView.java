package com.fintech.aireview.ui;

import com.fintech.aireview.review.ReviewListener;
import com.fintech.aireview.vcs.ReviewSource;

import javax.swing.JComponent;

/**
 * The tool-window review UI, as seen by the rest of the plugin (actions, the
 * tool-window factory, the {@link ReviewUiService}). Two implementations exist:
 *
 * <ul>
 *   <li>{@link WebReviewPanel} - the rich JCEF/HTML UI, used whenever JCEF is available.</li>
 *   <li>{@link ReviewPanel} - a plain Swing fallback for IDEs without an embedded browser.</li>
 * </ul>
 */
public interface ReviewView extends ReviewListener {
    /** The Swing component to install into the tool window. */
    JComponent getComponent();

    /** Kick off a review for {@code source}; ignored if one is already running. */
    void startReview(ReviewSource source);
}
