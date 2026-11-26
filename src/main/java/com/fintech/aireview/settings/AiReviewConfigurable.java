package com.fintech.aireview.settings;

import com.fintech.aireview.llm.LlmCallContext;
import com.fintech.aireview.llm.LlmClient;
import com.fintech.aireview.llm.LlmClientFactory;
import com.fintech.aireview.llm.LlmProvider;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Objects;

public class AiReviewConfigurable implements Configurable {

    private final JComboBox<LlmProvider> providerCombo = new JComboBox<>(LlmProvider.values());
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JBPasswordField tokenField = new JBPasswordField();
    private final JBTextField baseUrlField = new JBTextField();
    private final JBTextField maxTokensField = new JBTextField();
    private final JBTextField temperatureField = new JBTextField();
    private final JBTextField timeoutField = new JBTextField();
    private final JBTextField contextField = new JBTextField();
    private final JBTextField commitCountField = new JBTextField();
    private final JBTextField baseBranchField = new JBTextField();
    private final JBCheckBox streamingCheck = new JBCheckBox("Stream the review live into the tool window");
    private final JBCheckBox untrackedCheck = new JBCheckBox("Include new untracked files when reviewing local changes");
    private final JBTextArea extraRulesArea = new JBTextArea(6, 60);

    private JPanel root;
    private LlmProvider loadedTokenProvider;

    public AiReviewConfigurable() {
        modelCombo.setEditable(true);
    }

    @Override
    public @Nls String getDisplayName() {
        return "AI Code Reviewer";
    }

