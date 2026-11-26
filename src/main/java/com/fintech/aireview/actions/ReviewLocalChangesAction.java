package com.fintech.aireview.actions;

import com.fintech.aireview.vcs.ReviewSource;

public class ReviewLocalChangesAction extends AbstractReviewAction {
    public ReviewLocalChangesAction() {
        super(ReviewSource.UNCOMMITTED);
    }
}
