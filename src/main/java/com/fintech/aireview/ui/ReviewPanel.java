package com.fintech.aireview.ui;

import com.fintech.aireview.llm.LlmProvider;
import com.fintech.aireview.review.Finding;
import com.fintech.aireview.review.ReviewEngine;
import com.fintech.aireview.review.ReviewResult;
import com.fintech.aireview.review.Severity;
import com.fintech.aireview.settings.AiReviewConfigurable;
import com.fintech.aireview.settings.AiReviewSettings;
import com.fintech.aireview.settings.ApiKeyStore;
import com.fintech.aireview.vcs.ReviewContext;
import com.fintech.aireview.vcs.ReviewSource;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ReviewPanel extends JPanel implements ReviewView {

    private final Project project;

    private final JComboBox<ReviewSource> sourceCombo = new JComboBox<>(ReviewSource.values());
    private final JComboBox<String> filterCombo = new JComboBox<>(new String[]{"All findings", "Must Do only", "Should Do only"});
    private final JButton runButton = new JButton("Review");
    private final JButton stopButton = new JButton("Stop");
    private final JButton settingsButton = new JButton("Settings");
    private final JBLabel statusLabel = new JBLabel(" ");

    private final ScrollableVerticalPanel findingsContainer = new ScrollableVerticalPanel();
    private final JBTextArea traceArea = new JBTextArea();
    private final JBTextArea streamArea = new JBTextArea();
    private final JBTextArea contextArea = new JBTextArea();

    private final StringBuilder streamBuffer = new StringBuilder();
    private final Timer flushTimer = new Timer(120, e -> flushStream());

    private volatile ProgressIndicator indicator;
    private volatile boolean running;

    private ReviewResult lastResult;

    public ReviewPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty(6));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        wireActions();
        refreshStatus();
        setRunning(false);
        flushTimer.setRepeats(true);
        flushTimer.start();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    // ---------------------------------------------------------------- layout

    private JComponent buildToolbar() {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
        sourceCombo.setToolTipText("What should be reviewed");
        sourceCombo.setPreferredSize(new Dimension(JBUI.scale(250), sourceCombo.getPreferredSize().height));
        runButton.setToolTipText("Run the AI code review");
        stopButton.setToolTipText("Cancel the running review");
        filterCombo.setToolTipText("Filter the result list");

        row.add(new JBLabel("Review:"));
        row.add(sourceCombo);
        row.add(runButton);
        row.add(stopButton);
        row.add(filterCombo);
        row.add(settingsButton);
        row.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.add(row);

        statusLabel.setForeground(UIUtil.getContextHelpForeground());
        statusLabel.setBorder(JBUI.Borders.emptyLeft(8));
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.add(statusLabel);
        return wrapper;
    }

    private JComponent buildCenter() {
        findingsContainer.setBorder(JBUI.Borders.empty(4));

        JBScrollPane findingsScroll = new JBScrollPane(findingsContainer);
        findingsScroll.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        findingsScroll.getVerticalScrollBar().setUnitIncrement(16);

        configureLogArea(traceArea);
        configureLogArea(streamArea);
        configureLogArea(contextArea);

        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Trace", new JBScrollPane(traceArea));
        tabs.addTab("Model stream", new JBScrollPane(streamArea));
        tabs.addTab("Reviewed context", new JBScrollPane(contextArea));

        JBSplitter splitter = new JBSplitter(true, 0.62f);
        splitter.setFirstComponent(findingsScroll);
        splitter.setSecondComponent(tabs);
        return splitter;
    }

    private static void configureLogArea(JBTextArea area) {
        area.setEditable(false);
        area.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
    }

    private void wireActions() {
        runButton.addActionListener(e -> startReview((ReviewSource) sourceCombo.getSelectedItem()));
        stopButton.addActionListener(e -> {
            ProgressIndicator current = indicator;
            if (current != null) current.cancel();
        });
        settingsButton.addActionListener(e -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiReviewConfigurable.class);
            refreshStatus();
        });
        filterCombo.addActionListener(e -> {
            if (lastResult != null) renderFindings(lastResult);
        });
    }

    // ---------------------------------------------------------------- public

    @Override
    public void startReview(ReviewSource source) {
        if (running) return;
        AiReviewSettings.State state = AiReviewSettings.getInstance().getState();
        LlmProvider provider = AiReviewSettings.getInstance().provider();
        if (!ApiKeyStore.isConfigured(provider) && provider != LlmProvider.CUSTOM) {
            appendTrace("No API token configured for " + provider.getDisplayName() + ". Open Settings and add one.");
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiReviewConfigurable.class);
            refreshStatus();
            return;
        }

        sourceCombo.setSelectedItem(source);
        setRunning(true);
        clearOutputs();
        appendTrace("Provider: " + provider.getDisplayName() + "   Model: " + state.model);

        ReviewEngine engine = project.getService(ReviewEngine.class);
        ReviewPanel panel = this;
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI code review", true) {
            @Override
            public void run(@NotNull ProgressIndicator progress) {
                indicator = progress;
                progress.setIndeterminate(true);
                engine.run(source, panel, progress);
            }

            @Override
            public void onFinished() {
                indicator = null;
                setRunning(false);
            }
        });
    }

    // -------------------------------------------------------- ReviewListener

    @Override
    public void onTrace(String line) {
        appendTrace(line);
    }

    @Override
    public void onDelta(String text) {
        synchronized (streamBuffer) {
            streamBuffer.append(text);
        }
    }

    @Override
    public void onContextReady(ReviewContext context) {
        onEdt(() -> {
            contextArea.setText(context.header() + "\n" + context.payload());
            contextArea.setCaretPosition(0);
            statusLabel.setText("Reviewing " + context.changedFiles().size() + " file(s) - "
                    + context.payload().length() + " chars of context");
        });
    }

    @Override
    public void onFinished(ReviewResult result, long elapsedMillis) {
        onEdt(() -> {
            flushStream();
            lastResult = result;
            renderFindings(result);
            statusLabel.setText("Done in " + (elapsedMillis / 1000.0) + "s - "
                    + result.countOf(Severity.MUST) + " Must Do, " + result.countOf(Severity.SHOULD) + " Should Do");
        });
    }

    @Override
    public void onError(String message) {
        onEdt(() -> {
            flushStream();
            findingsContainer.removeAll();
            JBLabel label = new JBLabel("<html><b>Review failed</b><br>" + escape(message) + "</html>");
            label.setForeground(new JBColor(0xC62828, 0xEF5350));
            label.setBorder(JBUI.Borders.empty(12));
            label.setAlignmentX(LEFT_ALIGNMENT);
            findingsContainer.add(label);
            findingsContainer.revalidate();
            findingsContainer.repaint();
            statusLabel.setText("Failed - see the Trace tab");
        });
    }

    // --------------------------------------------------------------- helpers

    private void renderFindings(ReviewResult result) {
        findingsContainer.removeAll();
        findingsContainer.add(summaryCard(result));

        int filter = filterCombo.getSelectedIndex();
        List<Finding> visible = result.findingList().stream()
                .filter(f -> switch (filter) {
                    case 1 -> f.severityEnum() == Severity.MUST;
                    case 2 -> f.severityEnum() == Severity.SHOULD;
                    default -> true;
                })
                .sorted(Comparator.comparingInt(f -> f.severityEnum() == Severity.MUST ? 0 : 1))
                .toList();

        if (visible.isEmpty()) {
            JBLabel empty = new JBLabel("No findings in this category.");
            empty.setForeground(UIUtil.getContextHelpForeground());
            empty.setBorder(JBUI.Borders.empty(16, 12));
            empty.setAlignmentX(LEFT_ALIGNMENT);
            findingsContainer.add(empty);
        } else {
            for (Finding finding : visible) {
                FindingCard card = new FindingCard(finding);
                card.setAlignmentX(LEFT_ALIGNMENT);
                findingsContainer.add(card);
                JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
                sep.setAlignmentX(LEFT_ALIGNMENT);
                sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(1)));
                findingsContainer.add(sep);
            }
        }
        findingsContainer.add(Box.createVerticalGlue());
        findingsContainer.revalidate();
        findingsContainer.repaint();
    }

    private JComponent summaryCard(ReviewResult result) {
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        card.setBorder(JBUI.Borders.empty(10, 12));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        String risk = result.getRiskLevel() != null ? result.getRiskLevel().toUpperCase(Locale.ROOT) : "UNKNOWN";
        JBLabel title = new JBLabel(
                "Summary  -  risk " + risk + "  -  " + Severity.MUST.getEmoji() + " " + result.countOf(Severity.MUST)
                        + " Must Do  " + Severity.SHOULD.getEmoji() + " " + result.countOf(Severity.SHOULD) + " Should Do"
        );
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 1f));
        head.add(title, BorderLayout.WEST);

        JButton copyAll = new JButton("Copy all fixes");
        copyAll.addActionListener(e -> {
            String text = result.findingList().stream()
                    .map(ReviewPanel::formatForClipboard)
                    .collect(Collectors.joining("\n\n"));
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(copyAll);
        head.add(buttons, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        JBTextArea summary = new JBTextArea(result.getSummary() != null ? result.getSummary() : "");
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        summary.setFont(UIUtil.getLabelFont());
        summary.setBorder(JBUI.Borders.emptyTop(6));
        card.add(summary, BorderLayout.CENTER);
        return card;
    }

    private static String formatForClipboard(Finding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(finding.severityEnum().getLabel()).append(": ").append(finding.titleOrDefault()).append('\n');
        String location = finding.location();
        if (!location.isBlank()) sb.append("// ").append(location).append('\n');
        if (finding.getSuggestion() != null) sb.append(finding.getSuggestion()).append('\n');
        return sb.toString();
    }

    private void clearOutputs() {
        traceArea.setText("");
        streamArea.setText("");
        contextArea.setText("");
        synchronized (streamBuffer) {
            streamBuffer.setLength(0);
        }
        findingsContainer.removeAll();
        JBLabel waiting = new JBLabel("Running review...");
        waiting.setForeground(UIUtil.getContextHelpForeground());
        waiting.setBorder(JBUI.Borders.empty(16, 12));
        waiting.setAlignmentX(LEFT_ALIGNMENT);
        findingsContainer.add(waiting);
        findingsContainer.revalidate();
        findingsContainer.repaint();
    }

    private void flushStream() {
        String chunk;
        synchronized (streamBuffer) {
            if (streamBuffer.isEmpty()) return;
            chunk = streamBuffer.toString();
            streamBuffer.setLength(0);
        }
        streamArea.append(chunk);
        streamArea.setCaretPosition(streamArea.getDocument().getLength());
    }

    private void appendTrace(String line) {
        onEdt(() -> {
            traceArea.append(line);
            traceArea.append("\n");
            traceArea.setCaretPosition(traceArea.getDocument().getLength());
        });
    }

    private void setRunning(boolean value) {
        onEdt(() -> {
            running = value;
            runButton.setEnabled(!value);
            stopButton.setEnabled(value);
            sourceCombo.setEnabled(!value);
            runButton.setText(value ? "Reviewing..." : "Review");
        });
    }

    private void refreshStatus() {
        AiReviewSettings settings = AiReviewSettings.getInstance();
        LlmProvider provider = settings.provider();
        String configured = ApiKeyStore.isConfigured(provider) ? "token set" : "no token";
        statusLabel.setText(provider.getDisplayName() + " - " + settings.getState().model + " (" + configured + ")");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void onEdt(Runnable block) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any());
    }
}
