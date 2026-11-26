package com.fintech.aireview.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

/** Lets actions elsewhere in the IDE drive the tool window panel. */
@Service(Service.Level.PROJECT)
public final class ReviewUiService {

    private volatile ReviewView panel;

    @SuppressWarnings("unused")
    public ReviewUiService(Project project) {
    }

    public ReviewView getPanel() {
        return panel;
    }

    public void setPanel(ReviewView panel) {
        this.panel = panel;
    }
}
