package com.fintech.aireview.vcs;

import com.fintech.aireview.text.UiText;

/** What a review looks at. Display names live in ui-text.yml under {@code scopes.<NAME>}. */
public enum ReviewSource {
    UNCOMMITTED,
    STAGED,
    LAST_COMMIT,
    LAST_N_COMMITS,
    BRANCH_VS_BASE,
    CURRENT_FILE;

    public String getDisplayName() {
        return UiText.get("scopes." + name());
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
