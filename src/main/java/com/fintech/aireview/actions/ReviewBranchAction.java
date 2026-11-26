package com.fintech.aireview.actions;

import com.fintech.aireview.vcs.ReviewSource;

public class ReviewBranchAction extends AbstractReviewAction {
    public ReviewBranchAction() {
        super(ReviewSource.BRANCH_VS_BASE);
    }
}
