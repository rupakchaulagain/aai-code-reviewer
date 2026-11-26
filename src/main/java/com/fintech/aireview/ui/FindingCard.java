package com.fintech.aireview.ui;

import com.fintech.aireview.review.Finding;
import com.fintech.aireview.review.Severity;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;

public class FindingCard extends JBPanel<FindingCard> {

    private static final JBColor MUST_COLOR = new JBColor(0xC62828, 0xEF5350);
    private static final JBColor SHOULD_COLOR = new JBColor(0xB26A00, 0xFFB74D);
    private static final JBColor INFO_COLOR = new JBColor(0x1565C0, 0x64B5F6);

    private final Finding finding;
    private final Severity severity;

    public FindingCard(Finding finding) {
        super(new BorderLayout());
        this.finding = finding;
        this.severity = finding.severityEnum();

        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, accentColor()),
                JBUI.Borders.empty(8, 10, 12, 8)
        ));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private JBColor accentColor() {
        return switch (severity) {
            case MUST -> MUST_COLOR;
            case SHOULD -> SHOULD_COLOR;
            case INFO -> INFO_COLOR;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JBLabel title = new JBLabel(severity.getEmoji() + "  " + finding.titleOrDefault());
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 1f));
        title.setForeground(accentColor());
        header.add(title, BorderLayout.CENTER);

        StringBuilder meta = new StringBuilder();
        if (notBlank(finding.getRule())) meta.append(finding.getRule());
        if (notBlank(finding.getCategory())) {
            if (!meta.isEmpty()) meta.append("  |  ");
            meta.append(finding.getCategory());
        }
        String location = finding.location();
        if (!location.isBlank()) {
            if (!meta.isEmpty()) meta.append("  |  ");
            meta.append(location);
        }
        if (!meta.isEmpty()) {
            JBLabel metaLabel = new JBLabel(meta.toString());
            metaLabel.setForeground(UIUtil.getContextHelpForeground());
            metaLabel.setBorder(JBUI.Borders.emptyTop(2));
            header.add(metaLabel, BorderLayout.SOUTH);
        }
        return header;
    }

    private JComponent buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(JBUI.Borders.emptyTop(8));

        if (notBlank(finding.getExplanation())) {
            body.add(paragraph("Why this was flagged", finding.getExplanation()));
        }
        if (notBlank(finding.getImpact())) {
            body.add(Box.createVerticalStrut(JBUI.scale(6)));
            body.add(paragraph("Impact", finding.getImpact()));
        }
        if (notBlank(finding.getOriginal())) {
            body.add(Box.createVerticalStrut(JBUI.scale(8)));
            body.add(codeBlock("Current code", finding.getOriginal(), false));
        }
        if (notBlank(finding.getSuggestion())) {
            body.add(Box.createVerticalStrut(JBUI.scale(8)));
            body.add(codeBlock("Suggested fix", finding.getSuggestion(), true));
        }
        return body;
    }

    private JComponent paragraph(String caption, String text) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(LEFT_ALIGNMENT);

        JBLabel label = new JBLabel(caption);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(UIUtil.getContextHelpForeground());
        holder.add(label, BorderLayout.NORTH);

        JBTextArea area = new JBTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.emptyTop(3));
        area.setFont(UIUtil.getLabelFont());
        holder.add(area, BorderLayout.CENTER);
        return holder;
    }

    private JComponent codeBlock(String caption, String code, boolean primary) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(LEFT_ALIGNMENT);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JBLabel label = new JBLabel(caption);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(UIUtil.getContextHelpForeground());
        top.add(label, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        buttons.setOpaque(false);
        String idleText = primary ? "Copy code" : "Copy";
        JButton copy = new JButton(idleText);
        copy.setToolTipText("Copy this snippet to the clipboard");
        copy.addActionListener(e -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(code));
            copy.setText("Copied");
            Timer timer = new Timer(1200, ev -> copy.setText(idleText));
            timer.setRepeats(false);
            timer.start();
        });
        buttons.add(copy);
        top.add(buttons, BorderLayout.EAST);
        holder.add(top, BorderLayout.NORTH);

        JBTextArea area = new JBTextArea(code);
        area.setEditable(false);
        area.setFont(JBUI.Fonts.create(Font.MONOSPACED, 12));
        area.setBorder(JBUI.Borders.empty(6));
        area.setBackground(UIUtil.getTextFieldBackground());
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty()
        ));
        wrapper.add(area, BorderLayout.CENTER);
        holder.add(wrapper, BorderLayout.CENTER);

        return holder;
    }
}
