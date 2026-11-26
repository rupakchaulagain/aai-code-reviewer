package com.fintech.aireview.actions;

import com.fintech.aireview.vcs.ReviewSource;

public class ReviewLastCommitAction extends AbstractReviewAction {
    public ReviewLastCommitAction() {
        super(ReviewSource.LAST_COMMIT);
    }
}
