package com.fintech.aireview.review;

import com.fintech.aireview.text.UiText;

import java.util.Locale;

public enum Severity {
    MUST("blocker", "🔴"),
    SHOULD("major", "🟡"),
    INFO("minor", "🔵");

    private final String uiKey;
    private final String emoji;

    Severity(String uiKey, String emoji) {
        this.uiKey = uiKey;
        this.emoji = emoji;
    }

    /** Severity id used by the HTML panel and as the {@code severity.<id>} key in ui-text.yml. */
    public String getUiKey() {
        return uiKey;
    }

    public String getLabel() {
        return UiText.get("severity." + uiKey);
    }

    public String getEmoji() {
        return emoji;
    }

    public static Severity parse(String raw) {
        if (raw == null) return SHOULD;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "MUST", "MUST_DO", "MUST DO", "CRITICAL", "BLOCKER", "HIGH" -> MUST;
            case "INFO", "NIT", "NOTE", "LOW" -> INFO;
            default -> SHOULD;
        };
    }
}