    @Override
    public JComponent createComponent() {
        JPanel tokenRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        tokenRow.add(tokenField, BorderLayout.CENTER);
        JButton testButton = new JButton("Test connection");
        testButton.addActionListener(e -> testConnection());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        right.add(testButton);
        tokenRow.add(right, BorderLayout.EAST);

        extraRulesArea.setLineWrap(true);
        extraRulesArea.setWrapStyleWord(true);
        JBScrollPane rulesScroll = new JBScrollPane(extraRulesArea);
        rulesScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(120)));

        providerCombo.addActionListener(e -> onProviderChanged());

        JBLabel hint = new JBLabel(
                "<html>Tokens are stored in the IDE credential store (OS keychain), never in project files.<br>"
                        + "Leave the base URL empty to use the provider default. Use <b>Custom</b> for Ollama, vLLM, "
                        + "OpenRouter or an internal OpenAI-compatible gateway.</html>"
        );
        hint.setForeground(UIUtil.getContextHelpForeground());

        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(hint)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Provider:"), providerCombo, 1, false)
                .addLabeledComponent(new JBLabel("Model:"), modelCombo, 1, false)
                .addLabeledComponent(new JBLabel("API token:"), tokenRow, 1, false)
                .addLabeledComponent(new JBLabel("Custom API base URL:"), baseUrlField, 1, false)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Max output tokens:"), maxTokensField, 1, false)
                .addLabeledComponent(new JBLabel("Temperature (0.0 - 1.0):"), temperatureField, 1, false)
                .addLabeledComponent(new JBLabel("Request timeout (seconds):"), timeoutField, 1, false)
                .addLabeledComponent(new JBLabel("Max context characters:"), contextField, 1, false)
                .addComponent(streamingCheck)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Commits for 'Last N commits':"), commitCountField, 1, false)
                .addLabeledComponent(new JBLabel("Base branch for PR review:"), baseBranchField, 1, false)
                .addComponent(untrackedCheck)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Extra house rules (appended to the system prompt):"), rulesScroll, 1, true)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        panel.setBorder(JBUI.Borders.empty(10));
        root = panel;
        return panel;
    }

    private LlmProvider selectedProvider() {
        Object item = providerCombo.getSelectedItem();
        return item instanceof LlmProvider p ? p : LlmProvider.ANTHROPIC;
    }

    private String modelText() {
        Object item = modelCombo.getEditor().getItem();
        return item == null ? "" : item.toString();
    }

    private void onProviderChanged() {
        LlmProvider provider = selectedProvider();
        String current = modelText();
        modelCombo.removeAllItems();
        provider.getSuggestedModels().forEach(modelCombo::addItem);
        modelCombo.setSelectedItem(provider.getSuggestedModels().contains(current) ? current : provider.getDefaultModel());

        baseUrlField.getEmptyText().setText(provider.getDefaultBaseUrl());
        tokenField.setToolTipText("Token format: " + provider.getTokenHint());
        tokenField.setText(ApiKeyStore.get(provider));
        loadedTokenProvider = provider;
    }

    private AiReviewSettings.State buildStateFromUi() {
        AiReviewSettings.State state = new AiReviewSettings.State();
        state.provider = selectedProvider().name();
        state.model = modelText().trim();
        state.customBaseUrl = baseUrlField.getText().trim();
        state.maxTokens = parseInt(maxTokensField.getText(), 8192);
        state.temperature = parseDouble(temperatureField.getText(), 0.1);
        state.requestTimeoutSeconds = parseInt(timeoutField.getText(), 180);
        state.maxContextChars = parseInt(contextField.getText(), 60000);
        state.streaming = streamingCheck.isSelected();
        state.commitCount = parseInt(commitCountField.getText(), 1);
        state.baseBranch = baseBranchField.getText().trim();
        state.includeUntrackedFiles = untrackedCheck.isSelected();
        state.extraRules = extraRulesArea.getText();
        return state;
    }

    @Override
    public boolean isModified() {
        AiReviewSettings.State saved = AiReviewSettings.getInstance().getState();
        AiReviewSettings.State ui = buildStateFromUi();
        boolean tokenChanged = !new String(tokenField.getPassword()).equals(ApiKeyStore.get(selectedProvider()));
        return tokenChanged
                || !Objects.equals(saved.provider, ui.provider)
                || !Objects.equals(saved.model, ui.model)
                || !Objects.equals(saved.customBaseUrl, ui.customBaseUrl)
                || saved.maxTokens != ui.maxTokens
                || saved.temperature != ui.temperature
                || saved.requestTimeoutSeconds != ui.requestTimeoutSeconds
                || saved.maxContextChars != ui.maxContextChars
                || saved.streaming != ui.streaming
                || saved.commitCount != ui.commitCount
                || !Objects.equals(saved.baseBranch, ui.baseBranch)
                || saved.includeUntrackedFiles != ui.includeUntrackedFiles
                || !Objects.equals(saved.extraRules, ui.extraRules);
    }

    @Override
    public void apply() {
        AiReviewSettings.State ui = buildStateFromUi();
        AiReviewSettings.State saved = AiReviewSettings.getInstance().getState();
        saved.provider = ui.provider;
        saved.model = ui.model;
        saved.customBaseUrl = ui.customBaseUrl;
        saved.maxTokens = clamp(ui.maxTokens, 256, 200_000);
        saved.temperature = Math.max(0.0, Math.min(1.0, ui.temperature));
        saved.requestTimeoutSeconds = clamp(ui.requestTimeoutSeconds, 30, 900);
        saved.maxContextChars = clamp(ui.maxContextChars, 4000, 400_000);
        saved.streaming = ui.streaming;
        saved.commitCount = clamp(ui.commitCount, 1, 20);
        saved.baseBranch = ui.baseBranch;
        saved.includeUntrackedFiles = ui.includeUntrackedFiles;
        saved.extraRules = ui.extraRules;
        ApiKeyStore.set(selectedProvider(), new String(tokenField.getPassword()));
    }

    @Override
    public void reset() {
        AiReviewSettings.State saved = AiReviewSettings.getInstance().getState();
        LlmProvider provider = LlmProvider.fromId(saved.provider);
        providerCombo.setSelectedItem(provider);

        modelCombo.removeAllItems();
        provider.getSuggestedModels().forEach(modelCombo::addItem);
        modelCombo.setSelectedItem(saved.model.isBlank() ? provider.getDefaultModel() : saved.model);

        tokenField.setText(ApiKeyStore.get(provider));
        tokenField.setToolTipText("Token format: " + provider.getTokenHint());
        loadedTokenProvider = provider;

        baseUrlField.setText(saved.customBaseUrl);
        baseUrlField.getEmptyText().setText(provider.getDefaultBaseUrl());
        maxTokensField.setText(String.valueOf(saved.maxTokens));
        temperatureField.setText(String.valueOf(saved.temperature));
        timeoutField.setText(String.valueOf(saved.requestTimeoutSeconds));
        contextField.setText(String.valueOf(saved.maxContextChars));
        streamingCheck.setSelected(saved.streaming);
        commitCountField.setText(String.valueOf(saved.commitCount));
        baseBranchField.setText(saved.baseBranch);
        untrackedCheck.setSelected(saved.includeUntrackedFiles);
        extraRulesArea.setText(saved.extraRules);
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }

    private void testConnection() {
        AiReviewSettings.State state = buildStateFromUi();
        state.streaming = false;
        state.maxTokens = 64;
        String key = new String(tokenField.getPassword());

        String result;
        try {
            result = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> {
                        LlmClient client = LlmClientFactory.create(state, key);
                        LlmCallContext ctx = new LlmCallContext() {
                            @Override
                            public void onDelta(String text) {
                            }

                            @Override
                            public void onTrace(String line) {
                            }

                            @Override
                            public void checkCancelled() {
                            }
                        };
                        return client.complete(
                                "You are a connectivity probe. Reply with the single word OK.",
                                "Reply with OK.",
                                ctx
                        );
                    },
                    "Testing " + state.provider + " connection", true, null
            );
        } catch (Exception e) {
            Messages.showErrorDialog(
                    "Connection failed.\n\n" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    "AI Code Reviewer"
            );
            return;
        }

        String reply = result.trim();
        Messages.showInfoMessage(
                "Connection succeeded.\n\nModel replied: " + (reply.length() <= 200 ? reply : reply.substring(0, 200)),
                "AI Code Reviewer"
        );
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
