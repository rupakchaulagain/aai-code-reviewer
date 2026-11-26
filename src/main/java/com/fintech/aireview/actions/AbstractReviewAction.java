package com.fintech.aireview.actions;

import com.fintech.aireview.ui.ReviewUiService;
import com.fintech.aireview.ui.ReviewView;
import com.fintech.aireview.vcs.ReviewSource;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractReviewAction extends AnAction implements DumbAware {

    public static final String TOOL_WINDOW_ID = "AI Code Review";

    private final ReviewSource source;

    protected AbstractReviewAction(ReviewSource source) {
        this.source = source;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.PROJECT) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        if (project == null) return;
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) return;
        toolWindow.activate(() -> {
            ReviewView panel = project.getService(ReviewUiService.class).getPanel();
            if (panel != null) panel.startReview(source);
        });
    }
}
