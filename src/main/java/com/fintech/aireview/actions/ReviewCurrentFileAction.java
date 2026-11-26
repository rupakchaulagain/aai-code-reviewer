package com.fintech.aireview.actions;

import com.fintech.aireview.vcs.ReviewSource;

public class ReviewCurrentFileAction extends AbstractReviewAction {
    public ReviewCurrentFileAction() {
        super(ReviewSource.CURRENT_FILE);
    }
}
